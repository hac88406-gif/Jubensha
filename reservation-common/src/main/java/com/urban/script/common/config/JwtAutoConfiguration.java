package com.urban.script.common.config;

import com.urban.script.common.JwtUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * JWT 统一配置：从 Nacos urban-shared-config 读取 jwt.secret / jwt.expire-millis，
 * 并调用 {@link JwtUtil#setSecret(String)} / {@link JwtUtil#setExpireMillis(long)} 覆盖默认值。
 *
 * <h3>为什么不使用 {@code @Value}？</h3>
 * reservation-common 是共享 jar，不希望强制编译依赖 {@code spring-cloud-context}
 * （RefreshScopeRefreshedEvent / @RefreshScope 都来自它）。
 * 改用 {@link Environment} 直接取属性，配合"事件类名字符串匹配"实现热刷新，
 * 实现"编译零依赖 Spring Cloud Context，运行时自动生效"。
 *
 * <h3>热刷新工作原理：</h3>
 * Nacos 控制台修改 urban-shared-config 发布后 →
 * Spring Cloud Alibaba Nacos Config 监听长轮询收到变更 →
 * 替换 Environment 中的 PropertySource →
 * 发布 {@code RefreshScopeRefreshedEvent}（完全限定名：
 * {@code org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent}）→
 * 本类作为通用 {@link ApplicationListener<ApplicationEvent>} 收到后比对类名，
 * 匹配成功则重新读 Environment → 调 JwtUtil.setXxx 写入静态字段 →
 * 下一个 JWT 签发/解析请求自然使用新密钥。
 *
 * <h3>密钥一致性保证：</h3>
 * 所有服务（user-service / shop-service / order-service / agent-gateway / reservation-gateway）
 * 都会加载 reservation-common JAR 并从同一个 {@code urban-shared-config} Nacos DataId 读取 jwt.*，
 * 确保签发端（user-service generateToken）与解析端（reservation-gateway validate/getUserId）
 * 始终使用<b>同一份密钥</b>。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Configuration
public class JwtAutoConfiguration implements ApplicationListener<ApplicationEvent> {

    /** Nacos 发布后触发的刷新事件（完全限定名，避免直接引用类导致编译依赖） */
    private static final String REFRESH_EVENT_CLASS =
            "org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent";

    private final Environment env;

    public JwtAutoConfiguration(Environment env) {
        this.env = env;
    }

    /**
     * Bean 创建后立即把 Nacos 配置写入 JwtUtil。
     * 若 Nacos 不可达则使用 JwtUtil 默认值（与之前硬编码相同，保持向后兼容）。
     */
    @PostConstruct
    public void init() {
        applyToJwtUtil("INIT");
    }

    /**
     * 监听 Spring 所有 ApplicationEvent；仅当事件为 RefreshScopeRefreshedEvent
     * （由 Nacos 配置变更触发）时热刷新 JwtUtil。
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (REFRESH_EVENT_CLASS.equals(event.getClass().getName())) {
            applyToJwtUtil("REFRESH(NACOS)");
        }
    }

    private void applyToJwtUtil(String phase) {
        String secret = env.getProperty("jwt.secret");
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            // ⚠️ 不再回退到硬编码默认密钥 —— 那等于把「签发 token 的能力」随仓库一起公开。
            // 各服务 application.yml 均提供 jwt.secret 默认值（可用环境变量 JWT_SECRET 覆盖），
            // 因此走到这里说明配置确实缺失，直接 fail-fast 比运行时大面积 401 更好定位。
            throw new IllegalStateException(String.format(
                    "[JwtAutoConfiguration] %s: jwt.secret 缺失或长度不足 32 字节（当前 %s）。"
                            + "请设置环境变量 JWT_SECRET，或在 Nacos urban-shared-config 中配置 jwt.secret。",
                    phase, secret == null ? "null" : "len=" + secret.length()));
        }

        long expireMillis = 7_200_000L;  // 默认 2 小时
        String expireStr = env.getProperty("jwt.expire-millis");
        if (expireStr != null && !expireStr.isBlank()) {
            try {
                expireMillis = Long.parseLong(expireStr.trim());
            } catch (NumberFormatException ignored) {
                log.warn("[JwtAutoConfiguration] ⚠️ {}: jwt.expire-millis='{}' 无法解析为 long，回退默认 2h",
                        phase, expireStr);
            }
        }

        JwtUtil.setSecret(secret);
        JwtUtil.setExpireMillis(expireMillis);

        log.info("[JwtAutoConfiguration] ✅ {}: JwtUtil 已加载 Nacos 配置 " +
                        "secret.len={}, expireMillis={} (约 {} 小时)",
                phase, secret.length(), expireMillis,
                String.format("%.1f", expireMillis / 3_600_000.0));
    }
}
