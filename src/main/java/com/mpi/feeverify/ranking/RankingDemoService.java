package com.mpi.feeverify.ranking;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.snapshot.SnapshotService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行模块演示编排：一次调用真实落库跑通四个验收样例——
 * ① 局部分类调整：单药品级口径调整，失效登记精确到分类/药品/月份/分区；
 * ② 构建延迟：HIGH 构建未完成时高优先级请求可解释等待，绝不交付旧口径；
 * ③ 并列处理：并列规则变化的影子比较强制转发布审批；
 * ④ 权限过滤：机构级账号经授权行级过滤，精确分区缺失时走经验证的全省分区。
 * 另含频率路由、临时筛选现扫与深分页代价说明。
 */
@Service
public class RankingDemoService {

    private final SnapshotService snapshotService;
    private final MetricCaliberService caliberService;
    private final RankingBuildService buildService;
    private final RankingRouterService routerService;
    private final RankingShadowService shadowService;
    private final InvalidationService invalidationService;

    public RankingDemoService(SnapshotService snapshotService,
                              MetricCaliberService caliberService, RankingBuildService buildService,
                              RankingRouterService routerService, RankingShadowService shadowService,
                              InvalidationService invalidationService) {
        this.snapshotService = snapshotService;
        this.caliberService = caliberService;
        this.buildService = buildService;
        this.routerService = routerService;
        this.shadowService = shadowService;
        this.invalidationService = invalidationService;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        Map<String, Object> report = new LinkedHashMap<>();

        // ---------- 0. 冻结快照 + 取生效口径 v1 ----------
        Map<String, Object> snapshot = snapshotService.freeze("ranking-demo", "排行演示：封账月冻结快照");
        long snapshotId = ((Number) snapshot.get("snapshotId")).longValue();
        Map<String, Object> v1 = caliberService.active(MetricCaliberService.FAMILY);
        long v1Id = ((Number) v1.get("id")).longValue();
        report.put("setup", Map.of("snapshotId", snapshotId, "checksum", snapshot.get("checksum"),
                "activeCaliberId", v1Id, "caliberVersion", v1.get("version")));

        // ---------- 1. 频率路由：无构建时查询时扫描，达高频阈值给预计算提示 ----------
        Map<String, Object> scan1 = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG"));
        Map<String, Object> scan2 = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG"));
        Map<String, Object> scan3 = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG"));
        List<String> thirdReasons = ((List<?>) scan3.get("decisionReasons")).stream()
                .map(String::valueOf).toList();
        report.put("scenario1_frequencyRouting", Map.of(
                "paths", List.of(scan1.get("routedTo"), scan2.get("routedTo"), scan3.get("routedTo")),
                "frequency", scan3.get("frequency"),
                "hotHintEmitted", thirdReasons.stream().anyMatch(r -> r.contains("FREQUENCY_HINT"))));

        // ---------- 2. 基线预计算构建（v1，覆盖 2025-08~11，全省+ORG001 两种授权粒度）----------
        Map<String, Object> baseline = buildService.requestBuild(v1Id, Map.of(
                "monthFrom", "2025-08", "monthTo", "2025-11",
                "dimensions", List.of("ORG", "DEPT", "DRUG"),
                "orgScopes", List.of("*", "ORG001"), "topN", 10), "NORMAL", List.of(), "demo");
        long baselineBuildId = ((Number) baseline.get("id")).longValue();
        report.put("scenario2_baselineBuild", Map.of(
                "buildId", baselineBuildId, "status", baseline.get("status"),
                "progressPercent", baseline.get("progress_percent"),
                "availability", baseline.get("availability")));

        // 高频请求随后命中预计算；临时筛选与深分页各走一次
        Map<String, Object> precomputed = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG"));
        Map<String, Object> adhoc = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG",
                "adhocFilters", Map.of("minAmount", 300, "extraExcludedCategories", List.of("丙类"))));
        Map<String, Object> deepPage = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-08", "dimension", "DRUG",
                "topN", 10, "page", 2, "pageSize", 5));
        report.put("scenario2_routingPaths", Map.of(
                "hotRequest", precomputed.get("routedTo"),
                "adhocFilterRequest", adhoc.get("routedTo"),
                "adhocRowCount", adhoc.get("rowCount"),
                "deepPageRequest", deepPage.get("routedTo"),
                "deepPageReasonMentionsCost",
                String.valueOf(deepPage.get("decisionReasons")).contains("深分页"),
                "deepPageNote", "预计算命中时深分页不再扫描明细；决策依据保留现扫路径的深分页代价说明"));

        // ---------- 3. 权限过滤：机构级账号 + 经验证的全省分区兜底（2025-07 仅建全省分区）----------
        Map<String, Object> provinceOnly = buildService.requestBuild(v1Id, Map.of(
                "monthFrom", "2025-07", "monthTo", "2025-07",
                "dimensions", List.of("ORG"), "orgScopes", List.of("*"), "topN", 10),
                "NORMAL", List.of(), "demo");
        Map<String, Object> auditFallback = routerService.route(Map.of(
                "token", "tok-audit01", "month", "2025-07", "dimension", "ORG", "topN", 20));
        List<String> fallbackCodes = ((List<Map<String, Object>>) auditFallback.get("rows")).stream()
                .map(r -> String.valueOf(r.get("code"))).toList();
        // 有精确 ORG001 分区的月份直接命中预计算
        Map<String, Object> auditExact = routerService.route(Map.of(
                "token", "tok-audit01", "month", "2025-08", "dimension", "ORG", "topN", 20));
        report.put("scenario3_permissionFilter", Map.of(
                "provinceOnlyBuildId", provinceOnly.get("id"),
                "fallbackRoutedTo", auditFallback.get("routedTo"),
                "fallbackVisibleCodes", fallbackCodes,
                "noUnauthorizedOrgLeak",
                fallbackCodes.stream().noneMatch(c -> List.of("ORG000", "ORG004", "ORG005").contains(c)),
                "exactScopeRoutedTo", auditExact.get("routedTo"),
                "exactPartitionId", auditExact.get("partitionId")));

        // ---------- 4. 构建延迟：HIGH 构建未完成 → 可解释等待 / 现扫二选一 ----------
        Map<String, Object> delayed = buildService.requestBuild(v1Id, Map.of(
                "monthFrom", "2025-10", "monthTo", "2025-10",
                "dimensions", List.of("DRUG"), "orgScopes", List.of("*"), "topN", 10),
                "HIGH", List.of("2025-10"), "demo");
        long delayedBuildId = ((Number) delayed.get("id")).longValue();
        Map<String, Object> waiting = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-10", "dimension", "DRUG",
                "latencyTarget", "FAST", "waitStrategy", "AUTO"));
        Map<String, Object> noWaitScan = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-10", "dimension", "DRUG",
                "waitStrategy", "SCAN"));
        Map<String, Object> advanced = buildService.advance(delayedBuildId);
        Map<String, Object> afterAdvance = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-10", "dimension", "DRUG"));
        report.put("scenario4_buildDelay", Map.of(
                "delayedBuildId", delayedBuildId,
                "initialStatus", delayed.get("status"),
                "fastRequestRoutedTo", waiting.get("routedTo"),
                "waitingInfo", waiting.get("waiting"),
                "scanStrategyRoutedTo", noWaitScan.get("routedTo"),
                "scanUsesCurrentCaliber",
                String.valueOf(noWaitScan.get("caliberId")).equals(String.valueOf(v1Id)),
                "afterAdvanceBuildStatus", advanced.get("status"),
                "afterAdvanceRoutedTo", afterAdvance.get("routedTo")));

        // ---------- 5. 局部分类调整：v2 仅调整 G003/G007（谈判药→乙类）----------
        Map<String, Object> v2Draft = caliberService.createDraft(Map.of(
                "changeNote", "局部口径：G003/G007 由谈判药调整为乙类",
                "categoryRules", List.of(Map.of(
                        "from", "谈判药", "to", "乙类",
                        "drugCodes", List.of("G003", "G007")))));
        long v2Id = ((Number) v2Draft.get("id")).longValue();
        // 新口径先在 2025-11 建出分区（DRAFT 亦可构建，等待审批与生效）
        buildService.requestBuild(v2Id, Map.of(
                "monthFrom", "2025-11", "monthTo", "2025-11",
                "dimensions", List.of("ORG", "DEPT", "DRUG"), "orgScopes", List.of("*"), "topN", 10),
                "NORMAL", List.of(), "demo");
        // 对照比较：仅分类变化、并列规则相同 → 不触发发布审批（此时旧口径分区仍 READY）
        Map<String, Object> nonTieCompare = shadowService.compare(
                v1Id, v2Id, "2025-11", "DRUG", "*", 10, null);
        Map<String, Object> v2 = caliberService.activate(v2Id);

        Map<String, Object> invalidation = invalidationService.register(
                v1Id, v2Id, "2025-11", "政策只影响谈判药中的 G003/G007");
        long invalidationId = ((Number) invalidation.get("id")).longValue();
        List<Map<String, Object>> sealedPartitions = buildService.partitions(baselineBuildId).stream()
                .filter(p -> List.of("2025-08", "2025-09", "2025-10").contains(p.get("month")))
                .toList();
        List<?> affectedCategories = (List<?>) invalidation.get("affected_categories");
        Map<String, Object> scenario5 = new LinkedHashMap<>();
        scenario5.put("targetCaliberId", v2Id);
        scenario5.put("caliberDiff", v2.get("diff"));
        scenario5.put("invalidationId", invalidationId);
        scenario5.put("affectedCategories", invalidation.get("affected_categories"));
        scenario5.put("affectedDrugCodes", invalidation.get("affected_drug_codes"));
        scenario5.put("affectedMonths", invalidation.get("affected_months"));
        scenario5.put("invalidatedPartitionCount", invalidation.get("invalidatedPartitionCount"));
        scenario5.put("sealedMonthsKeptReady",
                sealedPartitions.stream().allMatch(p -> "READY".equals(p.get("status"))));
        scenario5.put("preciseScopeOnly",
                affectedCategories.size() == 2
                        && affectedCategories.containsAll(List.of("谈判药", "乙类"))
                        && !affectedCategories.contains("甲类")
                        && !affectedCategories.contains("丙类"));
        scenario5.put("controlCompareVerdict", nonTieCompare.get("verdict"));
        scenario5.put("controlCompareNoApproval", nonTieCompare.get("approvalId") == null);
        report.put("scenario5_localCategoryAdjustment", scenario5);

        // 登记册重建：新口径 HIGH 构建，完成后路由命中新口径预计算
        Map<String, Object> rebuild = invalidationService.rebuild(invalidationId, "demo");
        Map<String, Object> afterRebuild = routerService.route(Map.of(
                "token", "tok-drg01", "month", "2025-11", "dimension", "DRUG"));
        report.put("scenario5_rebuild", Map.of(
                "registerStatus", rebuild.get("status"),
                "rebuildBuildId", rebuild.get("rebuildBuildId"),
                "routedTo", afterRebuild.get("routedTo"),
                "servedCaliberId", afterRebuild.get("caliberId"),
                "noOldCaliberServed",
                String.valueOf(afterRebuild.get("caliberId")).equals(String.valueOf(v2Id))));

        // ---------- 6. 并列规则差异：v3 改稠密并列，影子比较强制转发布审批 ----------
        // 选 2025-07：该月药品排行第 5 名即出现同额并列，落在 Top10 窗口内，
        // 竞赛并列(1,1,3) 与稠密并列(1,1,2) 的名次差异可被比较器定位
        Map<String, Object> v3Draft = caliberService.createDraft(Map.of(
                "changeNote", "并列规则由竞赛并列改为稠密并列",
                "tieRules", Map.of("strategy", "DENSE", "tieBreaker", "CODE_ASC")));
        long v3Id = ((Number) v3Draft.get("id")).longValue();
        buildService.requestBuild(v1Id, Map.of(
                "monthFrom", "2025-07", "monthTo", "2025-07",
                "dimensions", List.of("DRUG"), "orgScopes", List.of("*"), "topN", 10),
                "NORMAL", List.of(), "demo");
        buildService.requestBuild(v3Id, Map.of(
                "monthFrom", "2025-07", "monthTo", "2025-07",
                "dimensions", List.of("DRUG"), "orgScopes", List.of("*"), "topN", 10),
                "NORMAL", List.of(), "demo");
        Map<String, Object> blockedCompare = shadowService.compare(
                v1Id, v3Id, "2025-07", "DRUG", "*", 10, "tok-drg01");
        long approvalId = ((Number) blockedCompare.get("approvalId")).longValue();
        Map<String, Object> approval = shadowService.reviewApproval(
                approvalId, "APPROVED", "audit-director", "确认稠密并列名次可发布");
        report.put("scenario6_tieRuleApproval", Map.of(
                "v3CaliberId", v3Id,
                "tieRuleDiffers", blockedCompare.get("tieRuleDiffers"),
                "verdict", blockedCompare.get("verdict"),
                "blockingItems", blockedCompare.get("blockingItems"),
                "rankAndTieDiffs", ((Map<?, ?>) blockedCompare.get("topnDiff")).get("rankAndTieDiffs"),
                "approvalId", approvalId,
                "approvalStatus", approval.get("status")));

        report.put("summary", Map.of(
                "snapshotId", snapshotId,
                "calibers", List.of(v1Id, v2Id, v3Id),
                "scenariosCovered", List.of("frequencyRouting", "precomputedVsScan",
                        "permissionFilter", "buildDelay", "localCategoryInvalidation", "tieRuleApproval")));
        return report;
    }
}
