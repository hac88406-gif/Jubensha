package com.urban.script.gateway.filter;

import com.urban.script.common.TraceIdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * TraceId 链路追踪全局过滤器（网关入口）
 *
 * <p>职责：
 * <ol>
 *   <li>整条调用链的 traceId 源头：读取客户端 {@code X-Trace-Id}（Postman/压测脚本可自定义，
 *       便于指定链路标记），没有则网关生成一个；</li>
 *   <li>透传下游：把 traceId 追加到转发请求头 {@code X-Trace-Id} → 下游 4 个 Servlet 服务的
 *       {@code TraceIdFilter} 读到后沿用，各服务日志用同一个 traceId；</li>
 *   <li>回写响应头 {@code X-Trace-Id}，前端/测试脚本能从响应里拿到本次链路 ID；</li>
 *   <li>网关自身日志也打上 MDC 方便定位（WebFlux 响应式线程切换，MDC 不保证贯穿异步回调，
 *       这里只保证 filter 内/下游调用段的日志可用，不追求 reactive 全链路）。</li>
 * </ol>
 *
 * <p><b>执行顺序</b>：{@link #getOrder()} = -200，早于 JwtAuthFilter(-100)，
 * 保证鉴权失败被直接拦截时，响应也带 traceId，Fail 日志有 ID 可查。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // ① 读客户端自定义 traceId 或生成
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdUtil.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceIdUtil.generate();
        }

        // ② 透传下游 + 回写响应头（不管下游是否处理，都让调用方拿到）
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TraceIdUtil.TRACE_ID_HEADER, traceId)
                .build();
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(TraceIdUtil.TRACE_ID_HEADER, traceId);

        // ③ 网关自身日志绑定 MDC（当前线程段的 filter 日志可带上 traceId）
        TraceIdUtil.set(traceId);
        log.debug("[TraceIdGlobalFilter] 透传 traceId={}, path={}", traceId, exchange.getRequest().getPath());
        try {
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } finally {
            TraceIdUtil.clear();
        }
    }

    /**
     * -200：必须早于 JwtAuthFilter(-100)，让 traceId 对整条网关处理链可见
     */
    @Override
    public int getOrder() {
        return -200;
    }
}