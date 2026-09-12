package com.urban.script.order.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置
 *
 * <p>显式配置单节点连接，与 application.yml 的 {@code redisson.single-server-config} 呼应。
 * redisson-spring-boot-starter 也会自动创建 {@code RedissonClient}，
 * 这里用 {@link ConditionalOnMissingBean} 避免重复。
 *
 * @author urban-script-reservation
 */
@Configuration
public class RedissonConfig {

    @Bean
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient() {
        Config config = new Config();
        // Redisson 延迟初始化：Spring Boot 启动时不主动连接 Redis，
        // 等第一次请求进来才建连接。避免 Redis 挂了直接拉崩应用启动。
        config.setLazyInitialization(true);

        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setDatabase(1)
                // 启动时不要强制建立最小空闲连接，否则连接失败会阻断启动
                .setConnectionMinimumIdleSize(0)
                .setConnectionPoolSize(16)
                // 启动时就把 Redis 挂了 → 让 Redisson 快速失败（不 hang），
                // 但因为 lazyInitialization=true，Bean 创建阶段根本不会触发连接，
                // 真正触发连接是在第一次 tryLock / decrStock 时。
                .setConnectTimeout(1000)
                .setTimeout(3000)
                // 启动时若 Redis 不可用，不要让 Redisson 抛异常阻断 Spring Boot 上下文初始化
                .setRetryAttempts(1)
                .setRetryInterval(100);
        return Redisson.create(config);
    }
}
