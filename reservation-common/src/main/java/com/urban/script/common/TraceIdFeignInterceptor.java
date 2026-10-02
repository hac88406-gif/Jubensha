package com.urban.script.common;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Feign 链路追踪拦截器：把当前请求线程的 traceId 透传给下游服务
 *
 * <p><b>为什么需要它？</b>
 * {@link com.urban.script.common.filter.TraceIdFilter} 只解决了「入站」——
 * 读取上游 {@code X-Trace-Id} 绑定到 MDC。若出站 Feign 调用不带这个头，
 * 下游服务会在自己这一跳重新生成一个新 traceId，链路到此断开：
 * 表现为「网关日志一个 ID、order-service 日志另一个 ID」，按 traceId 根本串不起来。
 * 本类补齐「出站」这一半，两者合起来才是真正的全链路透传。
 *
 * <p><b>覆盖范围</b>（作为 @Component 注册进主上下文后，Spring Cloud OpenFeign
 * 会把它应用到该服务的全部 FeignClient，无需逐个在 @FeignClient(configuration = ...)
 * 里声明）：
 * <ul>
 *   <li>order-service → shop-service / recommend-service；</li>
 *   <li>agent-gateway → order-service / shop-service / recommend-service；</li>
 *   <li>agent-gateway → python-agent：Python 侧 TraceIdMiddleware 读到同一个头，
 *       之后调 Java internal 接口时再回传 —— 形成「Java → Python → Java」跨语言闭环。</li>
 * </ul>
 *
 * <p><b>为什么加 @ConditionalOnClass？</b>与 {@link InternalApiKeyInterceptor} 同理：
 * reservation-common 被所有服务依赖，但 feign 在 common 的 pom 里是 provided scope，
 * 未引入 Feign 的服务（如 user-service / recommend-service）运行时 classpath 没有
 * RequestInterceptor 接口，缺少该注解会直接启动崩溃。
 *
 * <p><b>线程模型前提</b>：当前 Feign 为同线程同步调用（agent-gateway 已关闭 Sentinel），
 * 因此 {@link TraceIdUtil#get()} 能取到本请求 Filter 中绑定的值。若后续启用
 * 线程隔离型熔断（Hystrix / Sentinel 线程池模式），调用会切到别的线程而取不到 MDC，
 * 届时需改为显式传参或用 TransmittableThreadLocal。
 *
 * @author urban-script-reservation
 */
@Component
@ConditionalOnClass(feign.RequestInterceptor.class)
public class TraceIdFeignInterceptor implements RequestInterceptor {

    /**
     * Feign 请求拦截入口：把本线程的 traceId 追加到出站请求头
     *
     * @param template Feign 请求模板（可往里追加 header）
     */
    @Override
    public void apply(RequestTemplate template) {
        String traceId = TraceIdUtil.get();

        // 非 HTTP 请求线程（未显式自建链路的定时任务等）取不到 traceId，跳过即可，
        // 下游 TraceIdFilter 会自动生成一个，不会出现空值
        if (traceId == null || traceId.isBlank()) {
            return;
        }

        // 已有同名头则不重复追加（主上下文 + @FeignClient configuration 双注册时会各调一次）
        if (template.headers().containsKey(TraceIdUtil.TRACE_ID_HEADER)) {
            return;
        }
        template.header(TraceIdUtil.TRACE_ID_HEADER, traceId);
    }
}