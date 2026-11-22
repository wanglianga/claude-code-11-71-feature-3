package com.mpi.feeverify.ranking;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 失效登记册：政策修订（口径版本更替）时，精确计算传播到的月份、
 * 药品分类、药品编码与排行分区，并驱动旧口径分区失效与新口径重建。
 *
 * 精度原则：
 * - 整类分类规则/分类剔除变化 → 仅受影响分类；局部分类调整（drugCodes 非空）→ 精确到药品；
 * - 结算状态剔除、排序或并列规则变化是跨分类的 → 快照内全部分类；
 * - 月份传播从政策生效月起，生效月之前的封账月报保留旧口径，不失效、不混口径；
 * - 排行分区是 月份×维度×机构范围，受影响药品进入任何维度的聚合，
 *   因此受影响月份的三个维度分区都登记，但登记保留分类/药品级精确范围。
 */
@Service
public class InvalidationService {

    private final JdbcTemplate jdbc;
    private final MetricCaliberService caliberService;
    private final RankingBuildService buildService;
    private final SnapshotService snapshotService;

    public InvalidationService(JdbcTemplate jdbc, MetricCaliberService caliberService,
                               RankingBuildService buildService, SnapshotService snapshotService) {
        this.jdbc = jdbc;
        this.caliberService = caliberService;
        this.buildService = buildService;
        this.snapshotService = snapshotService;
    }

    @Transactional
    public Map<String, Object> register(long baseCaliberId, long targetCaliberId,
                                        String policyEffectiveMonth, String detail) {
        Map<String, Object> base = caliberService.get(baseCaliberId);
        Map<String, Object> target = caliberService.get(targetCaliberId);
        Map<String, Object> diff = caliberService.diff(base, target);
        snapshotService.active(); // 确认当前存在 ACTIVE 快照（失效语义以当前冻结数据为基准）

        // ---- 1. 精确计算受影响分类与药品 ----
        Set<String> affectedCategories = new TreeSet<>();
        Set<String> affectedDrugCodes = new TreeSet<>();
        List<Map<String, Object>> elements = new ArrayList<>();
        collectCategoryImpact(base, target, affectedCategories, affectedDrugCodes, elements);
        collectExclusionImpact(base, target, affectedCategories, affectedDrugCodes, elements);
        boolean settleStatusChanged = Boolean.TRUE.equals(
                ((Map<?, ?>) ((Map<?, ?>) diff.get("EXCLUSION")).get("settleStatuses")).get("changed"));
        boolean sortChanged = Boolean.TRUE.equals(((Map<?, ?>) diff.get("SORT")).get("changed"));
        boolean tieChanged = Boolean.TRUE.equals(((Map<?, ?>) diff.get("TIE")).get("changed"));
        boolean crossCutting = settleStatusChanged || sortChanged || tieChanged;
        if (sortChanged) {
            elements.add(Map.of("element", "SORT", "detail", diff.get("SORT"),
                    "impact", "排序规则变化，全部维度的排行次序需重算"));
        }
        if (tieChanged) {
            elements.add(Map.of("element", "TIE", "detail", diff.get("TIE"),
                    "impact", "并列规则变化，全部维度的名次需重算且须经发布审批"));
        }
        if (settleStatusChanged) {
            elements.add(Map.of("element", "EXCLUSION_SETTLE_STATUS",
                    "detail", ((Map<?, ?>) diff.get("EXCLUSION")).get("settleStatuses"),
                    "impact", "结算状态剔除变化，跨全部分类"));
        }
        if (crossCutting || affectedCategories.isEmpty()) {
            affectedCategories.addAll(allSnapshotCategories());
        }

        // ---- 2. 传播月份：生效月起、旧口径构建实际覆盖的月份 ----
        List<String> coveredMonths = jdbc.queryForList(
                "SELECT DISTINCT month FROM ranking_partition WHERE caliber_id = ? ORDER BY 1",
                String.class, baseCaliberId);
        List<String> affectedMonths = coveredMonths.stream()
                .filter(m -> m.compareTo(policyEffectiveMonth) >= 0).toList();
        if (affectedMonths.isEmpty()) {
            // 旧口径尚无分区：登记仍保留分类级影响面，无分区可失效
            affectedMonths = List.of();
        }

        // ---- 3. 命中分区：受影响月份内旧口径的全部维度/机构范围分区 ----
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT * FROM ranking_partition WHERE caliber_id = ? AND month >= ?"
                        + " ORDER BY month, dimension, org_scope",
                baseCaliberId, policyEffectiveMonth);
        List<Map<String, Object>> affectedPartitions = new ArrayList<>();
        Set<Long> touchedBuilds = new LinkedHashSet<>();
        for (Map<String, Object> p : candidates) {
            long partitionId = ((Number) p.get("id")).longValue();
            long buildId = ((Number) p.get("build_id")).longValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("buildId", buildId);
            item.put("partitionId", partitionId);
            item.put("month", p.get("month"));
            item.put("dimension", p.get("dimension"));
            item.put("orgScope", p.get("org_scope"));
            item.put("previousStatus", p.get("status"));
            affectedPartitions.add(item);
            touchedBuilds.add(buildId);
        }

