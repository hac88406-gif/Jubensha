package com.urban.script.recommend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

/**
 * 推荐微服务启动类
 * <p>
 * 基于 Neo4j 知识图谱实现个性化推荐：
 * <ul>
 *   <li>内容召回：同类型/同标签/同作者剧本</li>
 *   <li>协同过滤：相似玩家的共同偏好</li>
 *   <li>热门兜底：按被预订次数排序</li>
 * </ul>
 *
 * <p>scanBasePackages 显式包含 {@code com.urban.script.common}（与其他 5 个服务对齐）：
 * GlobalExceptionHandler / TraceIdFilter / SnowflakeAutoConfiguration / JwtAutoConfiguration
 * 等公共组件位于 reservation-common 的兄弟包，Spring Boot 默认扫不到 ——
 * 漏扫会导致本服务日志没有 traceId、异常返回格式与全局 R 格式不一致。
 */
@SpringBootApplication(scanBasePackages = {"com.urban.script.recommend", "com.urban.script.common"})
@EnableNeo4jRepositories
public class RecommendServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecommendServiceApplication.class, args);
    }
}
