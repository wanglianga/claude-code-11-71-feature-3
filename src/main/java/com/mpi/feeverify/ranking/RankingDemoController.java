package com.mpi.feeverify.ranking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ranking-demo")
@Tag(name = "排行模块演示",
        description = "一键跑通：频率路由 / 预计算vs现扫 / 权限过滤 / 构建延迟 / 局部分类调整失效 / 并列规则审批")
public class RankingDemoController {

    private final RankingDemoService rankingDemoService;

    public RankingDemoController(RankingDemoService rankingDemoService) {
        this.rankingDemoService = rankingDemoService;
    }

    @PostMapping("/run")
    @Operation(summary = "运行排行模块全部演示场景并返回结构化证据（所有对象真实落库，可逐条复查）")
    public Map<String, Object> run() {
        return rankingDemoService.run();
    }
}
