package com.mpi.feeverify.metric;

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
@RequestMapping("/api/metric-calibers")
@Tag(name = "指标口径版本库", description = "排行口径的分类/剔除/排序/并列规则整体版本化与四要素差异")
public class MetricCaliberController {

    private final MetricCaliberService caliberService;

    public MetricCaliberController(MetricCaliberService caliberService) {
        this.caliberService = caliberService;
    }

    @PostMapping
    @Operation(summary = "创建口径草稿（DRAFT）。body: categoryRules/exclusionRules/sortRules/tieRules/scopeRules")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return caliberService.createDraft(body);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "生效口径：同族旧版本退役，返回与上一生效版本的四要素差异")
    public Map<String, Object> activate(@PathVariable long id) {
        return caliberService.activate(id);
    }

    @GetMapping
    @Operation(summary = "口径版本列表（默认 fee-ranking 族）")
    public List<Map<String, Object>> list(@RequestParam(required = false) String family) {
        return caliberService.list(family);
    }

    @GetMapping("/{id}")
    @Operation(summary = "口径版本详情（含四类规则全文）")
    public Map<String, Object> get(@PathVariable long id) {
        return caliberService.get(id);
    }

    @GetMapping("/active")
    @Operation(summary = "当前生效口径")
    public Map<String, Object> active(@RequestParam(required = false) String family) {
        return caliberService.active(family == null ? MetricCaliberService.FAMILY : family);
    }

    @GetMapping("/{baseId}/diff/{targetId}")
    @Operation(summary = "两个口径版本的四要素差异（CATEGORY/EXCLUSION/SORT/TIE）")
    public Map<String, Object> diff(@PathVariable long baseId, @PathVariable long targetId) {
        return caliberService.diff(baseId, targetId);
    }
}
