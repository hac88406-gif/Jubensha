package com.urban.script.recommend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * 推荐服务 —— 基于 Neo4j 知识图谱
 * <p>
 * 三路召回 + 综合排序：
 * <ol>
 *   <li>内容召回：同标签/同作者剧本</li>
 *   <li>协同过滤：相似玩家偏好</li>
 *   <li>热门兜底：按被预订次数</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private final Neo4jClient neo4jClient;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "rec:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 个性化推荐（登录用户）
     */
    public List<Map<String, Object>> recommend(Long userId, int limit) {
        // 缓存命中直接返回
        String cacheKey = CACHE_PREFIX + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("[recommend] 缓存命中 userId={}", userId);
            return parseCached(cached);
        }

        List<Map<String, Object>> result;
        try {
            // 1) 内容召回：你玩过的剧本的同标签/同作者 → 你没玩过的
            //    注意 Neo4j 5.x 语法：NOT EXISTS {} 而非 WHERE NOT (u)-[:PLAYED]->(rec)
            List<Map<String, Object>> content = neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(s:Script)
                MATCH (s)-[:HAS_TAG|WRITTEN_BY]->()<-[:HAS_TAG|WRITTEN_BY]-(rec:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(rec) }
                RETURN rec.scriptId AS scriptId, rec.name AS name, rec.image AS image,
                       rec.mark AS mark, rec.scriptType AS scriptType,
                       count(*) AS score
                ORDER BY score DESC
                LIMIT 30
                """)
                    .bind(userId).to("uid")
                    .fetch().all()
                    .stream().map(m -> (Map<String, Object>) new HashMap<>(m))  // Neo4j 返回不可变 Map，转可变后才能改 score
                    .toList();

            // 1.5) 内容召回-人物联动：玩过的剧本里的角色 → 跨本同名角色所在剧本（人物线索）
            //      SAME_NAME 由图谱增厚重建生成，一条线索 score +1
            List<Map<String, Object>> charContent = neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(:Script)-[:HAS_CHARACTER]->(c:Character)
                MATCH (c)-[:SAME_NAME]->(:Character)<-[:HAS_CHARACTER]-(rec:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(rec) }
                WITH rec, count(DISTINCT c) AS score
                RETURN rec.scriptId AS scriptId, rec.name AS name, rec.image AS image,
                       rec.mark AS mark, rec.scriptType AS scriptType,
                       score
                ORDER BY score DESC
                LIMIT 30
                """)
                    .bind(userId).to("uid")
                    .fetch().all()
                    .stream().map(m -> (Map<String, Object>) new HashMap<>(m))
                    .toList();

            // 2) 协同过滤：和你玩过相同剧本的人，他们玩过但你没玩过的
            List<Map<String, Object>> cf = neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(s:Script)<-[:PLAYED]-(other:User)
                WHERE other.userId <> $uid
                WITH other, count(DISTINCT s) AS sim ORDER BY sim DESC LIMIT 20
                MATCH (other)-[:PLAYED]->(rec:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(rec) }
                RETURN rec.scriptId AS scriptId, rec.name AS name, rec.image AS image,
                       rec.mark AS mark, rec.scriptType AS scriptType,
                       count(*) AS score
                ORDER BY score DESC
                LIMIT 30
                """)
                    .bind(userId).to("uid")
                    .fetch().all()
                    .stream().map(m -> (Map<String, Object>) new HashMap<>(m))
                    .toList();

            // 合并去重 + 加权排序
            Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
            for (Map<String, Object> r : content) {
                Long id = (Long) r.get("scriptId");
                r.put("score", ((Number) r.get("score")).doubleValue() * 0.5);
                merged.put(id, r);
            }
            // 人物联动并入内容召回（权重稍低，作为辅助线索）
            for (Map<String, Object> r : charContent) {
                Long id = (Long) r.get("scriptId");
                double charScore = ((Number) r.get("score")).doubleValue() * 0.3;
                if (merged.containsKey(id)) {
                    double cur = ((Number) merged.get(id).get("score")).doubleValue();
                    merged.get(id).put("score", cur + charScore);
                } else {
                    r.put("score", charScore);
                    merged.put(id, r);
                }
            }
            for (Map<String, Object> r : cf) {
                Long id = (Long) r.get("scriptId");
                if (merged.containsKey(id)) {
                    double cur = ((Number) merged.get(id).get("score")).doubleValue();
                    merged.get(id).put("score", cur + ((Number) r.get("score")).doubleValue() * 0.4);
                } else {
                    r.put("score", ((Number) r.get("score")).doubleValue() * 0.4);
                    merged.put(id, r);
                }
            }

            result = new ArrayList<>(merged.values().stream()
                    .sorted((a, b) -> Double.compare(
                            ((Number) b.get("score")).doubleValue(),
                            ((Number) a.get("score")).doubleValue()))
                    .limit(limit)
                    .toList());

            // 内容召回不足时，用热门补齐（排除已玩过的，避免把玩过的再推回来）
            if (result.size() < limit) {
                // 查询该用户已玩过的剧本 id 集合
                Set<Long> playedIds = new HashSet<>();
                try {
                    playedIds = neo4jClient.query("""
                            MATCH (:User {userId: $uid})-[:PLAYED]->(s:Script)
                            RETURN collect(s.scriptId) AS ids
                            """)
                            .bind(userId).to("uid")
                            .fetch().one()
                            .map(m -> new HashSet<>((List<Long>) m.get("ids")))
                            .orElseGet(HashSet::new);
                } catch (Exception ex) {
                    log.warn("[recommend] 查询已玩剧本失败 userId={} err={}", userId, ex.getMessage());
                }

                List<Map<String, Object>> hot = hot(null, limit * 2);
                Set<Long> exist = new HashSet<>();
                result.forEach(r -> exist.add((Long) r.get("scriptId")));
                for (Map<String, Object> h : hot) {
                    Long id = (Long) h.get("scriptId");
                    if (!exist.contains(id) && !playedIds.contains(id)) {
                        result.add(h);
                        exist.add(id);
                        if (result.size() >= limit) break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[recommend] Neo4j 查询失败，降级热门 userId={} err={}", userId, e.getMessage());
            result = hot(null, limit);
        }

        // 写缓存
        redisTemplate.opsForValue().set(cacheKey, toJson(result), CACHE_TTL);
        return result;
    }

    /**
     * 热门推荐（可按类型筛选）
     * <p>
     * 优先按 PLAYED 次数（被体验数）排序；从没被玩过的剧本按 mark 评分兜底排序，
     * 保证冷启动 / 数据稀疏时热门区也有内容可看。
     */
    public List<Map<String, Object>> hot(String type, int limit) {
        String cypher;
        if (type != null && !type.isBlank()) {
            cypher = """
                MATCH (s:Script {scriptType: $type})
                OPTIONAL MATCH (:User)-[p:PLAYED]->(s)
                RETURN s.scriptId AS scriptId, s.name AS name, s.image AS image,
                       s.mark AS mark, s.scriptType AS scriptType,
                       COALESCE(sum(p.count), 0) AS score
                ORDER BY score DESC, COALESCE(s.mark, 0) DESC
                LIMIT $lim
                """;
        } else {
            cypher = """
                MATCH (s:Script)
                OPTIONAL MATCH (:User)-[p:PLAYED]->(s)
                RETURN s.scriptId AS scriptId, s.name AS name, s.image AS image,
                       s.mark AS mark, s.scriptType AS scriptType,
                       COALESCE(sum(p.count), 0) AS score
                ORDER BY score DESC, COALESCE(s.mark, 0) DESC
                LIMIT $lim
                """;
        }
        var q = neo4jClient.query(cypher).bind(limit).to("lim");
        if (type != null && !type.isBlank()) q = q.bind(type).to("type");
        return q.fetch().all().stream().toList();
    }

    /**
     * 相似剧本（猜你喜欢）
     * <p>主信号：RELATED_TO 关系（共享细标签+同类型累加的 weight）；
     * 辅信号：同作者（WRITTEN_BY 共享，每本 +1）。合并去重后按分数排序。
     */
    public List<Map<String, Object>> similar(Long scriptId, int limit) {
        // 1) RELATED_TO 无向 weight 加权（RELATED_TO 由 MERGE (s)-[r]-(o) 创建，方向不定，须无向匹配）
        List<Map<String, Object>> rel = neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[r:RELATED_TO]-(o:Script)
                WHERE o.scriptId <> $sid
                RETURN o.scriptId AS scriptId, o.name AS name, o.image AS image,
                       o.mark AS mark, o.scriptType AS scriptType,
                       r.weight AS score
                ORDER BY score DESC
                """)
                .bind(scriptId).to("sid")
                .fetch().all()
                .stream().map(m -> (Map<String, Object>) new HashMap<>(m))
                .toList();

        // 2) 同作者剧本（RELATED_TO 之外的补充召回）
        List<Map<String, Object>> sameAuthor = neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[:WRITTEN_BY]->(:Author)<-[:WRITTEN_BY]-(o:Script)
                WHERE o.scriptId <> $sid
                RETURN o.scriptId AS scriptId, o.name AS name, o.image AS image,
                       o.mark AS mark, o.scriptType AS scriptType,
                       1 AS score
                """)
                .bind(scriptId).to("sid")
                .fetch().all()
                .stream().map(m -> (Map<String, Object>) new HashMap<>(m))
                .toList();

        // 3) 合并：RELATED_TO 命中保留其 weight；同作者但未命中 RELATED_TO 的 +1 兜底
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rel) {
            Long id = (Long) r.get("scriptId");
            if (id != null) merged.put(id, r);
        }
        for (Map<String, Object> r : sameAuthor) {
            Long id = (Long) r.get("scriptId");
            if (id == null) continue;
            Map<String, Object> exist = merged.get(id);
            if (exist != null) {
                // 既共享标签/类型又同作者，权重再 +1
                exist.put("score", ((Number) exist.get("score")).doubleValue() + 1);
            } else {
                merged.put(id, r);
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Double.compare(
                        ((Number) b.get("score")).doubleValue(),
                        ((Number) a.get("score")).doubleValue()))
                .limit(limit)
                .toList();
    }

    /**
     * 推荐解释图（P2 可视化 · 推荐解释）
     * <p>返回「用户玩过的剧本 → 推荐剧本」的关联链，前端据此画出"为什么推荐"。
     * 每条边带 reason（同标签 / 同类型 / 同作者 / 同名角色联动），
     * weight 为该维度命中数（多标签/多类型会累加），用于线宽与排序。
     * <p>与 recommend() 的区别：本方法只返回"有解释路径"的剧本，
     * 并把关联原因暴露出来；纯热门兜底的剧本没有路径，不进入本图。
     *
     * @param userId 登录用户
     * @param limit  推荐剧本节点上限（默认 10，封顶 20）
     * @return {"played": [{scriptId,name,scriptType,mark,image}],
     *         "recs":   [{scriptId,name,scriptType,mark,image,weight}],
     *         "links":  [{source,target,reason,weight}]}
     *         —— source/target 均为剧本 scriptId（source 为玩过的剧本）
     */
    public Map<String, Object> graphExplain(Long userId, int limit) {
        int lim = (limit <= 0) ? 10 : Math.min(limit, 20);
        Map<String, Object> result = new LinkedHashMap<>();

        // 1) 用户玩过的剧本节点（图左侧，作为"起点"）
        List<Map<String, Object>> played = neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(s:Script)
                RETURN s.scriptId AS scriptId, s.name AS name, s.scriptType AS scriptType,
                       s.mark AS mark, s.image AS image
                ORDER BY s.scriptId
                """)
                .bind(userId).to("uid")
                .fetch().all().stream()
                .map(m -> (Map<String, Object>) new HashMap<>(m))
                .toList();
        result.put("played", played);
        if (played.isEmpty()) {
            // 没玩过任何剧本 → 没有推荐路径可解释，空图
            result.put("recs", List.of());
            result.put("links", List.of());
            return result;
        }

        // 2) 四路关联查询：同标签 / 同类型 / 同作者 / 同名角色联动
        //    每条返回 (source=玩过的剧本, target=推荐剧本, reason, weight)
        List<Map<String, Object>> relRows = new ArrayList<>();
        relRows.addAll(neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(p:Script)
                MATCH (p)-[:HAS_TAG]->(t:Tag)<-[:HAS_TAG]-(r:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(r) }
                RETURN p.scriptId AS source, r.scriptId AS target,
                       '同标签' AS reason, count(DISTINCT t) AS weight
                """).bind(userId).to("uid").fetch().all().stream()
                .map(m -> (Map<String, Object>) new HashMap<>(m)).toList());
        relRows.addAll(neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(p:Script)
                MATCH (p)-[:HAS_TYPE]->(ty:Type)<-[:HAS_TYPE]-(r:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(r) }
                RETURN p.scriptId AS source, r.scriptId AS target,
                       '同类型' AS reason, count(DISTINCT ty) AS weight
                """).bind(userId).to("uid").fetch().all().stream()
                .map(m -> (Map<String, Object>) new HashMap<>(m)).toList());
        relRows.addAll(neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(p:Script)
                MATCH (p)-[:WRITTEN_BY]->(:Author)<-[:WRITTEN_BY]-(r:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(r) }
                RETURN p.scriptId AS source, r.scriptId AS target,
                       '同作者' AS reason, count(*) AS weight
                """).bind(userId).to("uid").fetch().all().stream()
                .map(m -> (Map<String, Object>) new HashMap<>(m)).toList());
        relRows.addAll(neo4jClient.query("""
                MATCH (u:User {userId: $uid})-[:PLAYED]->(p:Script)-[:HAS_CHARACTER]->(c:Character)
                MATCH (c)-[:SAME_NAME]->(:Character)<-[:HAS_CHARACTER]-(r:Script)
                WHERE NOT EXISTS { (u)-[:PLAYED]->(r) }
                RETURN p.scriptId AS source, r.scriptId AS target,
                       '同名角色' AS reason, count(DISTINCT c) AS weight
                """).bind(userId).to("uid").fetch().all().stream()
                .map(m -> (Map<String, Object>) new HashMap<>(m)).toList());

        // 3) 按 target 聚合推荐剧本（weight 累加），并收集剧本元信息
        Map<Long, Map<String, Object>> recMap = new LinkedHashMap<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seenLink = new HashSet<>(); // source-target-reason 去重
        for (Map<String, Object> row : relRows) {
            Object rawFrom = row.get("source");
            Object rawTo = row.get("target");
            if (rawFrom == null || rawTo == null) continue;
            long from = ((Number) rawFrom).longValue();
            long to = ((Number) rawTo).longValue();
            String reason = String.valueOf(row.get("reason"));
            int w = row.get("weight") == null ? 0 : ((Number) row.get("weight")).intValue();

            String linkKey = from + "-" + to + "-" + reason;
            if (seenLink.add(linkKey)) {
                Map<String, Object> l = new HashMap<>();
                l.put("source", from);
                l.put("target", to);
                l.put("reason", reason);
                l.put("weight", w);
                links.add(l);
            }

            Map<String, Object> rec = recMap.get(to);
            if (rec == null) {
                rec = new HashMap<>();
                rec.put("scriptId", to);
                recMap.put(to, rec);
            }
            rec.merge("weight", w, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
        }

        // 4) 取推荐剧本元信息（名称/类型/评分/封面），仅保留 top-N
        List<Long> ids = recMap.entrySet().stream()
                .sorted((a, b) -> Integer.compare(
                        ((Number) b.getValue().get("weight")).intValue(),
                        ((Number) a.getValue().get("weight")).intValue()))
                .limit(lim)
                .map(Map.Entry::getKey)
                .toList();
        List<Map<String, Object>> recs = new ArrayList<>();
        if (!ids.isEmpty()) {
            List<Map<String, Object>> meta = neo4jClient.query("""
                    MATCH (s:Script)
                    WHERE s.scriptId IN $ids
                    RETURN s.scriptId AS scriptId, s.name AS name, s.scriptType AS scriptType,
                           s.mark AS mark, s.image AS image
                    """)
                    .bind(ids).to("ids")
                    .fetch().all().stream()
                    .map(m -> (Map<String, Object>) new HashMap<>(m))
                    .toList();
            Map<Long, Map<String, Object>> metaById = new HashMap<>();
            for (Map<String, Object> m : meta) {
                metaById.put(((Number) m.get("scriptId")).longValue(), m);
            }
            for (Long id : ids) {
                Map<String, Object> rec = metaById.get(id);
                if (rec == null) continue;
                rec.put("weight", recMap.get(id).get("weight"));
                recs.add(rec);
            }
        }

        // 5) 只保留指向入选推荐剧本的边
        Set<Long> keepIds = new HashSet<>(ids);
        links.removeIf(l -> !keepIds.contains(((Number) l.get("target")).longValue()));

        result.put("recs", recs);
        result.put("links", links);
        return result;
    }

    // ---------- 缓存序列化（简单 JSON，避免引入 Jackson 依赖）----------
    private String toJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, Object> m = list.get(i);
            sb.append("{");
            sb.append("\"scriptId\":").append(m.get("scriptId")).append(",");
            sb.append("\"name\":\"").append(String.valueOf(m.get("name")).replace("\"", "\\\"")).append("\",");
            sb.append("\"image\":\"").append(String.valueOf(m.get("image")).replace("\"", "\\\"")).append("\",");
            sb.append("\"mark\":").append(m.get("mark")).append(",");
            sb.append("\"scriptType\":\"").append(String.valueOf(m.get("scriptType"))).append("\"");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<Map<String, Object>> parseCached(String json) {
        // 简单解析：推荐缓存主要用于快速返回，结构固定
        List<Map<String, Object>> list = new ArrayList<>();
        if (json == null || json.length() < 2) return list;
        String body = json.substring(1, json.length() - 1);
        if (body.isBlank()) return list;
        for (String item : body.split("\\},\\{")) {
            item = item.replace("{", "").replace("}", "");
            Map<String, Object> m = new HashMap<>();
            for (String kv : item.split(",")) {
                String[] p = kv.split(":", 2);
                if (p.length != 2) continue;
                String k = p[0].replace("\"", "").trim();
                String v = p[1].replace("\"", "").trim();
                if ("scriptId".equals(k)) m.put(k, Long.parseLong(v));
                // mark 可能为 null（无评分剧本），此时跳过该字段而非强制解析
                else if ("mark".equals(k) && !"null".equalsIgnoreCase(v)) {
                    try {
                        m.put(k, Double.parseDouble(v));
                    } catch (NumberFormatException ex) {
                        log.debug("[recommend] 解析缓存 mark 失败，跳过: {}", v);
                    }
                }
                // image/mark 等字段为 null 时同样跳过，避免把字符串 "null" 当真实值
                // （否则前端会渲染出 "评分 null"、破图 <img src="null"> 等脏展示）
                else if (!"null".equalsIgnoreCase(v)) m.put(k, v);
            }
            if (!m.isEmpty()) list.add(m);
        }
        return list;
    }
}
