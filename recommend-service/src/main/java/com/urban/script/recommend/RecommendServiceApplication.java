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
 */
@SpringBootApplication
@EnableNeo4jRepositories
public class RecommendServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecommendServiceApplication.class, args);
    }
}
