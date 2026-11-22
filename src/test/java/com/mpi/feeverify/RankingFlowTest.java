package com.mpi.feeverify;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.ranking.RankingDemoService;
import com.mpi.feeverify.ranking.RankingRouterService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排行模块集成测试：在独立 schema（verify_it）中真实跑通
 * 指标口径版本库 / 构建器 / 路由决策器 / 影子比较器 / 失效登记册，
 * 验收局部分类调整、构建延迟、并列处理与权限过滤四类样例。
 */
@SpringBootTest
@ActiveProfiles("test")
class RankingFlowTest {

    @Autowired
    private RankingDemoService rankingDemoService;

    @Autowired
    private RankingRouterService routerService;

    @Autowired
    private MetricCaliberService caliberService;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 排行模块的状态（口径/构建/分区/决策/比较/审批/登记）跨测试方法必须隔离：
     * verify_it schema 不会被 Flyway 重建，这里每轮清空 V3 表并重置种子口径 v1。
     */
    @BeforeEach
    void resetRankingState() {
        jdbc.execute("TRUNCATE TABLE ranking_approval, ranking_shadow_compare,"
                + " caliber_invalidation_register, ranking_route_decision, ranking_partition,"
                + " ranking_build, metric_caliber_version RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO metric_caliber_version"
                        + " (family, version, category_rules, exclusion_rules, sort_rules, tie_rules,"
                        + " scope_rules, status, change_note, activated_at)"
                        + " VALUES ('fee-ranking', 1, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,"
                        + " 'ACTIVE', '测试种子口径', now())",
                Jsons.write(List.of()),
                Jsons.write(java.util.Map.of("settleStatuses", List.of("REJECTED"),
                        "drugCategories", List.of(), "drugCodes", List.of())),
                Jsons.write(java.util.Map.of("metric", "AMOUNT", "direction", "DESC")),
                Jsons.write(java.util.Map.of("strategy", "COMPETITION", "tieBreaker", "CODE_ASC")),
                Jsons.write(java.util.Map.of("dimensions", List.of("ORG", "DEPT", "DRUG"),
                        "authGranularities", List.of("PROVINCE", "ORG"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRankingFlowCoversAllRequiredScenarios() {
        Map<String, Object> report = rankingDemoService.run();

        // 1. 频率路由：无构建时三次现扫，第三次达到高频阈值并给出预计算提示
        Map<String, Object> s1 = (Map<String, Object>) report.get("scenario1_frequencyRouting");
        assertEquals(List.of("ON_DEMAND_SCAN", "ON_DEMAND_SCAN", "ON_DEMAND_SCAN"), s1.get("paths"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) s1.get("frequency")).get("hot"));
        assertEquals(Boolean.TRUE, s1.get("hotHintEmitted"));

        // 2. 基线构建 READY；高频请求命中预计算，临时筛选现扫，深分页记录资源代价
        Map<String, Object> s2b = (Map<String, Object>) report.get("scenario2_baselineBuild");
        assertEquals("READY", s2b.get("status"));
        assertEquals(100, s2b.get("progressPercent"));
        Map<String, Object> s2r = (Map<String, Object>) report.get("scenario2_routingPaths");
        assertEquals("PRECOMPUTED", s2r.get("hotRequest"));
        assertEquals("ON_DEMAND_SCAN", s2r.get("adhocFilterRequest"));
        // 深分页命中预计算：无需重新扫描明细，决策依据仍记录现扫路径的深分页代价
        assertEquals("PRECOMPUTED", s2r.get("deepPageRequest"));
        assertEquals(Boolean.TRUE, s2r.get("deepPageReasonMentionsCost"));

        // 3. 权限过滤：精确分区缺失走经验证全省分区+授权过滤，无越权机构泄露；精确分区直接命中
        Map<String, Object> s3 = (Map<String, Object>) report.get("scenario3_permissionFilter");
        assertEquals("FALLBACK_VERIFIED", s3.get("fallbackRoutedTo"));
        List<String> visible = (List<String>) s3.get("fallbackVisibleCodes");
        assertFalse(visible.isEmpty());
        assertEquals(Boolean.TRUE, s3.get("noUnauthorizedOrgLeak"));
        assertEquals("PRECOMPUTED", s3.get("exactScopeRoutedTo"));
        assertNotNull(s3.get("exactPartitionId"));

        // 4. 构建延迟：高优先级未完成 → 可解释等待；不等待则当前口径现扫；推进后命中预计算
        Map<String, Object> s4 = (Map<String, Object>) report.get("scenario4_buildDelay");
        assertEquals("RUNNING", s4.get("initialStatus"));
        assertEquals("WAITING", s4.get("fastRequestRoutedTo"));
        Map<String, Object> waiting = (Map<String, Object>) s4.get("waitingInfo");
        assertEquals("HIGH", waiting.get("priority"));
        assertNotNull(waiting.get("statusReason"));
        assertEquals("ON_DEMAND_SCAN", s4.get("scanStrategyRoutedTo"));
        assertEquals(Boolean.TRUE, s4.get("scanUsesCurrentCaliber"));
        assertEquals("READY", s4.get("afterAdvanceBuildStatus"));
        assertEquals("PRECOMPUTED", s4.get("afterAdvanceRoutedTo"));

        // 5. 局部分类调整：失效精确到 谈判药/乙类 与 G003/G007；封账月保留；控制组比较不触发审批
        Map<String, Object> s5 = (Map<String, Object>) report.get("scenario5_localCategoryAdjustment");
        assertEquals(Boolean.TRUE, s5.get("preciseScopeOnly"));
        assertEquals(List.of("G003", "G007"), s5.get("affectedDrugCodes"));
        assertEquals(List.of("2025-11"), s5.get("affectedMonths"));
        assertTrue(((Number) s5.get("invalidatedPartitionCount")).intValue() > 0);
        assertEquals(Boolean.TRUE, s5.get("sealedMonthsKeptReady"));
        assertNotBlocked(s5.get("controlCompareVerdict"));
        assertEquals(Boolean.TRUE, s5.get("controlCompareNoApproval"));

        Map<String, Object> s5r = (Map<String, Object>) report.get("scenario5_rebuild");
        assertEquals("REBUILT", s5r.get("registerStatus"));
        assertEquals("PRECOMPUTED", s5r.get("routedTo"));
        assertEquals(Boolean.TRUE, s5r.get("noOldCaliberServed"));

        // 6. 并列规则差异：影子比较 BLOCKED 并强制发布审批，审批通过后留痕
        Map<String, Object> s6 = (Map<String, Object>) report.get("scenario6_tieRuleApproval");
        assertEquals(Boolean.TRUE, s6.get("tieRuleDiffers"));
        assertEquals("BLOCKED", s6.get("verdict"));
        assertFalse(((List<?>) s6.get("blockingItems")).isEmpty());
        assertFalse(((List<?>) s6.get("rankAndTieDiffs")).isEmpty(), "并列名次差异应被定位");
        assertNotNull(s6.get("approvalId"));
        assertEquals("APPROVED", s6.get("approvalStatus"));
    }

    private void assertNotBlocked(Object verdict) {
        assertTrue("MATCH".equals(verdict) || "DIFF".equals(verdict),
                "并列规则相同的比较不应 BLOCKED，实际=" + verdict);
    }

    @Test
    void routerRejectsInvalidToken() {
        ApiException e = assertThrows(ApiException.class, () -> routerService.route(Map.of(
                "token", "tok-not-exists", "month", "2025-08", "dimension", "DRUG")));
        assertEquals(HttpStatus.UNAUTHORIZED, e.status());
    }

    @Test
    void nonActiveCaliberCannotServe() {
        Map<String, Object> draft = caliberService.createDraft(Map.of(
                "changeNote", "未生效草稿"));
        long draftId = ((Number) draft.get("id")).longValue();
        ApiException e = assertThrows(ApiException.class, () -> routerService.route(Map.of(
                "token", "tok-drg01", "caliberId", draftId,
                "month", "2025-08", "dimension", "DRUG")));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }
}
