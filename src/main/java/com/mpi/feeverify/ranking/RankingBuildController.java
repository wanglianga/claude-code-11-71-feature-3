package com.mpi.feeverify.ranking;

import com.mpi.feeverify.support.Jsons;
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
@RequestMapping("/api/ranking-builds")
@Tag(name = "排行榜构建器", description = "绑定口径与快照的预计算构建：输入快照/构建进度/可用范围，支持延迟分区推进")
public class RankingBuildController {

    private final RankingBuildService buildService;

    public RankingBuildController(RankingBuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping
    @Operation(summary = "登记并执行构建。body: {caliberId, buildSpec{monthFrom,monthTo,dimensions,orgScopes,topN},"
            + " priority, holdMonths（模拟构建延迟的月份）, requestedBy}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> request(@RequestBody Map<String, Object> body) {
        return buildService.requestBuild(
                ((Number) body.get("caliberId")).longValue(),
                (Map<String, Object>) body.get("buildSpec"),
                (String) body.get("priority"),
                Jsons.asList(body.get("holdMonths")).stream().map(String::valueOf).toList(),
                (String) body.get("requestedBy"));
    }

    @PostMapping("/{id}/advance")
    @Operation(summary = "推进被延迟的分区（BUILDING → READY），模拟构建完成")
    public Map<String, Object> advance(@PathVariable long id) {
        return buildService.advance(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "构建详情（含进度、可用范围、状态原因）")
    public Map<String, Object> get(@PathVariable long id) {
        return buildService.get(id);
    }

    @GetMapping
    @Operation(summary = "构建列表（可按 caliberId 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long caliberId) {
        return buildService.list(caliberId);
    }

    @GetMapping("/{id}/partitions")
    @Operation(summary = "构建下的排行分区（月份×维度×机构范围，含失效标记）")
    public List<Map<String, Object>> partitions(@PathVariable long id) {
        return buildService.partitions(id);
    }

    @GetMapping("/partitions/{partitionId}")
    @Operation(summary = "分区详情（含完整排行 entries 与口径摘要）")
    public Map<String, Object> partition(@PathVariable long partitionId) {
        return buildService.partition(partitionId);
    }
}
