package com.mpi.feeverify.ranking;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.RestrictedExecutor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行计算器：在冻结快照（snap_* 表）上按指标口径计算月度排行。
 * 预计算构建与查询时扫描（ON_DEMAND_SCAN）复用同一套口径语义与同一段 SQL，
 * 保证两条执行路径的结果可逐位比较。
 *
 * 口径四要素：
 * - 分类规则 categoryRules：[{"from":"谈判药","to":"乙类","drugCodes":[]}]，
 *   drugCodes 为空表示整类映射，非空表示仅调整列出的药品（局部分类调整）；
 * - 剔除规则 exclusionRules：{settleStatuses,drugCategories,drugCodes}，
 *   药品分类剔除作用于“分类映射之后”的有效分类；
 * - 排序规则 sortRules：{metric=AMOUNT/COUNT, direction=DESC/ASC}；
 * - 并列规则 tieRules：{strategy=COMPETITION/DENSE/SEQUENTIAL, tieBreaker=CODE_ASC}。
 */
@Component
public class RankingCalculator {

    public static final List<String> DIMENSIONS = List.of("ORG", "DEPT", "DRUG");

    private final RestrictedExecutor executor;

    public RankingCalculator(RestrictedExecutor executor) {
        this.executor = executor;
    }

    /** 口径解析后的结构化视图。 */
    public record Caliber(long id, int version, List<Map<String, Object>> categoryRules,
                          Map<String, Object> exclusionRules, Map<String, Object> sortRules,
                          Map<String, Object> tieRules) {

        @SuppressWarnings("unchecked")
        public static Caliber of(Map<String, Object> row) {
            return new Caliber(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("version")).intValue(),
                    Jsons.asListOfMaps(row.get("category_rules")),
                    Jsons.asMap(row.get("exclusion_rules")),
                    Jsons.asMap(row.get("sort_rules")),
                    Jsons.asMap(row.get("tie_rules")));
        }

        public String strategy() {
            return String.valueOf(tieRules.getOrDefault("strategy", "COMPETITION"));
        }

        public String metric() {
            return String.valueOf(sortRules.getOrDefault("metric", "AMOUNT"));
        }

