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
@RequestMapping("/api/ranking-router")
@Tag(name = "排行查询路由决策器",
        description = "按口径/月份/授权/时效目标/频率选择 PRECOMPUTED/ON_DEMAND_SCAN/WAITING/FALLBACK_VERIFIED")
public class RankingRouterController {

    private final RankingRouterService routerService;

    public RankingRouterController(RankingRouterService routerService) {
        this.routerService = routerService;
    }

    @PostMapping("/query")
    @Operation(summary = "路由一次月度排行请求。body: {token, caliberId?, month, dimension, topN, page, pageSize,"
            + " latencyTarget(FAST/NORMAL), waitStrategy(WAIT/SCAN/AUTO), adhocFilters?}")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        return routerService.route(body);
    }

    @GetMapping("/decisions/{id}")
    @Operation(summary = "路由决策详情（含逐条决策依据）")
    public Map<String, Object> decision(@PathVariable long id) {
        return routerService.decision(id);
    }

    @GetMapping("/decisions")
    @Operation(summary = "路由决策列表（可按 caliberId 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long caliberId) {
        return routerService.listDecisions(caliberId);
    }
}
