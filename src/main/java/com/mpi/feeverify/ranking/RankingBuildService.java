package com.mpi.feeverify.ranking;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行榜构建器：一次构建绑定一个口径版本与一个冻结快照，
 * 在 snap_* 表上按口径扫描明细，产出 月份×维度×机构范围 的排行分区。
 * 记录输入快照、构建进度（PENDING/RUNNING/READY/PARTIAL_READY/FAILED/INVALIDATED）
 * 与可用范围；预计算结果与口径和快照双重绑定，口径变更后由失效登记册精确失效。
 *
 * 支持模拟构建延迟：holdMonths 指定的月份分区保持 BUILDING，
 * 直到 advance() 推进，用于演示“构建未完成时高优先级请求的可解释等待”。
 */
@Service
public class RankingBuildService {

    private final JdbcTemplate jdbc;
    private final SnapshotService snapshotService;
    private final MetricCaliberService caliberService;
    private final RankingCalculator calculator;

    public RankingBuildService(JdbcTemplate jdbc, SnapshotService snapshotService,
                               MetricCaliberService caliberService, RankingCalculator calculator) {
        this.jdbc = jdbc;
        this.snapshotService = snapshotService;
        this.caliberService = caliberService;
        this.calculator = calculator;
    }

    /**
     * 登记并执行构建。
     *
     * @param spec       {monthFrom, monthTo, dimensions:[ORG,DEPT,DRUG], orgScopes:["*"], topN}
     * @param priority   NORMAL / HIGH
     * @param holdMonths 模拟延迟：这些月份的分区停留 BUILDING，等待 advance
     */
    @Transactional
    public Map<String, Object> requestBuild(long caliberId, Map<String, Object> spec, String priority,
                                            List<String> holdMonths, String requestedBy) {
        Map<String, Object> caliber = caliberService.get(caliberId);
        Map<String, Object> snapshot = snapshotService.active();
        long snapshotId = ((Number) snapshot.get("id")).longValue();
        Map<String, Object> normalizedSpec = normalizeSpec(spec);

        Long buildId = jdbc.queryForObject(
                "INSERT INTO ranking_build (caliber_id, snapshot_id, input_snapshot, build_spec,"
                        + " status, priority, progress_percent, progress_detail, requested_by, started_at)"
                        + " VALUES (?,?,?::jsonb,?::jsonb,'RUNNING',?,0,'[]'::jsonb,?,now()) RETURNING id",
                Long.class, caliberId, snapshotId,
                Jsons.write(Map.of("snapshotId", snapshotId,
                        "checksum", snapshot.get("checksum"),
                        "rowCount", snapshot.get("row_count"),
                        "frozenAt", snapshot.get("frozen_at"))),
                Jsons.write(normalizedSpec),
                priority == null ? "NORMAL" : priority, requestedBy);

        List<String> months = monthsBetween(
                String.valueOf(normalizedSpec.get("monthFrom")),
                String.valueOf(normalizedSpec.get("monthTo")));
        List<String> holds = holdMonths == null ? List.of() : holdMonths;
        for (String month : months) {
            for (String dimension : Jsons.asList(normalizedSpec.get("dimensions")).stream().map(String::valueOf).toList()) {
                for (String orgScope : Jsons.asList(normalizedSpec.get("orgScopes")).stream().map(String::valueOf).toList()) {
                    Long partitionId = jdbc.queryForObject(
                            "INSERT INTO ranking_partition (build_id, caliber_id, snapshot_id, month,"
                                    + " dimension, org_scope, status, caliber_digest)"
                                    + " VALUES (?,?,?,?,?,?,'BUILDING',?::jsonb) RETURNING id",
                            Long.class, buildId, caliberId, snapshotId, month, dimension, orgScope,
                            Jsons.write(caliberDigest(caliber)));
                    if (!holds.contains(month)) {
                        buildPartition(partitionId, caliber, month, dimension, orgScope);
                    }
                }
            }
        }
        refreshBuildStatus(buildId);
        return get(buildId);
    }