        public String direction() {
            return String.valueOf(sortRules.getOrDefault("direction", "DESC"));
        }
    }

    /**
     * 计算单月排行。
     *
     * @param caliber   口径
     * @param month     月份 YYYY-MM
     * @param dimension ORG / DEPT / DRUG
     * @param orgScope  '*' 全省，或具体机构编码（授权过滤粒度）
     * @param limit     取前 N（null 表示全量）
     */
    public List<Map<String, Object>> rank(Caliber caliber, String month, String dimension,
                                          String orgScope, Integer limit) {
        return rank(caliber, month, dimension, orgScope, limit, Map.of());
    }

    /**
     * 计算单月排行（带临时筛选）。adhoc 仅查询时扫描路径支持，体现
     * “不用等待构建、能立即响应新增临时筛选，代价是深分页持续占用计算资源”。
     * 支持键：extraExcludedStatuses / extraExcludedCategories / extraExcludedDrugCodes /
     * minAmount / drugCodePrefix。
     */
    public List<Map<String, Object>> rank(Caliber caliber, String month, String dimension,
                                          String orgScope, Integer limit,
                                          Map<String, Object> adhoc) {
        if (!DIMENSIONS.contains(dimension)) {
            throw ApiException.badRequest("不支持的排行维度: " + dimension + "（支持 ORG/DEPT/DRUG）");
        }
        return executor.inRestrictedTx(15000, 5000, "16MB", session -> {
            String sql = buildSql(caliber, dimension, orgScope, adhoc);
            MapSqlParameterSource params = buildParams(caliber, month, orgScope, adhoc);
            List<Map<String, Object>> rows = session.np().queryForList(sql, params);
            List<Map<String, Object>> entries = assignRanks(caliber, rows, dimension);
            return limit == null ? entries : entries.stream().limit(limit).toList();
        });
    }

    /** 构造口径 SQL：有效分类 CASE 表达式 + 行级剔除 + 维度分组。 */
    private String buildSql(Caliber caliber, String dimension, String orgScope) {
        return buildSql(caliber, dimension, orgScope, Map.of());
    }

    private String buildSql(Caliber caliber, String dimension, String orgScope,
                            Map<String, Object> adhoc) {
        List<Map<String, Object>> rules = caliber.categoryRules();
        // 无分类规则时有效分类即原分类，避免生成无 WHEN 的非法 CASE
        String effExpr;
        if (rules.isEmpty()) {
            effExpr = "g.category";
        } else {
            StringBuilder effCase = new StringBuilder("CASE");
            for (int i = 0; i < rules.size(); i++) {
                Map<String, Object> rule = rules.get(i);
                List<Object> drugCodes = Jsons.asList(rule.get("drugCodes"));
                if (drugCodes.isEmpty()) {
                    effCase.append(" WHEN g.category = :from").append(i)
                            .append(" THEN :to").append(i);
                } else {
                    effCase.append(" WHEN g.drug_code IN (:codes").append(i)
                            .append(") THEN :to").append(i);
                }
            }
            effCase.append(" ELSE g.category END");
            effExpr = effCase.toString();
        }

        String groupExpr = switch (dimension) {
            case "ORG" -> "x.org_code";
            case "DEPT" -> "dd.category";
            default -> "x.drug_code";
        };
        String nameExpr = switch (dimension) {
            case "ORG" -> "o.org_name";
            case "DEPT" -> "dd.category";
            default -> "dg.drug_name";
        };

        StringBuilder sql = new StringBuilder("""
                SELECT %s AS code, %s AS name, SUM(x.amount) AS amount, COUNT(*) AS cnt,
                       MAX(x.eff_cat) AS eff_cat
                FROM (
                    SELECT f.id, f.org_code, f.diagnosis_code, f.drug_code, f.settle_status,
                           f.amount, f.settle_date, %s AS eff_cat
                    FROM snap_fee_detail f
                    JOIN snap_dim_drug g ON g.drug_code = f.drug_code
                ) x
                JOIN snap_dim_diagnosis dd ON dd.diagnosis_code = x.diagnosis_code
                LEFT JOIN snap_dim_org o ON o.org_code = x.org_code
                JOIN snap_dim_drug dg ON dg.drug_code = x.drug_code
                WHERE to_char(x.settle_date, 'YYYY-MM') = :month
                  AND x.settle_status NOT IN (:exStatuses)
                  AND x.eff_cat NOT IN (:exCategories)
                  AND x.drug_code NOT IN (:exDrugCodes)
                """.formatted(groupExpr, nameExpr, effExpr));
        if (orgScope != null && !"*".equals(orgScope)) {
            sql.append(" AND x.org_code = :scopeOrg");
        }
        if (!Jsons.asList(adhoc.get("extraExcludedStatuses")).isEmpty()) {
            sql.append(" AND x.settle_status NOT IN (:adhocStatuses)");
        }
        if (!Jsons.asList(adhoc.get("extraExcludedCategories")).isEmpty()) {
            sql.append(" AND x.eff_cat NOT IN (:adhocCategories)");
        }
        if (!Jsons.asList(adhoc.get("extraExcludedDrugCodes")).isEmpty()) {
            sql.append(" AND x.drug_code NOT IN (:adhocDrugCodes)");
        }
        if (adhoc.get("minAmount") != null) {
            sql.append(" AND x.amount >= :minAmount");
        }
        if (adhoc.get("drugCodePrefix") != null) {
            sql.append(" AND x.drug_code LIKE :drugPrefix");
        }
        sql.append(" GROUP BY ").append(groupExpr);
        if (!groupExpr.equals(nameExpr)) {
            sql.append(", ").append(nameExpr);
        }
        return sql.toString();
    }

    private MapSqlParameterSource buildParams(Caliber caliber, String month, String orgScope) {
        return buildParams(caliber, month, orgScope, Map.of());
    }

    private MapSqlParameterSource buildParams(Caliber caliber, String month, String orgScope,
                                              Map<String, Object> adhoc) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("month", month);
        List<Map<String, Object>> rules = caliber.categoryRules();
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = rules.get(i);
            p.addValue("from" + i, String.valueOf(rule.get("from")));
            p.addValue("to" + i, String.valueOf(rule.get("to")));
            List<Object> codes = Jsons.asList(rule.get("drugCodes"));
            if (!codes.isEmpty()) {
                p.addValue("codes" + i, codes.stream().map(String::valueOf).toList());
            }
        }
        Map<String, Object> ex = caliber.exclusionRules();
        // 空 NOT IN 列表用永不命中的哨兵值占位，保持“不剔除任何值”的语义且 SQL 合法
        p.addValue("exStatuses", nonEmptyOrSentinel(ex.get("settleStatuses")));
        p.addValue("exCategories", nonEmptyOrSentinel(ex.get("drugCategories")));
        p.addValue("exDrugCodes", nonEmptyOrSentinel(ex.get("drugCodes")));
        if (orgScope != null && !"*".equals(orgScope)) {
            p.addValue("scopeOrg", orgScope);
        }
        p.addValue("adhocStatuses", nonEmptyOrSentinel(adhoc.get("extraExcludedStatuses")));
        p.addValue("adhocCategories", nonEmptyOrSentinel(adhoc.get("extraExcludedCategories")));
        p.addValue("adhocDrugCodes", nonEmptyOrSentinel(adhoc.get("extraExcludedDrugCodes")));
        if (adhoc.get("minAmount") != null) {
            p.addValue("minAmount", new java.math.BigDecimal(String.valueOf(adhoc.get("minAmount"))));
        }
        if (adhoc.get("drugCodePrefix") != null) {
            p.addValue("drugPrefix", String.valueOf(adhoc.get("drugCodePrefix")) + "%");
        }
        return p;
    }

    private List<String> nonEmptyOrSentinel(Object v) {
        List<String> list = stringList(v, List.of());
        return list.isEmpty() ? List.of("__NONE__") : list;
    }

    private List<String> stringList(Object v, List<String> fallback) {
        if (v == null) {
            return fallback;
        }
        return Jsons.asList(v).stream().map(String::valueOf).toList();
    }

    /**
     * 按排序规则与并列规则赋名次。
     * 先按指标（金额/笔数）方向排序，同额以编码升序作为稳定次序；
     * 再按 COMPETITION（1,1,3）/ DENSE（1,1,2）/ SEQUENTIAL（1,2,3）赋名次。
     */
    private List<Map<String, Object>> assignRanks(Caliber caliber, List<Map<String, Object>> rows,
                                                  String dimension) {
        boolean byAmount = "AMOUNT".equals(caliber.metric());
        boolean desc = !"ASC".equals(caliber.direction());
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            int cmp;
            if (byAmount) {
                BigDecimal va = (BigDecimal) a.get("amount");
                BigDecimal vb = (BigDecimal) b.get("amount");
                cmp = va.compareTo(vb);
            } else {
                long va = ((Number) a.get("cnt")).longValue();
                long vb = ((Number) b.get("cnt")).longValue();
                cmp = Long.compare(va, vb);
            }
            if (desc) {
                cmp = -cmp;
            }
            if (cmp != 0) {
                return cmp;
            }
            return String.valueOf(a.get("code")).compareTo(String.valueOf(b.get("code")));
        });

        String strategy = caliber.strategy();
        List<Map<String, Object>> entries = new ArrayList<>();
        int denseRank = 0;
        Object lastMetric = null;
        for (int i = 0; i < sorted.size(); i++) {
            Map<String, Object> row = sorted.get(i);
            Object metric = byAmount ? row.get("amount") : row.get("cnt");
            boolean sameAsLast = lastMetric != null && compareMetric(metric, lastMetric) == 0;
            if (!sameAsLast) {
                denseRank++;
            }
            int rank = switch (strategy) {
                case "DENSE" -> denseRank;
                case "SEQUENTIAL" -> i + 1;
                default -> sameAsLast ? rankOf(entries.get(i - 1)) : i + 1; // COMPETITION
            };
            boolean tied = i + 1 < sorted.size()
                    && compareMetric(metric, byAmount ? sorted.get(i + 1).get("amount")
                    : sorted.get(i + 1).get("cnt")) == 0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", rank);
            entry.put("code", String.valueOf(row.get("code")));
            entry.put("name", String.valueOf(row.get("name")));
            entry.put("amount", row.get("amount"));
            entry.put("count", ((Number) row.get("cnt")).longValue());
            entry.put("tied", tied || sameAsLast);
            if ("DRUG".equals(dimension)) {
                entry.put("category", String.valueOf(row.get("eff_cat")));
            }
            entries.add(entry);
            lastMetric = metric;
        }
        return entries;
    }

    private int rankOf(Map<String, Object> previous) {
        return ((Number) previous.get("rank")).intValue();
    }

    @SuppressWarnings("unchecked")
    private int compareMetric(Object a, Object b) {
        if (a instanceof BigDecimal && b instanceof BigDecimal) {
            return ((BigDecimal) a).compareTo((BigDecimal) b);
        }
        return Long.compare(((Number) a).longValue(), ((Number) b).longValue());
    }

    /** 授权可见性过滤：ORG 维度下机构级账号只能看到授权机构行（行级剔除，无越权泄露）。 */
    public static List<Map<String, Object>> applyVisibility(List<Map<String, Object>> entries,
                                                            List<String> allowedOrgs) {
        if (allowedOrgs == null) {
            return entries; // 省级账号
        }
        return entries.stream()
                .filter(e -> allowedOrgs.contains(String.valueOf(e.get("code"))))
                .toList();
    }
}