        Long registerId = jdbc.queryForObject(
                "INSERT INTO caliber_invalidation_register (base_caliber_id, target_caliber_id,"
                        + " policy_effective_month, affected_categories, affected_drug_codes,"
                        + " affected_elements, affected_months, affected_partitions, status, detail)"
                        + " VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,'OPEN',?)"
                        + " RETURNING id",
                Long.class, baseCaliberId, targetCaliberId, policyEffectiveMonth,
                Jsons.write(affectedCategories), Jsons.write(affectedDrugCodes),
                Jsons.write(elements), Jsons.write(affectedMonths),
                Jsons.write(affectedPartitions), detail);

        // ---- 4. 旧口径分区精确失效（生效月之前的封账月报保持 READY）----
        int invalidated = jdbc.update(
                "UPDATE ranking_partition SET status = 'INVALIDATED', invalidation_id = ?"
                        + " WHERE caliber_id = ? AND month >= ? AND status <> 'INVALIDATED'",
                registerId, baseCaliberId, policyEffectiveMonth);
        for (Long buildId : touchedBuilds) {
            buildService.refreshBuildStatus(buildId);
        }

        Map<String, Object> result = get(registerId);
        result.put("invalidatedPartitionCount", invalidated);
        result.put("touchedBuilds", touchedBuilds);
        result.put("sealedMonthsKept", coveredMonths.stream()
                .filter(m -> m.compareTo(policyEffectiveMonth) < 0).toList());
        return result;
    }

    private void collectCategoryImpact(Map<String, Object> base, Map<String, Object> target,
                                       Set<String> categories, Set<String> drugCodes,
                                       List<Map<String, Object>> elements) {
        List<Map<String, Object>> baseRules = Jsons.asListOfMaps(base.get("category_rules"));
        List<Map<String, Object>> targetRules = Jsons.asListOfMaps(target.get("category_rules"));
        List<Map<String, Object>> added = new ArrayList<>(targetRules);
        added.removeAll(baseRules);
        List<Map<String, Object>> removed = new ArrayList<>(baseRules);
        removed.removeAll(targetRules);
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }
        for (Map<String, Object> rule : added) {
            categories.add(String.valueOf(rule.get("from")));
            categories.add(String.valueOf(rule.get("to")));
            List<Object> codes = Jsons.asList(rule.get("drugCodes"));
            if (!codes.isEmpty()) {
                codes.forEach(c -> drugCodes.add(String.valueOf(c)));
                categories.addAll(categoriesOfDrugs(codes));
            }
        }
        for (Map<String, Object> rule : removed) {
            categories.add(String.valueOf(rule.get("from")));
            categories.add(String.valueOf(rule.get("to")));
            List<Object> codes = Jsons.asList(rule.get("drugCodes"));
            if (!codes.isEmpty()) {
                codes.forEach(c -> drugCodes.add(String.valueOf(c)));
            }
        }
        elements.add(Map.of("element", "CATEGORY",
                "added", added, "removed", removed,
                "scope", drugCodes.isEmpty() ? "CATEGORY_LEVEL" : "DRUG_LEVEL_LOCAL_ADJUSTMENT"));
    }

    @SuppressWarnings("unchecked")
    private void collectExclusionImpact(Map<String, Object> base, Map<String, Object> target,
                                        Set<String> categories, Set<String> drugCodes,
                                        List<Map<String, Object>> elements) {
        Map<String, Object> bx = Jsons.asMap(base.get("exclusion_rules"));
        Map<String, Object> tx = Jsons.asMap(target.get("exclusion_rules"));
        Map<String, Object> detail = new LinkedHashMap<>();
        boolean changed = false;
        for (String key : List.of("settleStatuses", "drugCategories", "drugCodes")) {
            TreeSet<String> b = new TreeSet<>(Jsons.asList(bx.get(key)).stream().map(String::valueOf).toList());
            TreeSet<String> t = new TreeSet<>(Jsons.asList(tx.get(key)).stream().map(String::valueOf).toList());
            List<String> added = new ArrayList<>(t);
            added.removeAll(b);
            List<String> removed = new ArrayList<>(b);
            removed.removeAll(t);
            if (!added.isEmpty() || !removed.isEmpty()) {
                changed = true;
                detail.put(key, Map.of("added", added, "removed", removed));
            }
            if ("drugCategories".equals(key)) {
                categories.addAll(added);
                categories.addAll(removed);
            }
            if ("drugCodes".equals(key)) {
                drugCodes.addAll(added);
                drugCodes.addAll(removed);
                categories.addAll(categoriesOfDrugs(new ArrayList<>(drugCodes)));
            }
        }
        if (changed) {
            boolean settleChanged = detail.containsKey("settleStatuses");
            elements.add(Map.of("element", "EXCLUSION", "detail", detail,
                    "impact", settleChanged
                            ? "结算状态剔除变化，跨全部分类" : "分类/药品级精确剔除变化"));
        }
    }

    private List<String> categoriesOfDrugs(List<Object> drugCodes) {
        if (drugCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT DISTINCT category FROM snap_dim_drug WHERE drug_code IN ("
                        + drugCodes.stream().map(c -> "?").reduce((a, b) -> a + "," + b).orElse("?") + ")",
                String.class, drugCodes.stream().map(String::valueOf).toArray());
    }

    private List<String> allSnapshotCategories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM snap_dim_drug ORDER BY 1", String.class);
    }

    /**
     * 按登记册重建：以新口径对受影响月份/维度/机构范围发起构建，
     * 完成后按分区覆盖情况把登记册置 REBUILT / PARTIAL_REBUILT。
     */
    @Transactional
    public Map<String, Object> rebuild(long registerId, String requestedBy) {
        Map<String, Object> register = get(registerId);
        if (!"OPEN".equals(register.get("status")) && !"PARTIAL_REBUILT".equals(register.get("status"))) {
            throw ApiException.conflict("登记册 " + registerId + " 状态=" + register.get("status")
                    + "，无需重建");
        }
        List<String> months = Jsons.asList(register.get("affected_months")).stream().map(String::valueOf).toList();
        if (months.isEmpty()) {
            throw ApiException.conflict("登记册 " + registerId + " 无受影响月份分区，无需重建");
        }
        List<Map<String, Object>> partitions = Jsons.asListOfMaps(register.get("affected_partitions"));
        List<Object> dimensions = partitions.stream().map(p -> p.get("dimension")).distinct().sorted().toList();
        List<Object> orgScopes = partitions.stream().map(p -> p.get("orgScope")).distinct().sorted().toList();
        long targetCaliberId = ((Number) register.get("target_caliber_id")).longValue();

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("monthFrom", months.get(0));
        spec.put("monthTo", months.get(months.size() - 1));
        spec.put("dimensions", dimensions);
        spec.put("orgScopes", orgScopes);
        Map<String, Object> build = buildService.requestBuild(targetCaliberId, spec, "HIGH", List.of(), requestedBy);
        long buildId = ((Number) build.get("id")).longValue();

        // 重建是 月份×维度×机构范围 的矩形构建，受影响组合的就绪分区数应覆盖登记册中的每个组合；
        // 用“受影响组合是否全部就绪”判定，避免矩形额外分区导致的计数误判。
        long expectedCombos = partitions.stream()
                .map(p -> p.get("month") + "|" + p.get("dimension") + "|" + p.get("orgScope"))
                .distinct().count();
        Long readyCombos = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT month || '|' || dimension || '|' || org_scope)"
                        + " FROM ranking_partition WHERE build_id = ? AND status = 'READY'",
                Long.class, buildId);
        String newStatus = readyCombos != null && readyCombos >= expectedCombos
                ? "REBUILT" : "PARTIAL_REBUILT";
        jdbc.update("UPDATE caliber_invalidation_register SET status = ?, rebuilt_at = now() WHERE id = ?",
                newStatus, registerId);

        Map<String, Object> result = get(registerId);
        result.put("rebuildBuildId", buildId);
        result.put("rebuildReadyCombos", readyCombos);
        result.put("expectedCombos", expectedCombos);
        return result;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM caliber_invalidation_register WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("失效登记不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list(String status) {
        if (status == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT id, base_caliber_id, target_caliber_id, policy_effective_month,"
                            + " affected_categories, affected_drug_codes, affected_months, status,"
                            + " created_at, rebuilt_at FROM caliber_invalidation_register ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, base_caliber_id, target_caliber_id, policy_effective_month,"
                        + " affected_categories, affected_drug_codes, affected_months, status,"
                        + " created_at, rebuilt_at FROM caliber_invalidation_register WHERE status = ?"
                + " ORDER BY id DESC", status));
    }
}
