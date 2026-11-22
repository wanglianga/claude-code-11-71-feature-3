package com.mpi.feeverify.ranking;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.snapshot.SnapshotService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.UserDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行查询路由决策器：按请求的口径版本、月份、授权与时效目标，
 * 结合请求频率、可接受等待、口径变更影响面与授权过滤粒度选择执行路径：
 *
 * - PRECOMPUTED       命中与口径+快照绑定的就绪分区，高频审计请求限制在已验证结果中；
 * - ON_DEMAND_SCAN    查询时扫描明细：无需等待构建、可立即响应临时筛选，深分页持续占用计算资源；
 * - WAITING           高优先级构建未完成：返回可解释等待状态，绝不交付旧口径；
 * - FALLBACK_VERIFIED 精确分区不可用时，改用同口径同快照下经验证的另一条路径（如全省分区+授权过滤）；
 * - REJECTED          口径非生效或授权拒绝。
 *
 * 每次决策落库 ranking_route_decision，逐条记录决策依据，口径/构建/分区/授权相互引用。
 */
@Service
public class RankingRouterService {

    private final JdbcTemplate jdbc;
    private final UserDirectory users;
    private final MetricCaliberService caliberService;
    private final RankingBuildService buildService;
    private final RankingCalculator calculator;
    private final SnapshotService snapshotService;

    @Value("${verify.ranking.hot-frequency-threshold:3}")
    private int hotThreshold;

