package com.urban.script.common.config;

import com.urban.script.common.SnowflakeIdGenerator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Snowflake workerId 统一配置：从 application.yml / Nacos 读取
 * {@code snowflake.worker-id}，启动时调用
 * {@link SnowflakeIdGenerator#overrideWorkerId(long)} 注入。
 *
 * <h3>为什么不用 @Value / @RefreshScope？</h3>
 * {@link SnowflakeIdGenerator} 是静态单例（非 Spring Bean），无法直接注入。
 * 参考 {@code JwtAutoConfiguration} 的做法：通过 {@link Environment} 取属性后
 * 写入静态字段。workerId 在实例创建后不可变，因此只支持启动期一次性注入，
 * 不支持 Nacos 运行时热刷新（ID 生成器换 workerId 必须重启，否则序列错乱）。
 *
 * <h3>多实例部署约定：</h3>
 * 同一台机器跑多个 JVM（或 order-service 扩容多实例）时，
 * 必须为每个实例显式配置不同的 {@code snowflake.worker-id}（0~31），
 * 例如 Nacos 中 order-service-prod.yaml 写 snowflake.worker-id: 2。
 * 未配置时兜底策略见 {@link SnowflakeIdGenerator} 类注释（IP hash）。
 *
 * <h3>生效条件：</h3>
 * 各业务服务的 {@code @SpringBootApplication(scanBasePackages = {"com.urban.script.xxx",
 * "com.urban.script.common"})} 已覆盖本包（与 JwtAutoConfiguration 一致）。
 *
 * @author urban-script-reservation
 */
@Slf4j
@Configuration
public class SnowflakeAutoConfiguration {

    private final Environment env;

    public SnowflakeAutoConfiguration(Environment env) {
        this.env = env;
    }

    /**
     * Bean 创建后立即把配置写入 SnowflakeIdGenerator（早于任何业务请求，
     * 单例首次 getInstance() 时必然已注入）。
     */
    @PostConstruct
    public void init() {
        String workerIdStr = env.getProperty("snowflake.worker-id");
        if (workerIdStr == null || workerIdStr.isBlank()) {
            // 未配置属正常场景：走系统属性 / 环境变量 / IP hash 兜底链
            log.info("[SnowflakeAutoConfiguration] 未配置 snowflake.worker-id，"
                    + "按 系统属性 > 环境变量 > IP hash 兜底链解析");
            return;
        }

        try {
            long workerId = Long.parseLong(workerIdStr.trim());
            SnowflakeIdGenerator.overrideWorkerId(workerId);
            log.info("[SnowflakeAutoConfiguration] ✅ 已从配置注入 snowflake.worker-id={}", workerId);
        } catch (NumberFormatException e) {
            // 配置错误不阻断启动，但必须大声告警，避免静默降级到兜底链后多实例撞 ID
            log.warn("[SnowflakeAutoConfiguration] ⚠️ snowflake.worker-id='{}' 无法解析为 long，"
                    + "将走兜底链（系统属性 > 环境变量 > IP hash）", workerIdStr);
        } catch (IllegalArgumentException e) {
            // 超出 0~31 范围：配置硬错误，直接抛出让启动失败，强制修正
            throw new IllegalStateException("snowflake.worker-id 配置非法: " + e.getMessage(), e);
        }
    }
}
