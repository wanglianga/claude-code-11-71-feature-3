package com.mpi.feeverify.ranking;

import com.mpi.feeverify.metric.MetricCaliberService;
import com.mpi.feeverify.support.ApiException;
import com.mpi.feeverify.support.Jsons;
import com.mpi.feeverify.support.UserDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 影子排行比较器：在同一冻结快照上比较两个口径版本的月度排行，
 * 核对前 N 名（集合/顺序）、金额、并列名次与授权过滤后的可见机构。
 *
 * 阻断规则：两个版本的并列规则不同时，比较结论必须转发布审批
 * （ranking_approval，PENDING），审批通过前新口径不得生效。
 */
@Service
public class RankingShadowService {

    private final JdbcTemplate jdbc;
    private final MetricCaliberService caliberService;
    private final RankingBuildService buildService;
    private final UserDirectory users;

    public RankingShadowService(JdbcTemplate jdbc, MetricCaliberService caliberService,
                                RankingBuildService buildService, UserDirectory users) {
        this.jdbc = jdbc;
        this.caliberService = caliberService;
        this.buildService = buildService;
        this.users = users;
    }

    @Transactional
    public Map<String, Object> compare(long baseCaliberId, long targetCaliberId, String month,
                                       String dimension, String orgScope, int topN, String token) {
        Map<String, Object> base = caliberService.get(baseCaliberId);
        Map<String, Object> target = caliberService.get(targetCaliberId);
        long snapshotId = ((Number) jdbc.queryForMap(
                "SELECT id FROM data_snapshot WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1").get("id")).longValue();

        Map<String, Object> basePartition = requireReadyPartition(baseCaliberId, snapshotId, month, dimension, orgScope);
        Map<String, Object> targetPartition = requireReadyPartition(targetCaliberId, snapshotId, month, dimension, orgScope);
        long baseBuildId = ((Number) basePartition.get("build_id")).longValue();
        long targetBuildId = ((Number) targetPartition.get("build_id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> baseEntries = (List<Map<String, Object>>) basePartition.get("entries");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targetEntries = (List<Map<String, Object>>) targetPartition.get("entries");

        List<String> allowedOrgs = null;
        if (token != null) {
            Map<String, Object> user = users.authenticate(token);
            allowedOrgs = users.allowedOrgs(user, MetricCaliberService.FAMILY);
        }
        if ("ORG".equals(dimension) && allowedOrgs != null) {
            baseEntries = RankingCalculator.applyVisibility(baseEntries, allowedOrgs);
            targetEntries = RankingCalculator.applyVisibility(targetEntries, allowedOrgs);
        }

        Map<String, Object> caliberDiff = caliberService.diff(base, target);
        boolean tieRuleDiffers = Boolean.TRUE.equals(caliberDiff.get("tieRuleDiffers"));

        Map<String, Object> topnDiff = buildTopnDiff(baseEntries, targetEntries, topN);
        Map<String, Object> visibleOrgDiff = "ORG".equals(dimension)
                ? buildVisibleOrgDiff(baseEntries, targetEntries, topN) : null;

        boolean topnChanged = Boolean.TRUE.equals(topnDiff.get("changed"));
        String verdict = tieRuleDiffers ? "BLOCKED" : topnChanged ? "DIFF" : "MATCH";

        List<String> blockingItems = new ArrayList<>();
        if (tieRuleDiffers) {
            Object from = ((Map<?, ?>) caliberDiff.get("TIE")).get("from");
            Object to = ((Map<?, ?>) caliberDiff.get("TIE")).get("to");
            blockingItems.add("TIE_RULE_CHANGED: " + from + " → " + to
                    + "；并列名次口径变化必须经发布审批，禁止自动切换");
        }

        Long compareId = jdbc.queryForObject(
                "INSERT INTO ranking_shadow_compare (base_caliber_id, target_caliber_id, base_build_id,"
                        + " target_build_id, snapshot_id, month, dimension, org_scope, top_n, caliber_diff,"
                        + " topn_diff, visible_org_diff, tie_rule_differs, verdict)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?) RETURNING id",
                Long.class, baseCaliberId, targetCaliberId, baseBuildId, targetBuildId, snapshotId,
                month, dimension, orgScope, topN, Jsons.write(caliberDiff), Jsons.write(topnDiff),
                visibleOrgDiff == null ? null : Jsons.write(visibleOrgDiff), tieRuleDiffers, verdict);

        Long approvalId = null;
        if (tieRuleDiffers) {
            approvalId = jdbc.queryForObject(
                    "INSERT INTO ranking_approval (compare_id, target_caliber_id, status, blocking_items,"
                            + " submitted_by) VALUES (?,?, 'PENDING', ?::jsonb, ?) RETURNING id",
                    Long.class, compareId, targetCaliberId, Jsons.write(blockingItems),
                    token == null ? "system-shadow" : token);
            jdbc.update("UPDATE ranking_shadow_compare SET approval_id = ? WHERE id = ?", approvalId, compareId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compareId", compareId);
        result.put("baseCaliberId", baseCaliberId);
        result.put("targetCaliberId", targetCaliberId);
        result.put("snapshotId", snapshotId);
        result.put("baseBuildId", baseBuildId);
        result.put("targetBuildId", targetBuildId);
        result.put("month", month);
        result.put("dimension", dimension);
        result.put("orgScope", orgScope);
        result.put("topN", topN);
        result.put("caliberDiff", caliberDiff);
        result.put("topnDiff", topnDiff);
        result.put("visibleOrgDiff", visibleOrgDiff);
        result.put("tieRuleDiffers", tieRuleDiffers);
        result.put("verdict", verdict);
        result.put("blockingItems", blockingItems);
        result.put("approvalId", approvalId);
        return result;
    }

    private Map<String, Object> requireReadyPartition(long caliberId, long snapshotId, String month,
                                                      String dimension, String orgScope) {
        List<Map<String, Object>> partitions =
                buildService.findPartitions(caliberId, snapshotId, month, dimension, orgScope);
        return partitions.stream().filter(p -> "READY".equals(p.get("status"))).findFirst()
                .orElseThrow(() -> ApiException.conflict("口径 " + caliberId + " 在快照 " + snapshotId
                        + " 上没有就绪分区（" + month + "/" + dimension + "/" + orgScope
                        + "），请先调用 POST /api/ranking-builds 完成构建再做影子比较"));
    }

    private Map<String, Object> buildTopnDiff(List<Map<String, Object>> baseEntries,
                                              List<Map<String, Object>> targetEntries, int topN) {
        List<Map<String, Object>> baseTop = baseEntries.stream().limit(topN).toList();
        List<Map<String, Object>> targetTop = targetEntries.stream().limit(topN).toList();
        List<String> baseCodes = baseTop.stream().map(e -> String.valueOf(e.get("code"))).toList();
        List<String> targetCodes = targetTop.stream().map(e -> String.valueOf(e.get("code"))).toList();

        List<String> removed = new ArrayList<>(baseCodes);
        removed.removeAll(targetCodes);
        List<String> added = new ArrayList<>(targetCodes);
        added.removeAll(baseCodes);
        boolean orderChanged = !baseCodes.equals(targetCodes);

        Map<String, Object> baseByCode = indexByCode(baseTop);
        Map<String, Object> targetByCode = indexByCode(targetTop);
        List<Map<String, Object>> amountDiffs = new ArrayList<>();
        List<Map<String, Object>> rankDiffs = new ArrayList<>();
        for (String code : baseCodes) {
            if (!targetByCode.containsKey(code)) {
                continue;
            }
            Map<String, Object> b = (Map<String, Object>) baseByCode.get(code);
            Map<String, Object> t = (Map<String, Object>) targetByCode.get(code);
            if (!Objects.equals(String.valueOf(b.get("amount")), String.valueOf(t.get("amount")))) {
                amountDiffs.add(Map.of("code", code, "baseAmount", b.get("amount"),
                        "targetAmount", t.get("amount")));
            }
            if (!Objects.equals(b.get("rank"), t.get("rank")) || !Objects.equals(b.get("tied"), t.get("tied"))) {
                rankDiffs.add(Map.of("code", code,
                        "base", Map.of("rank", b.get("rank"), "tied", b.get("tied")),
                        "target", Map.of("rank", t.get("rank"), "tied", t.get("tied"))));
            }
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("baseOrder", baseCodes);
        diff.put("targetOrder", targetCodes);
        diff.put("removedFromTopN", removed);
        diff.put("addedToTopN", added);
        diff.put("orderChanged", orderChanged);
        diff.put("amountDiffs", amountDiffs);
        diff.put("rankAndTieDiffs", rankDiffs);
        boolean changed = orderChanged || !amountDiffs.isEmpty() || !rankDiffs.isEmpty();
        diff.put("changed", changed);
        return diff;
    }

    private Map<String, Object> buildVisibleOrgDiff(List<Map<String, Object>> baseEntries,
                                                    List<Map<String, Object>> targetEntries, int topN) {
        List<String> baseOrgs = baseEntries.stream().limit(topN).map(e -> String.valueOf(e.get("code"))).toList();
        List<String> targetOrgs = targetEntries.stream().limit(topN).map(e -> String.valueOf(e.get("code"))).toList();
        List<String> disappeared = new ArrayList<>(baseOrgs);
        disappeared.removeAll(targetOrgs);
        List<String> appeared = new ArrayList<>(targetOrgs);
        appeared.removeAll(baseOrgs);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("baseVisibleOrgs", baseOrgs);
        diff.put("targetVisibleOrgs", targetOrgs);
        diff.put("disappeared", disappeared);
        diff.put("appeared", appeared);
        diff.put("changed", !disappeared.isEmpty() || !appeared.isEmpty());
        return diff;
    }

    private Map<String, Object> indexByCode(List<Map<String, Object>> entries) {
        Map<String, Object> map = new TreeMap<>();
        for (Map<String, Object> e : entries) {
            map.put(String.valueOf(e.get("code")), e);
        }
        return map;
    }

    // ----- 发布审批 -----

    @Transactional
    public Map<String, Object> reviewApproval(long approvalId, String decision, String reviewer,
                                              String comment) {
        if (!List.of("APPROVED", "REJECTED").contains(decision)) {
            throw ApiException.badRequest("decision 仅支持 APPROVED / REJECTED");
        }
        Map<String, Object> approval = getApproval(approvalId);
        if (!"PENDING".equals(approval.get("status"))) {
            throw ApiException.conflict("审批 " + approvalId + " 已处置，当前=" + approval.get("status"));
        }
        jdbc.update("UPDATE ranking_approval SET status = ?, reviewed_by = ?, review_comment = ?,"
                + " reviewed_at = now() WHERE id = ?", decision, reviewer, comment, approvalId);
        return getApproval(approvalId);
    }

    public Map<String, Object> getApproval(long approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ranking_approval WHERE id = ?", approvalId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("发布审批不存在: " + approvalId);
        }
        return Jsons.normalizeRow(rows.get(0));
    }

    public List<Map<String, Object>> listApprovals(String status) {
        if (status == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT * FROM ranking_approval ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT * FROM ranking_approval WHERE status = ? ORDER BY id DESC", status));
    }

    public List<Map<String, Object>> listCompares(Long targetCaliberId) {
        if (targetCaliberId == null) {
            return Jsons.normalizeRows(jdbc.queryForList(
                    "SELECT id, base_caliber_id, target_caliber_id, month, dimension, org_scope,"
                            + " tie_rule_differs, verdict, approval_id, created_at"
                            + " FROM ranking_shadow_compare ORDER BY id DESC"));
        }
        return Jsons.normalizeRows(jdbc.queryForList(
                "SELECT id, base_caliber_id, target_caliber_id, month, dimension, org_scope,"
                        + " tie_rule_differs, verdict, approval_id, created_at"
                        + " FROM ranking_shadow_compare WHERE target_caliber_id = ? ORDER BY id DESC",
                targetCaliberId));
    }
}
