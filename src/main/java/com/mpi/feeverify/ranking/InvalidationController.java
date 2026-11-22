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
@RequestMapping("/api/caliber-invalidations")
@Tag(name = "失效登记册",
        description = "政策修订传播到的月份/药品分类/排行分区精确登记，驱动分区失效与按新口径重建")
public class InvalidationController {

    private final InvalidationService invalidationService;

    public InvalidationController(InvalidationService invalidationService) {
        this.invalidationService = invalidationService;
    }

    @PostMapping
    @Operation(summary = "登记一次政策修订失效：{baseCaliberId, targetCaliberId, policyEffectiveMonth, detail?}。"
            + "精确计算受影响分类/药品/月份/分区，并把生效月起的旧口径分区置 INVALIDATED")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return invalidationService.register(
                ((Number) body.get("baseCaliberId")).longValue(),
                ((Number) body.get("targetCaliberId")).longValue(),
                (String) body.get("policyEffectiveMonth"),
                (String) body.get("detail"));
    }

    @PostMapping("/{id}/rebuild")
    @Operation(summary = "按登记册以新口径重建受影响分区（HIGH 优先级），完成后登记册置 REBUILT/PARTIAL_REBUILT")
    public Map<String, Object> rebuild(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return invalidationService.rebuild(id, body == null ? "system" : (String) body.getOrDefault("requestedBy", "system"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "失效登记详情（含受影响分类、药品、月份、分区清单）")
    public Map<String, Object> get(@PathVariable long id) {
        return invalidationService.get(id);
    }

    @GetMapping
    @Operation(summary = "失效登记列表（可按 status 过滤）")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return invalidationService.list(status);
    }
}
