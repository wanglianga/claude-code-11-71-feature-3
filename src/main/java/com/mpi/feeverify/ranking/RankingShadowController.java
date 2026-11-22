package com.mpi.feeverify.ranking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ranking-shadow")
@Tag(name = "影子排行比较器",
        description = "同快照下比较两个口径版本的前N名/金额/并列/可见机构；并列规则差异强制转发布审批")
public class RankingShadowController {

    private final RankingShadowService shadowService;

    public RankingShadowController(RankingShadowService shadowService) {
        this.shadowService = shadowService;
    }

    @PostMapping("/compare")
    @Operation(summary = "比较两个口径版本的月度排行。body: {baseCaliberId, targetCaliberId, month,"
            + " dimension, orgScope, topN, token?（提供则按授权过滤可见机构）}")
    public Map<String, Object> compare(@RequestBody Map<String, Object> body) {
        return shadowService.compare(
                ((Number) body.get("baseCaliberId")).longValue(),
                ((Number) body.get("targetCaliberId")).longValue(),
                (String) body.get("month"),
                (String) body.getOrDefault("dimension", "DRUG"),
                (String) body.getOrDefault("orgScope", "*"),
                body.get("topN") == null ? 10 : ((Number) body.get("topN")).intValue(),
                (String) body.get("token"));
    }

    @GetMapping("/compares")
    @Operation(summary = "影子比较记录列表（可按 targetCaliberId 过滤）")
    public List<Map<String, Object>> compares(@RequestParam(required = false) Long targetCaliberId) {
        return shadowService.listCompares(targetCaliberId);
    }

    @GetMapping("/approvals/{id}")
    @Operation(summary = "发布审批详情")
    public Map<String, Object> approval(@PathVariable long id) {
        return shadowService.getApproval(id);
    }

    @GetMapping("/approvals")
    @Operation(summary = "发布审批列表（可按 status 过滤）")
    public List<Map<String, Object>> approvals(@RequestParam(required = false) String status) {
        return shadowService.listApprovals(status);
    }

    @PostMapping("/approvals/{id}/review")
    @Operation(summary = "审批处置：decision=APPROVED/REJECTED")
    public Map<String, Object> review(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return shadowService.reviewApproval(id, (String) body.get("decision"),
                (String) body.getOrDefault("reviewer", "admin"), (String) body.get("comment"));
    }
}
