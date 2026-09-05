package com.urban.script.common;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Feign 内部调用鉴权拦截器
 *
 * <p>所有通过 Feign Client 发起的微服务间调用，都会自动带上
 * {@code X-Internal-Api-Key} 请求头，由下游网关 / 服务校验后放行。
 *
 * <p>密钥来源：Nacos 配置 {@code urban.internal-api-key}（通过 Environment 每次实时取值）。
 * 为什么用 Environment 而不是 {@code @Value + @RefreshScope}？
 * <ol>
 *   <li>Nacos 发布配置后，Spring Cloud 会把新配置作为新的 PropertySource 加入 Environment，
 *       {@code getProperty} 自然读到最新值，不需要额外的刷新机制。</li>
 *   <li>reservation-common 是共享 jar，不希望强制引入 {@code spring-cloud-context} 依赖
 *       （这样即便是"纯 user-service 这种不连 Feign 的轻服务"也不会因缺 @RefreshScope 类崩）。</li>
 * </ol>
 *
 * <p>为什么加 @ConditionalOnClass？
 * reservation-common 会被所有微服务依赖，但 {@code spring-cloud-starter-openfeign}
 * 在 common 里是 {@code provided} scope —— 只有需要 Feign 的服务（gateway、shop-service 等）
 * 才会真的把 feign 类带到运行时 classpath。user-service 这种"纯对内接口"服务不会带 Feign。
 * 没有 @ConditionalOnClass 的话，Spring 扫描 common 时遇到 {@code implements feign.RequestInterceptor}
 * 会因为类加载不到 feign 接口而直接崩。
 *
 * @author urban-script-reservation
 */
@Component
@ConditionalOnClass(feign.RequestInterceptor.class)
public class InternalApiKeyInterceptor implements RequestInterceptor {

    private final Environment env;

    public InternalApiKeyInterceptor(Environment env) {
        this.env = env;
    }

    /**
     * Feign 请求拦截入口：每次都从 Environment 读最新 apiKey，
     * 这样 Nacos 改 urban.internal-api-key 不用重启也能生效。
     */
    @Override
    public void apply(RequestTemplate template) {
        // 与 InternalApiKeyFilter 的 fallback 完全一致，确保"Nacos 不可达"时服务间调用仍兼容。
        // 正常情况下从 Nacos urban-shared-config 的 urban.internal-api-key 取。
        String apiKey = env.getProperty("urban.internal-api-key", "urban-internal-api-key-dev-fallback");
        template.header("X-Internal-Api-Key", apiKey);
    }
}
