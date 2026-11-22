package com.mpi.feeverify.metric;

import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 指标口径版本库：排行口径的分类、剔除、排序、并列四类规则整体版本化。
 * 新版本先 DRAFT，经影子排行比较与发布审批后才能 ACTIVE；
 * 同族同时只有一个 ACTIVE，生效时旧版本 RETIRED。
 * 任意两个版本可做四要素差异计算，供比较器、失效登记册与发布审批引用。
 */
@Service
public class MetricCaliberService {

    public static final String FAMILY = "fee-ranking";

    private final JdbcTemplate jdbc;

    public MetricCaliberService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createDraft(Map<String, Object> body) {
        String family = String.valueOf(body.getOrDefault("family", FAMILY));
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM metric_caliber_version WHERE family = ?",
                Integer.class, family);
        List<Map<String, Object>> categoryRules = Jsons.asListOfMaps(body.get("categoryRules"));
        Map<String, Object> exclusionRules = asMap(body.get("exclusionRules"), defaultExclusion());
        Map<String, Object> sortRules = asMap(body.get("sortRules"),
                Map.of("metric", "AMOUNT", "direction", "DESC"));
        Map<String, Object> tieRules = asMap(body.get("tieRules"),
                Map.of("strategy", "COMPETITION", "tieBreaker", "CODE_ASC"));
        Map<String, Object> scopeRules = asMap(body.get("scopeRules"),
                Map.of("dimensions", List.of("ORG", "DEPT", "DRUG"),
                        "authGranularities", List.of("PROVINCE", "ORG")));
        validateRules(categoryRules, exclusionRules, sortRules, tieRules);

