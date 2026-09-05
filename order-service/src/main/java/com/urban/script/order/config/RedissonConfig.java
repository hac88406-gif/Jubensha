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
        // address 格式: redis://host:port 或 rediss://host:port (TLS)
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setDatabase(1)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16)
                .setConnectTimeout(5000)
                .setTimeout(3000);
        return Redisson.create(config);
    }
}