    /** 推进被延迟（BUILDING）的分区，模拟构建完成。 */
    @Transactional
    public Map<String, Object> advance(long buildId) {
        Map<String, Object> build = get(buildId);
        if (!List.of("RUNNING", "PARTIAL_READY").contains(build.get("status"))) {
            throw ApiException.conflict("构建 " + buildId + " 当前状态=" + build.get("status")
                    + "，没有可推进的延迟分区");
        }
        Map<String, Object> caliber = caliberService.get(((Number) build.get("caliber_id")).longValue());
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT * FROM ranking_partition WHERE build_id = ? AND status = 'BUILDING' ORDER BY id",
                buildId);
        for (Map<String, Object> partition : pending) {
            buildPartition(((Number) partition.get("id")).longValue(), caliber,
                    String.valueOf(partition.get("month")),
                    String.valueOf(partition.get("dimension")),
                    String.valueOf(partition.get("org_scope")));
        }
        refreshBuildStatus(buildId);
        return get(buildId);
    }

    private void buildPartition(long partitionId, Map<String, Object> caliber, String month,
                                String dimension, String orgScope) {
        try {
            RankingCalculator.Caliber c = new RankingCalculator.Caliber(
                    ((Number) caliber.get("id")).longValue(),
                    ((Number) caliber.get("version")).intValue(),
                    Jsons.asListOfMaps(caliber.get("category_rules")),
                    Jsons.asMap(caliber.get("exclusion_rules")),
                    Jsons.asMap(caliber.get("sort_rules")),
                    Jsons.asMap(caliber.get("tie_rules")));
            List<Map<String, Object>> entries = calculator.rank(c, month, dimension, orgScope, null);
            java.math.BigDecimal total = sumAmount(entries);
            jdbc.update("UPDATE ranking_partition SET status='READY', entries=?::jsonb, row_count=?,"
                            + " total_amount=?, built_at=now() WHERE id=?",
                    Jsons.write(entries), entries.size(), total, partitionId);
        } catch (Exception e) {
            jdbc.update("UPDATE ranking_partition SET status='FAILED' WHERE id=?", partitionId);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "分区 " + partitionId + " 构建失败: " + e.getMessage());
        }
    }

    /** 根据分区实际状态刷新构建进度、状态与可用范围。 */
    void refreshBuildStatus(long buildId) {
        List<Map<String, Object>> partitions = jdbc.queryForList(
                "SELECT id, month, dimension, org_scope, status FROM ranking_partition"
                        + " WHERE build_id = ? ORDER BY id", buildId);
        int ready = 0, building = 0, failed = 0, invalidated = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        List<String> readyMonths = new ArrayList<>();
        List<String> readyDimensions = new ArrayList<>();
        List<String> readyScopes = new ArrayList<>();
        for (Map<String, Object> p : partitions) {
            String status = String.valueOf(p.get("status"));
            switch (status) {
                case "READY" -> {
                    ready++;
                    readyMonths.add(String.valueOf(p.get("month")));
                    readyDimensions.add(String.valueOf(p.get("dimension")));
                    readyScopes.add(String.valueOf(p.get("org_scope")));
                }
                case "BUILDING" -> building++;
                case "FAILED" -> failed++;
                case "INVALIDATED" -> invalidated++;
                default -> { }
            }
            detail.add(Map.of("partitionId", p.get("id"), "month", p.get("month"),
                    "dimension", p.get("dimension"), "orgScope", p.get("org_scope"),
                    "status", status));
        }
        int percent = partitions.isEmpty() ? 0 : (ready * 100 / partitions.size());
        String status;
        String reason = null;
        if (building > 0) {
            status = "RUNNING";
            reason = building + " 个分区构建中（" + ready + "/" + partitions.size() + " 已就绪）";
        } else if (failed > 0 && ready == 0) {
            status = "FAILED";
            reason = failed + " 个分区构建失败";
        } else if (invalidated == partitions.size()) {
            status = "INVALIDATED";
            reason = "全部分区被失效登记册命中，需按新口径重建";
        } else if (failed > 0 || invalidated > 0) {
            status = "PARTIAL_READY";
            reason = "就绪 " + ready + "，失败 " + failed + "，失效 " + invalidated;
        } else {
            status = "READY";
        }
        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("readyCount", ready);
        availability.put("totalCount", partitions.size());
        availability.put("months", readyMonths.stream().distinct().sorted().toList());
        availability.put("dimensions", readyDimensions.stream().distinct().sorted().toList());
        availability.put("orgScopes", readyScopes.stream().distinct().sorted().toList());

        jdbc.update("UPDATE ranking_build SET status=?, progress_percent=?, progress_detail=?::jsonb,"
                        + " availability=?::jsonb, status_reason=?, finished_at=CASE WHEN ? IN ('READY','FAILED','INVALIDATED')"
                        + " THEN now() ELSE finished_at END WHERE id=?",
                status, percent, Jsons.write(detail), Jsons.write(availability), reason, status, buildId);
    }

    public Map<String, Object> get(long buildId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ranking_build WHERE id = ?", buildId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("排行构建不存在: " + buildId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> list(Long caliberId) {
        if (caliberId == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT id, caliber_id, snapshot_id, status, priority, progress_percent,"
                            + " availability, status_reason, created_at FROM ranking_build ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, caliber_id, snapshot_id, status, priority, progress_percent,"
                        + " availability, status_reason, created_at FROM ranking_build"
                + " WHERE caliber_id = ? ORDER BY id DESC", caliberId));
    }

    public List<Map<String, Object>> partitions(long buildId) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, build_id, month, dimension, org_scope, status, invalidation_id, row_count,"
                        + " total_amount, built_at FROM ranking_partition WHERE build_id = ? ORDER BY id",
                buildId));
    }

    public Map<String, Object> partition(long partitionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ranking_partition WHERE id = ?", partitionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("排行分区不存在: " + partitionId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    /** 查找某口径+快照下指定键的分区（任意状态），供路由与失效登记判断。 */
    public List<Map<String, Object>> findPartitions(long caliberId, long snapshotId, String month,
                                                    String dimension, String orgScope) {
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT p.* FROM ranking_partition p JOIN ranking_build b ON b.id = p.build_id"
                        + " WHERE p.caliber_id = ? AND p.snapshot_id = ? AND p.month = ?"
                        + " AND p.dimension = ? AND p.org_scope = ? ORDER BY p.id DESC",
                caliberId, snapshotId, month, dimension, orgScope));
    }

    /** 取就绪分区条目（路由预计算路径）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readyEntries(long partitionId) {
        Map<String, Object> partition = partition(partitionId);
        if (!"READY".equals(partition.get("status"))) {
            throw ApiException.conflict("分区 " + partitionId + " 状态=" + partition.get("status")
                    + "，无就绪条目");
        }
        Object entries = partition.get("entries");
        if (entries instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Jsons.readListOfMaps(String.valueOf(entries));
    }

    private Map<String, Object> normalizeSpec(Map<String, Object> raw) {
        if (raw == null || raw.get("monthFrom") == null || raw.get("monthTo") == null) {
            throw ApiException.badRequest("buildSpec 必须含 monthFrom/monthTo（YYYY-MM）");
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("monthFrom", raw.get("monthFrom"));
        spec.put("monthTo", raw.get("monthTo"));
        List<Object> dimensions = Jsons.asList(raw.get("dimensions"));
        spec.put("dimensions", dimensions.isEmpty() ? List.of("ORG", "DEPT", "DRUG") : dimensions);
        List<Object> orgScopes = Jsons.asList(raw.get("orgScopes"));
        spec.put("orgScopes", orgScopes.isEmpty() ? List.of("*") : orgScopes);
        spec.put("topN", raw.getOrDefault("topN", 10));
        return spec;
    }

    static List<String> monthsBetween(String from, String to) {
        YearMonth start = YearMonth.parse(from);
        YearMonth end = YearMonth.parse(to);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("monthFrom 不能晚于 monthTo");
        }
        List<String> months = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            months.add(m.toString());
        }
        return months;
    }

    private Map<String, Object> caliberDigest(Map<String, Object> caliber) {
        return Map.of("caliberId", caliber.get("id"), "version", caliber.get("version"),
                "categoryRules", Jsons.asListOfMaps(caliber.get("category_rules")),
                "exclusionRules", Jsons.asMap(caliber.get("exclusion_rules")),
                "sortRules", Jsons.asMap(caliber.get("sort_rules")),
                "tieRules", Jsons.asMap(caliber.get("tie_rules")));
    }

    private java.math.BigDecimal sumAmount(List<Map<String, Object>> entries) {
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (Map<String, Object> e : entries) {
            Object v = e.get("amount");
            if (v instanceof java.math.BigDecimal b) {
                sum = sum.add(b);
            } else if (v instanceof Number n) {
                sum = sum.add(new java.math.BigDecimal(String.valueOf(n)));
            }
        }
        return sum;
    }
}