        Long id = jdbc.queryForObject(
                "INSERT INTO metric_caliber_version (family, version, category_rules, exclusion_rules,"
                        + " sort_rules, tie_rules, scope_rules, status, change_note, created_by)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'DRAFT',?,?)"
                        + " RETURNING id",
                Long.class, family, version,
                Jsons.write(categoryRules),
                Jsons.write(exclusionRules), Jsons.write(sortRules), Jsons.write(tieRules),
                Jsons.write(scopeRules), body.get("changeNote"), body.get("createdBy"));
        return get(id);
    }

    private void validateRules(List<Map<String, Object>> categoryRules, Map<String, Object> exclusionRules,
                               Map<String, Object> sortRules, Map<String, Object> tieRules) {
        for (Map<String, Object> rule : categoryRules) {
            if (rule.get("from") == null || rule.get("to") == null) {
                throw ApiException.badRequest("分类规则每条必须含 from/to，drugCodes 可省略（空=整类映射）");
            }
        }
        String strategy = String.valueOf(tieRules.getOrDefault("strategy", "COMPETITION"));
        if (!List.of("COMPETITION", "DENSE", "SEQUENTIAL").contains(strategy)) {
            throw ApiException.badRequest("并列规则 strategy 仅支持 COMPETITION/DENSE/SEQUENTIAL");
        }
        String metric = String.valueOf(sortRules.getOrDefault("metric", "AMOUNT"));
        if (!List.of("AMOUNT", "COUNT").contains(metric)) {
            throw ApiException.badRequest("排序指标 metric 仅支持 AMOUNT/COUNT");
        }
        String direction = String.valueOf(sortRules.getOrDefault("direction", "DESC"));
        if (!List.of("DESC", "ASC").contains(direction)) {
            throw ApiException.badRequest("排序方向 direction 仅支持 DESC/ASC");
        }
        for (String key : List.of("settleStatuses", "drugCategories", "drugCodes")) {
            Object v = exclusionRules.get(key);
            if (v != null && !(v instanceof List)) {
                throw ApiException.badRequest("剔除规则 " + key + " 必须是数组");
            }
        }
    }

    private Map<String, Object> defaultExclusion() {
        return Map.of("settleStatuses", List.of("REJECTED"),
                "drugCategories", List.of(), "drugCodes", List.of());
    }

    private Map<String, Object> asMap(Object v, Map<String, Object> fallback) {
        if (v == null) {
            return new LinkedHashMap<>(fallback);
        }
        return Jsons.asMap(v);
    }

    /** 生效口径：同族旧 ACTIVE 退役。返回新版本与上一生效版本的四要素差异。 */
    @Transactional
    public Map<String, Object> activate(long id) {
        Map<String, Object> target = get(id);
        if (!"DRAFT".equals(target.get("status"))) {
            throw ApiException.conflict("仅 DRAFT 口径可生效，当前=" + target.get("status"));
        }
        String family = String.valueOf(target.get("family"));
        List<Map<String, Object>> previous = jdbc.queryForList(
                "SELECT * FROM metric_caliber_version WHERE family = ? AND status = 'ACTIVE'"
                        + " ORDER BY version DESC", family);
        jdbc.update("UPDATE metric_caliber_version SET status = 'RETIRED'"
                + " WHERE family = ? AND status = 'ACTIVE'", family);
        jdbc.update("UPDATE metric_caliber_version SET status = 'ACTIVE', activated_at = now()"
                + " WHERE id = ?", id);

        Map<String, Object> result = new LinkedHashMap<>(get(id));
        if (!previous.isEmpty()) {
            result.put("diffAgainst", previous.get(0).get("version"));
            result.put("diff", diff(previous.get(0), target));
        }
        return result;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM metric_caliber_version WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("口径版本不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public Map<String, Object> requireActive(long id) {
        Map<String, Object> caliber = get(id);
        if (!"ACTIVE".equals(caliber.get("status"))) {
            throw ApiException.conflict("口径 " + id + "（v" + caliber.get("version")
                    + "）状态=" + caliber.get("status") + "，非 ACTIVE 不能用于排行服务");
        }
        return caliber;
    }

    public Map<String, Object> active(String family) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM metric_caliber_version WHERE family = ? AND status = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1", family);
        if (rows.isEmpty()) {
            throw ApiException.conflict("口径族 " + family + " 没有 ACTIVE 版本");
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list(String family) {
        String f = family == null ? FAMILY : family;
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, family, version, status, change_note, created_at, activated_at"
                        + " FROM metric_caliber_version WHERE family = ? ORDER BY version", f));
    }

    /**
     * 两个口径版本的四要素差异：CATEGORY / EXCLUSION / SORT / TIE。
     * 每项带 changed 与明细；并列规则差异单独打 tieRuleDiffers 标志。
     */
    public Map<String, Object> diff(long baseId, long targetId) {
        return diff(get(baseId), get(targetId));
    }

    public Map<String, Object> diff(Map<String, Object> base, Map<String, Object> target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseVersion", base.get("version"));
        out.put("targetVersion", target.get("version"));

        List<String> baseCat = normalizeCategory(Jsons.asListOfMaps(base.get("category_rules")));
        List<String> targetCat = normalizeCategory(Jsons.asListOfMaps(target.get("category_rules")));
        out.put("CATEGORY", elementDiff(baseCat, targetCat));

        Map<String, Object> baseEx = Jsons.asMap(base.get("exclusion_rules"));
        Map<String, Object> targetEx = Jsons.asMap(target.get("exclusion_rules"));
        Map<String, Object> exclusionDiff = new LinkedHashMap<>();
        boolean exChanged = false;
        for (String key : List.of("settleStatuses", "drugCategories", "drugCodes")) {
            TreeSet<String> b = new TreeSet<>(strSet(baseEx.get(key)));
            TreeSet<String> t = new TreeSet<>(strSet(targetEx.get(key)));
            List<String> added = new ArrayList<>(t);
            added.removeAll(b);
            List<String> removed = new ArrayList<>(b);
            removed.removeAll(t);
            boolean changed = !added.isEmpty() || !removed.isEmpty();
            exChanged |= changed;
            exclusionDiff.put(key, Map.of("changed", changed, "added", added, "removed", removed));
        }
        exclusionDiff.put("changed", exChanged);
        out.put("EXCLUSION", exclusionDiff);

        Map<String, Object> baseSort = Jsons.asMap(base.get("sort_rules"));
        Map<String, Object> targetSort = Jsons.asMap(target.get("sort_rules"));
        boolean sortChanged = !baseSort.equals(targetSort);
        out.put("SORT", Map.of("changed", sortChanged,
                "from", baseSort, "to", targetSort));

        Map<String, Object> baseTie = Jsons.asMap(base.get("tie_rules"));
        Map<String, Object> targetTie = Jsons.asMap(target.get("tie_rules"));
        boolean tieChanged = !baseTie.equals(targetTie);
        out.put("TIE", Map.of("changed", tieChanged, "from", baseTie, "to", targetTie));
        out.put("tieRuleDiffers", tieChanged);
        out.put("anyChanged",
                (Boolean) ((Map<?, ?>) out.get("CATEGORY")).get("changed") || exChanged
                        || sortChanged || tieChanged);
        return out;
    }

    private Map<String, Object> elementDiff(List<String> base, List<String> target) {
        TreeSet<String> b = new TreeSet<>(base);
        TreeSet<String> t = new TreeSet<>(target);
        List<String> added = new ArrayList<>(t);
        added.removeAll(b);
        List<String> removed = new ArrayList<>(b);
        removed.removeAll(t);
        return Map.of("changed", !added.isEmpty() || !removed.isEmpty(),
                "added", added, "removed", removed);
    }

    /** 分类规则规范化为可比较字符串：整类映射与单药品映射分别编码。 */
    private List<String> normalizeCategory(List<Map<String, Object>> rules) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            List<String> codes = Jsons.asList(rule.get("drugCodes")).stream()
                    .map(String::valueOf).sorted().toList();
            out.add(rule.get("from") + "->" + rule.get("to") + "|" + String.join(",", codes));
        }
        return out;
    }

    private List<String> strSet(Object v) {
        if (v == null) {
            return List.of();
        }
        return Jsons.asList(v).stream().map(String::valueOf).toList();
    }
}
