package com.urban.script.common;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 链路追踪工具
 *
 * <p>跨服务链路追踪的"溯源码"：一次用户请求从 Gateway 进入后生成一个全局唯一的
 * traceId，通过 HTTP 头透传给下游服务，各服务日志统一用 {@code %X{traceId}} 输出，
 * 排障时按 traceId 聚合即可还原整条调用链（Gateway → order → shop → ... ）。
 *
 * <p>实现细节：
 * <ul>
 *   <li>双重绑定：{@link #HOLDER}（ThreadLocal，跨模块取用）+ {@link MDC}（日志框架
 *       pattern 渲染用），两者同时 sync，一方失效另一方兜底；</li>
 *   <li>线程安全：TraceId 绑定在当前处理线程，Servlet Filter 的 finally 中必须调用
 *       {@link #clear()}，否则线程池复用线程会造成 traceId 串流（A 请求日志挂上 B 的 ID）；</li>
 *   <li>MQ 消费者 / 定时任务没有入站 HTTP 上下文，可在业务入口显式
 *       {@code TraceIdUtil.set(TraceIdUtil.generate())} 自建链路。</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
public final class TraceIdUtil {

    /** 跨服务透传的 HTTP 请求头名（Gateway 生成/透传，下游过滤器读取） */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 接入 logback pattern 的 MDC key：logging.pattern.console 里用 %X{traceId} 展示 */
    public static final String MDC_KEY = "traceId";

    /**
     * 线程级存储：Servlet 线程模型下一次请求固定由一个线程处理，
     * ThreadLocal 保证并发请求互不干扰。
     */
    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdUtil() {
        // 工具类禁止实例化
    }

    /**
     * 生成全局唯一 traceId：UUID 去连字符，32 位十六进制字符串（如 a1b2c3d4...）
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 取出当前线程的 traceId（没有则为 null）
     */
    public static String get() {
        return HOLDER.get();
    }

    /**
     * 绑定 traceId：同时写入 ThreadLocal 与 MDC（日志 pattern 立即生效）
     *
     * @param traceId 可传 null/blank，内部自动生成兜底，避免下游把 traceId 渲染成 "null"
     */
    public static void set(String traceId) {
        String id = (traceId == null || traceId.isBlank()) ? generate() : traceId;
        HOLDER.set(id);
        MDC.put(MDC_KEY, id);
    }

    /**
     * 清空绑定：必须在 finally 中调用（配合线程池，防 traceId 串流）
     */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }
}