    public RankingRouterService(JdbcTemplate jdbc, UserDirectory users,
                                MetricCaliberService caliberService, RankingBuildService buildService,
                                RankingCalculator calculator, SnapshotService snapshotService) {
        this.jdbc = jdbc;
        this.users = users;
        this.caliberService = caliberService;
        this.buildService = buildService;
        this.calculator = calculator;
        this.snapshotService = snapshotService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> route(Map<String, Object> body) {
        String token = (String) body.get("token");
        Map<String, Object> user = users.authenticate(token);
        long snapshotId = ((Number) snapshotService.active().get("id")).longValue();

        Long caliberId = body.get("caliberId") == null
                ? ((Number) caliberService.active(MetricCaliberService.FAMILY).get("id")).longValue()
                : ((Number) body.get("caliberId")).longValue();
        Map<String, Object> caliberRow = caliberService.requireActive(caliberId);
        RankingCalculator.Caliber caliber = RankingCalculator.Caliber.of(caliberRow);

        String month = String.valueOf(body.getOrDefault("month", body.get("monthFrom")));
        if (body.get("month") == null && body.get("monthFrom") == null) {
            throw ApiException.badRequest("month 必填（YYYY-MM，封账月度排行按月请求）");
        }
        String dimension = String.valueOf(body.getOrDefault("dimension", "DRUG"));
        int topN = body.get("topN") == null ? 10 : ((Number) body.get("topN")).intValue();
        int page = body.get("page") == null ? 1 : ((Number) body.get("page")).intValue();
        int pageSize = body.get("pageSize") == null ? 50 : ((Number) body.get("pageSize")).intValue();
        String latencyTarget = String.valueOf(body.getOrDefault("latencyTarget", "NORMAL")); // FAST/NORMAL
        String waitStrategy = String.valueOf(body.getOrDefault("waitStrategy", "AUTO"));      // WAIT/SCAN/AUTO
        Map<String, Object> adhoc = Jsons.asMap(body.get("adhocFilters"));
        if (adhoc == null) {
            adhoc = Map.of();
        }
        List<String> allowedOrgs = users.allowedOrgs(user, MetricCaliberService.FAMILY);
        String orgScope = allowedOrgs == null ? "*" : String.valueOf(user.get("org_code"));

        Map<String, Object> requestSpec = new LinkedHashMap<>();
        requestSpec.put("month", month);
        requestSpec.put("dimension", dimension);
        requestSpec.put("orgScope", orgScope);
        requestSpec.put("topN", topN);
        requestSpec.put("page", page);
        requestSpec.put("pageSize", pageSize);
        requestSpec.put("latencyTarget", latencyTarget);
        requestSpec.put("snapshotId", snapshotId);

        List<String> reasons = new ArrayList<>();
        long frequency = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ranking_route_decision WHERE caliber_id = ?"
                        + " AND request_spec->>'month' = ? AND request_spec->>'dimension' = ?",
                Long.class, caliberId, month, dimension);
        boolean hot = frequency + 1 >= hotThreshold;
        reasons.add("请求频率=" + (frequency + 1) + (hot ? "（≥阈值 " + hotThreshold + "，高频审计请求，优先预计算）"
                : "（低频，可接受查询时扫描）"));
        reasons.add("时效目标=" + latencyTarget + "，可接受等待策略=" + waitStrategy);
        reasons.add("授权过滤粒度=" + (allowedOrgs == null ? "PROVINCE（全省可见）"
                : "ORG（仅可见 " + allowedOrgs + "）"));
        if (page > 1) {
            reasons.add("深分页 page=" + page + "：查询时扫描路径将持续占用计算资源，预计算路径无此代价");
        }

        // ---------- 路径选择 ----------
        String routedTo;
        Long buildId = null;
        Long partitionId = null;
        List<Map<String, Object>> entries;
        long elapsedMs = 0;
        Map<String, Object> waitingInfo = null;

        List<Map<String, Object>> exact = buildService.findPartitions(
                caliberId, snapshotId, month, dimension, orgScope);
        List<Map<String, Object>> province = "*".equals(orgScope) ? List.of()
                : buildService.findPartitions(caliberId, snapshotId, month, dimension, "*");
        Map<String, Object> exactLatest = exact.isEmpty() ? null : exact.get(0);
        Map<String, Object> provinceLatest = province.isEmpty() ? null : province.get(0);

        if (!adhoc.isEmpty()) {
            // 临时筛选：预计算结果无法直接复用，只能查询时扫描
            routedTo = "ON_DEMAND_SCAN";
            reasons.add("ADHOC_FILTER: 携带临时筛选 " + adhoc.keySet() + "，预计算分区不可直接复用，"
                    + "改用查询时扫描（无需等待构建，立即响应）");
            long start = System.currentTimeMillis();
            entries = calculator.rank(caliber, month, dimension, orgScope, null, adhoc);
            elapsedMs = System.currentTimeMillis() - start;
            reasons.add("扫描实际耗时=" + elapsedMs + "ms（同口径同快照语义，结果可与预计算逐位比较）");
        } else if (isReady(exactLatest)) {
            routedTo = "PRECOMPUTED";
            partitionId = ((Number) exactLatest.get("id")).longValue();
            buildId = ((Number) exactLatest.get("build_id")).longValue();
            entries = buildService.readyEntries(partitionId);
            reasons.add("命中预计算分区 partitionId=" + partitionId + "（口径 v" + caliber.version()
                    + " + 快照 " + snapshotId + " 双重绑定，已验证结果）");
        } else if (isReady(provinceLatest)) {
            // 经验证的另一条路径：同口径同快照的全省分区 + 授权行级过滤
            routedTo = "FALLBACK_VERIFIED";
            partitionId = ((Number) provinceLatest.get("id")).longValue();
            buildId = ((Number) provinceLatest.get("build_id")).longValue();
            entries = RankingCalculator.applyVisibility(buildService.readyEntries(partitionId), allowedOrgs);
            reasons.add("精确机构范围无就绪分区，改用同口径同快照全省分区 partitionId=" + partitionId
                    + "，并按授权过滤可见机构（行级剔除，无越权泄露）");
        } else if (isInvalidated(exactLatest) || isInvalidated(provinceLatest)) {
            // 口径变更命中：旧口径结果已失效，严禁交付
            reasons.add("CALIBER_INVALIDATED: 分区已被失效登记册命中，旧口径结果禁止交付，"
                    + "必须使用当前 ACTIVE 口径重建或现扫");
            Decision waiting = decideWhenBuilding(exactLatest, provinceLatest, latencyTarget, waitStrategy,
                    hot, reasons);
            if (waiting.shouldWait()) {
                routedTo = "WAITING";
                waitingInfo = waiting.info();
                buildId = waiting.buildId();
                partitionId = waiting.partitionId();
                entries = List.of();
            } else {
                routedTo = "ON_DEMAND_SCAN";
                long start = System.currentTimeMillis();
                entries = calculator.rank(caliber, month, dimension, orgScope, null);
                elapsedMs = System.currentTimeMillis() - start;
                reasons.add("选择经验证的查询时扫描路径（当前口径+当前快照），耗时=" + elapsedMs + "ms");
            }
        } else if (isBuilding(exactLatest) || isBuilding(provinceLatest)) {
            Map<String, Object> building = isBuilding(exactLatest) ? exactLatest : provinceLatest;
            Decision waiting = decideWhenBuilding(building, null, latencyTarget, waitStrategy, hot, reasons);
            if (waiting.shouldWait()) {
                routedTo = "WAITING";
                waitingInfo = waiting.info();
                buildId = waiting.buildId();
                partitionId = waiting.partitionId();
                entries = List.of();
            } else {
                routedTo = "ON_DEMAND_SCAN";
                long start = System.currentTimeMillis();
                entries = calculator.rank(caliber, month, dimension, orgScope, null);
                elapsedMs = System.currentTimeMillis() - start;
                reasons.add("构建未完成且选择不等待，查询时扫描兜底（当前口径，绝不回退旧口径），耗时="
                        + elapsedMs + "ms");
            }
        } else {
            // 无任何构建：按频率与时效目标决定
            routedTo = "ON_DEMAND_SCAN";
            long start = System.currentTimeMillis();
            entries = calculator.rank(caliber, month, dimension, orgScope, null);
            elapsedMs = System.currentTimeMillis() - start;
            reasons.add("无预计算分区，查询时扫描响应，耗时=" + elapsedMs + "ms");
            if (hot) {
                reasons.add("FREQUENCY_HINT: 该键已达高频阈值，建议调用 POST /api/ranking-builds 预计算"
                        + "以把后续请求限制在已验证结果中");
            }
        }

        // ---------- 名次窗口与分页 ----------
        List<Map<String, Object>> pageRows = List.of();
        if (!"WAITING".equals(routedTo)) {
            List<Map<String, Object>> visible = "ORG".equals(dimension) && allowedOrgs != null
                    ? RankingCalculator.applyVisibility(entries, allowedOrgs) : entries;
            List<Map<String, Object>> topped = visible.size() > topN ? visible.subList(0, topN) : visible;
            int from = Math.min((page - 1) * pageSize, topped.size());
            int to = Math.min(from + pageSize, topped.size());
            pageRows = topped.subList(from, to);
        }

        String status = "WAITING".equals(routedTo) ? "WAITING" : "SERVED";
        Long decisionId = jdbc.queryForObject(
                "INSERT INTO ranking_route_decision (caliber_id, build_id, partition_id, username, role,"
                        + " org_code, request_spec, routed_to, status, decision_reasons, adhoc_filters,"
                        + " row_count, elapsed_ms) VALUES (?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?::jsonb,?,?)"
                        + " RETURNING id",
                Long.class, caliberId, buildId, partitionId, user.get("username"), user.get("role"),
                user.get("org_code"), Jsons.write(requestSpec), routedTo, status,
                Jsons.write(reasons), Jsons.write(adhoc), pageRows.size(), elapsedMs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionId", decisionId);
        result.put("caliberId", caliberId);
        result.put("caliberVersion", caliber.version());
        result.put("snapshotId", snapshotId);
        result.put("buildId", buildId);
        result.put("partitionId", partitionId);
        result.put("user", user.get("username"));
        result.put("routedTo", routedTo);
        result.put("status", status);
        result.put("frequency", Map.of("observed", frequency + 1, "hot", hot, "threshold", hotThreshold));
        result.put("decisionReasons", reasons);
        result.put("adhocFilters", adhoc);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("rowCount", pageRows.size());
        result.put("elapsedMs", elapsedMs);
        result.put("rows", pageRows);
        if (waitingInfo != null) {
            result.put("waiting", waitingInfo);
            result.put("suggestedAlternatives", List.of(
                    "构建推进后重新请求本接口（自动命中 PRECOMPUTED）",
                    "携带 waitStrategy=SCAN 重新请求，走当前口径的查询时扫描（经验证路径）"));
        }
        return result;
    }

    /** 构建未完成/失效时：按优先级、时效目标与等待策略决定 WAITING 还是现扫。 */
    private Decision decideWhenBuilding(Map<String, Object> primary, Map<String, Object> secondary,
                                        String latencyTarget, String waitStrategy, boolean hot,
                                        List<String> reasons) {
        Map<String, Object> target = primary != null ? primary : secondary;
        if (target == null) {
            return Decision.scan();
        }
        long bid = ((Number) target.get("build_id")).longValue();
        long pid = ((Number) target.get("id")).longValue();
        Map<String, Object> build = buildService.get(bid);
        String priority = String.valueOf(build.get("priority"));
        boolean high = "HIGH".equals(priority);
        boolean wantWait = "WAIT".equals(waitStrategy)
                || ("AUTO".equals(waitStrategy) && "FAST".equals(latencyTarget) && high);
        if (wantWait) {
            reasons.add("BUILD_PENDING: 高优先级构建 " + bid + "（" + priority + "）进度 "
                    + build.get("progress_percent") + "%，状态=" + build.get("status")
                    + "；返回可解释等待，严禁交付旧口径结果");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("buildId", bid);
            info.put("partitionId", pid);
            info.put("buildStatus", build.get("status"));
            info.put("priority", priority);
            info.put("progressPercent", build.get("progress_percent"));
            info.put("statusReason", build.get("status_reason"));
            return Decision.wait(bid, pid, info);
        }
        reasons.add("BUILD_PENDING 但等待策略=" + waitStrategy + "、时效目标=" + latencyTarget
                + "，不阻塞请求，转查询时扫描（当前口径，结果经验证语义）");
        return Decision.scan();
    }

    private boolean isReady(Map<String, Object> partition) {
        return partition != null && "READY".equals(partition.get("status"));
    }

    private boolean isBuilding(Map<String, Object> partition) {
        return partition != null && "BUILDING".equals(partition.get("status"));
    }

    private boolean isInvalidated(Map<String, Object> partition) {
        return partition != null && "INVALIDATED".equals(partition.get("status"));
    }

    public Map<String, Object> decision(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ranking_route_decision WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("路由决策不存在: " + id);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listDecisions(Long caliberId) {
        if (caliberId == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT id, caliber_id, build_id, partition_id, username, routed_to, status,"
                            + " row_count, elapsed_ms, created_at FROM ranking_route_decision ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, caliber_id, build_id, partition_id, username, routed_to, status,"
                        + " row_count, elapsed_ms, created_at FROM ranking_route_decision"
                + " WHERE caliber_id = ? ORDER BY id DESC", caliberId));
    }

    /** 等待/现扫二选一的内部结论。 */
    private record Decision(boolean shouldWait, Long buildId, Long partitionId, Map<String, Object> info) {

        static Decision scan() {
            return new Decision(false, null, null, null);
        }

        static Decision wait(long buildId, long partitionId, Map<String, Object> info) {
            return new Decision(true, buildId, partitionId, info);
        }
    }
}
