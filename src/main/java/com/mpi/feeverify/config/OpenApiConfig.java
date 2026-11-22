package com.mpi.feeverify.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI feeVerifyOpenApi() {
        return new OpenAPI().info(new Info()
                .title("省级医保费用查询改写验证服务")
                .version("1.0.0")
                .description("""
                        面向稽核、病种付费与预算团队的费用明细查询改写验证平台。
                        模块一（查询改写）：查询登记 → 计划采集 → 规则诊断 → 候选生成 →
                        影子执行 → 结果裁判 → 发布门禁 → 路由/回退。
                        模块二（月度费用排行）：指标口径版本库 → 排行榜构建器 → 查询路由决策器 →
                        影子排行比较器/发布审批 → 失效登记册，
                        封账月报支持查询时扫描与口径+快照绑定的预计算两条路径。
                        验证方式：冻结快照的只读副本（snap_* 表 + 校验和），
                        验证结论仅代表该快照，数据更新后需按风险重验。"""));
    }
}
