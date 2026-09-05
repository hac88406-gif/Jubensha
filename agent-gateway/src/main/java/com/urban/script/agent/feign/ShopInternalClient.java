package com.urban.script.agent.feign;

import com.urban.script.common.InternalApiKeyInterceptor;
import com.urban.script.common.R;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * shop-service 内部接口 Feign 客户端 —— 供 agent-gateway 代理 Python Agent 调用
 *
 * <p>与 order-service 的 ShopClient（内部专用）不同：
 * <ul>
 *   <li>ShopClient：feign/SessionFeignRes（agent-gateway 自己包下的 DTO，复用）</li>
 *   <li>ShopInternalClient：agent-gateway 新增，用自己的 ScriptRes/SessionFeignRes 内部 DTO</li>
 * </ul>
 *
 * <p>注意：Feign 调 shop-service 的完整路径要带 Controller base path（/script、/session）：
 * <pre>
 *   shop-service ScriptController base path = /script → Feign 写 /script/internal/list
 *   shop-service SessionController base path = /session → Feign 写 /session/internal/{id}
 * </pre>
 *
 * @author urban-script-reservation
 */
@FeignClient(
        name = "shop-service",
        configuration = InternalApiKeyInterceptor.class,
        fallbackFactory = ShopInternalClient.ShopInternalFallback.class
)
public interface ShopInternalClient {

    /**
     * 内部剧本列表（shop-service: ScriptController /script/internal/list）
     * <p>shopId 可选：按店铺筛选剧本，支持 AI 回答"XX 店有什么适合 6 人的硬核本"。
     */
    @GetMapping("/script/internal/list")
    R<List<ScriptRes>> listScripts(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer playerCnt);

    /**
     * 内部场次详情（shop-service: SessionController /session/internal/{id}）
     */
    @GetMapping("/session/internal/{id}")
    R<SessionFeignRes> getSession(@PathVariable("id") Long sessionId);

    // ========================================================================
    // DTO（与 shop-service 的 ScriptRes / SessionFeignRes 字段对齐）
    // ========================================================================

    /** 剧本 DTO（只取 agent 需要的字段） */
    @Data
    class ScriptRes {
        private Long id;
        private String name;
        private String scriptType;
        private Integer playerMin;
        private Integer playerMax;
        private Integer duration;
        private java.math.BigDecimal price;
    }

    /** 场次 DTO（与 shop-service 的 SessionFeignRes 字段对齐） */
    @Data
    class SessionFeignRes {
        private Long id;
        private Long scriptId;
        private Long shopId;
        private Long dmId;
        private LocalDate sessionDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private Integer capacity;
        private Integer booked;
        private Integer status;
    }

    // ========================================================================
    // Fallback（shop-service 挂了 / Sentinel 限流时兜底）
    // ========================================================================

    @Slf4j
    @Component
    class ShopInternalFallback implements FallbackFactory<ShopInternalClient> {
        @Override
        public ShopInternalClient create(Throwable cause) {
            log.warn("[ShopInternalClientFallback] shop-service Feign 调用失败: {}",
                    cause != null ? cause.getMessage() : "unknown");

            return new ShopInternalClient() {
                @Override
                public R<List<ScriptRes>> listScripts(Long shopId, String type, Integer playerCnt) {
                    return R.fail(503, "剧本服务暂不可用，请稍后再试");
                }
                @Override
                public R<SessionFeignRes> getSession(Long sessionId) {
                    return R.fail(503, "场次服务暂不可用，请稍后再试");
                }
            };
        }
    }
}
