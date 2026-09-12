package com.urban.script.recommend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱同步服务 —— 把 MySQL 的剧本/订单数据同步到 Neo4j
 * <p>图谱结构（增厚后）：
 * <pre>
 *  (Script)-[:WRITTEN_BY]->(Author)        作者
 *  (Script)-[:HAS_TAG]->(Tag)              细标签(推理/解谜/还原...)
 *  (Script)-[:HAS_TYPE]->(Type)            剧本类型(硬核/情感/欢乐/机制)
 *  (Script)-[:HAS_CHARACTER]->(Character)  角色(scriptId+name 唯一)
 *  (Character)-[:CO_CHARACTER]->(Character)同本互为角色(单向按 charId 排序)
 *  (Character)-[:SAME_NAME]->(Character)    跨本同名角色(排除字母占位名, 人物联动)
 *  (Script)-[:RELATED_TO {weight}]->(Script) 剧本相关(共享标签/同类型, weight 累加)
 *  (User)-[:PLAYED {count}]->(Script)       游玩记录(协同过滤)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphSyncService {

    private final Neo4jClient neo4jClient;

    /**
     * 同步剧本节点（含类型/标签/作者/角色关系）
     * MERGE 保证幂等，重复调用不会产生重复节点
     *
     * @param characters 角色列表 [{name,gender,age,desc,image}]，可为 null/空
     */
    public void syncScript(Long scriptId, String name, String scriptType,
                           Integer playerMin, Integer playerMax,
                           java.math.BigDecimal price, java.math.BigDecimal mark,
                           String image, String author, List<String> tags,
                           List<Map<String, Object>> characters) {
        // 1) 剧本节点（MERGE 幂等）
        neo4jClient.query("""
                MERGE (s:Script {scriptId: $sid})
                SET s.name = $name, s.scriptType = $type,
                    s.playerMin = $pmin, s.playerMax = $pmax,
                    s.price = $price, s.mark = $mark, s.image = $image
                """)
                .bind(scriptId).to("sid")
                .bind(name).to("name")
                .bind(scriptType).to("type")
                .bind(playerMin).to("pmin")
                .bind(playerMax).to("pmax")
                // Neo4j Driver 不支持 BigDecimal 参数，显式转 double（NULL 保持 null）
                .bind(price != null ? price.doubleValue() : null).to("price")
                .bind(mark != null ? mark.doubleValue() : null).to("mark")
                .bind(image).to("image")
                .run();

        // 2) 作者关系
        if (author != null && !author.isBlank()) {
            neo4jClient.query("""
                    MERGE (a:Author {name: $author})
                    WITH a
                    MATCH (s:Script {scriptId: $sid})
                    MERGE (s)-[:WRITTEN_BY]->(a)
                    """)
                    .bind(author).to("author")
                    .bind(scriptId).to("sid")
                    .run();
        }

        // 3) 类型节点（硬核/情感/欢乐/机制）+ HAS_TYPE 关系
        if (scriptType != null && !scriptType.isBlank()) {
            neo4jClient.query("""
                    MERGE (t:Type {name: $type})
                    WITH t
                    MATCH (s:Script {scriptId: $sid})
                    MERGE (s)-[:HAS_TYPE]->(t)
                    """)
                    .bind(scriptType).to("type")
                    .bind(scriptId).to("sid")
                    .run();
        }

        // 4) 细标签关系
        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                neo4jClient.query("""
                        MERGE (t:Tag {name: $tag})
                        WITH t
                        MATCH (s:Script {scriptId: $sid})
                        MERGE (s)-[:HAS_TAG]->(t)
                        """)
                        .bind(tag).to("tag")
                        .bind(scriptId).to("sid")
                        .run();
            }
        }

        // 5) 角色节点 + 本本关联（HAS_CHARACTER / CO_CHARACTER）
        syncCharacters(scriptId, characters);

        // 6) 剧本相关关系（RELATED_TO，先清旧后重建，保证 weight 幂等）
        rebuildRelated(scriptId);

        log.info("[syncScript] scriptId={} name={} type={} tags={} chars={}",
                scriptId, name, scriptType, tags, characters == null ? 0 : characters.size());
    }

    /**
     * 角色节点批量创建 + 同本角色两两建立 CO_CHARACTER 关系
     * <ul>
     *   <li>charId = scriptId:角色名，跨本同名不冲突</li>
     *   <li>CO_CHARACTER 单向(按 charId 升序配对)，避免同对重复建两条</li>
     * </ul>
     */
    private void syncCharacters(Long scriptId, List<Map<String, Object>> characters) {
        if (characters == null || characters.isEmpty()) return;
        for (Map<String, Object> c : characters) {
            Object rawName = c.get("name");
            if (rawName == null || rawName.toString().isBlank()) continue; // 无名字的角色不入图
            String cname = rawName.toString();
            String cid = scriptId + ":" + cname; // 稳定唯一 ID

            Integer gender = asInt(c.get("gender"));
            Integer age = asInt(c.get("age"));
            String desc = c.get("desc") == null ? null : c.get("desc").toString();
            String cimage = c.get("image") == null ? null : c.get("image").toString();

            neo4jClient.query("""
                    MERGE (c:Character {charId: $cid})
                    SET c.name = $cname, c.gender = $gender, c.age = $age,
                        c.desc = $desc, c.image = $cimage
                    WITH c
                    MATCH (s:Script {scriptId: $sid})
                    MERGE (s)-[:HAS_CHARACTER]->(c)
                    """)
                    .bind(cid).to("cid")
                    .bind(cname).to("cname")
                    .bind(gender).to("gender")
                    .bind(age).to("age")
                    .bind(desc).to("desc")
                    .bind(cimage).to("cimage")
                    .bind(scriptId).to("sid")
                    .run();
        }
        // 同本角色两两关联（单条 Cypher 完成全部配对）
        neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[:HAS_CHARACTER]->(a:Character)
                MATCH (s)-[:HAS_CHARACTER]->(b:Character)
                WHERE a <> b AND a.charId < b.charId
                MERGE (a)-[:CO_CHARACTER]->(b)
                """)
                .bind(scriptId).to("sid")
                .run();
    }

    /**
     * 重建某剧本的 RELATED_TO 关系（先删旧保证幂等）
     * weight = 共享细标签数 + 同类型数（同标签与同类型去重后累加）
     */
    private void rebuildRelated(Long scriptId) {
        if (scriptId == null) return;
        // 1) 清掉该剧本的全部旧相关关系
        neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[r:RELATED_TO]-(o)
                DELETE r
                """).bind(scriptId).to("sid").run();

        // 2) 共享细标签 → weight +1
        neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[:HAS_TAG]->(t:Tag)<-[:HAS_TAG]-(o:Script)
                WHERE o.scriptId <> $sid
                MERGE (s)-[r:RELATED_TO]-(o)
                ON CREATE SET r.weight = 1
                ON MATCH SET r.weight = r.weight + 1
                """).bind(scriptId).to("sid").run();

        // 3) 同类型 → weight +1
        neo4jClient.query("""
                MATCH (s:Script {scriptId: $sid})-[:HAS_TYPE]->(t:Type)<-[:HAS_TYPE]-(o:Script)
                WHERE o.scriptId <> $sid
                MERGE (s)-[r:RELATED_TO]-(o)
                ON CREATE SET r.weight = 1
                ON MATCH SET r.weight = r.weight + 1
                """).bind(scriptId).to("sid").run();
    }

    /**
     * 跨本同名角色关联（人物联动）
     * <p>把不同剧本中同名的真实角色用 SAME_NAME 关系连起来，
     * 使「人物」成为跨剧本推荐的桥梁信号。过滤规则：
     * <ul>
     *   <li>名字必须包含中文且长度≥2（排除 A/B/C/D 这类字母占位名）</li>
     *   <li>按 charId 排序保证单向，每对只建一条</li>
     * </ul>
     */
    public void buildSameNameEntities() {
        // 先清理旧的 SAME_NAME 关系，再重建，保证幂等
        neo4jClient.query("MATCH ()-[r:SAME_NAME]->() DELETE r").run();
        neo4jClient.query("""
                MATCH (a:Character)-[:HAS_CHARACTER]-(:Script)
                MATCH (b:Character)-[:HAS_CHARACTER]-(:Script)
                WHERE a <> b AND a.charId < b.charId
                  AND a.name = b.name
                  AND size(a.name) >= 2
                  AND a.name =~ '.*\\p{IsHan}.*'
                MERGE (a)-[:SAME_NAME]->(b)
                """).run();
        log.info("[buildSameNameEntities] SAME_NAME 关系已重建");
    }

    /** 同步订单（玩家-剧本游玩关系，count+1） */
    public void syncOrder(Long userId, String username, Long scriptId) {
        neo4jClient.query("""
                MERGE (u:User {userId: $uid})
                SET u.username = $uname
                WITH u
                MATCH (s:Script {scriptId: $sid})
                MERGE (u)-[r:PLAYED]->(s)
                ON CREATE SET r.count = 1
                ON MATCH SET r.count = r.count + 1
                """)
                .bind(userId).to("uid")
                .bind(username != null ? username : ("user" + userId)).to("uname")
                .bind(scriptId).to("sid")
                .run();
        log.info("[syncOrder] userId={} scriptId={}", userId, scriptId);
    }

    /** 失效某用户的推荐缓存（支付成功后调用） */
    public void evictUserCache(Long userId) {
        // 由调用方通过 Redis 处理，这里仅记录
        log.debug("[evictUserCache] userId={}", userId);
    }

    /**
     * 查询图谱统计（含增厚的节点/关系，用于验证/P2 可视化）
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("scripts", count("MATCH (s:Script) RETURN count(s) AS c"));
        stats.put("users", count("MATCH (u:User) RETURN count(u) AS c"));
        stats.put("tags", count("MATCH (t:Tag) RETURN count(t) AS c"));
        stats.put("types", count("MATCH (t:Type) RETURN count(t) AS c"));
        stats.put("authors", count("MATCH (a:Author) RETURN count(a) AS c"));
        stats.put("characters", count("MATCH (c:Character) RETURN count(c) AS c"));
        stats.put("coCharacterRel", count("MATCH (:Character)-[:CO_CHARACTER]->(:Character) RETURN count(*) AS c"));
        stats.put("sameNameRel", count("MATCH (:Character)-[:SAME_NAME]->(:Character) RETURN count(*) AS c"));
        // 注意: 无向 pattern (:Script)-[r:RELATED_TO]-(:Script) 会把每条关系计两次，
        // 因此这里固定按一个方向计数（RELATED_TO 每对仅一条，方向随机）
        stats.put("relatedRel", count("MATCH (:Script)-[:RELATED_TO]->(:Script) RETURN count(*) AS c"));
        stats.put("playedRel", count("MATCH (:User)-[:PLAYED]->(:Script) RETURN count(*) AS c"));
        return stats;
    }

    private long count(String cypher) {
        return neo4jClient.query(cypher)
                .fetch().one().map(m -> (long) m.get("c")).orElse(0L);
    }

    /**
     * 智能选本（Cypher 过滤）—— AI 陪练"帮我挑本"工具 / P2 可视化共用
     * <p>按 类型 / 期望人数 / 细标签 过滤图谱中的剧本节点，评分降序返回（最多 30 条）。
     * 与 MySQL 检索的区别：数据来自 Neo4j 图，支持按 Tag 关系过滤，并聚合返回该剧本的标签列表。
     *
     * @param scriptType 剧本类型（硬核/情感/欢乐/机制...），可为 null
     * @param tag        细标签（推理/解谜/还原...），可为 null
     * @param playerCnt  期望人数，可为 null（按 playerMin<=cnt<=playerMax 匹配）
     * @param limit      返回条数上限（默认 10，封顶 30）
     * @return [{scriptId, name, scriptType, playerMin, playerMax, mark, price, image, tags}, ...]
     */
    public List<Map<String, Object>> smartFilter(String scriptType, String tag, Integer playerCnt, Integer limit) {
        StringBuilder cypher = new StringBuilder("MATCH (s:Script)\n");
        // 动态拼 WHERE：参数为 null 时跳过对应条件，避免 null 绑定与 `IS NULL OR` 写法歧义
        List<String> wheres = new java.util.ArrayList<>();
        if (scriptType != null && !scriptType.isBlank()) wheres.add("s.scriptType = $type");
        if (playerCnt != null) wheres.add("s.playerMin <= $pc AND s.playerMax >= $pc");
        if (tag != null && !tag.isBlank()) wheres.add("(s)-[:HAS_TAG]->(:Tag {name: $tag})");
        if (!wheres.isEmpty()) cypher.append("WHERE ").append(String.join(" AND ", wheres)).append("\n");
        cypher.append("""
                OPTIONAL MATCH (s)-[:HAS_TAG]->(t:Tag)
                WITH s, collect(t.name) AS tags
                RETURN s.scriptId AS scriptId, s.name AS name, s.scriptType AS scriptType,
                       s.playerMin AS playerMin, s.playerMax AS playerMax,
                       s.mark AS mark, s.price AS price, s.image AS image, tags
                ORDER BY s.mark IS NULL, s.mark DESC, s.name ASC
                LIMIT $limit
                """);
        int lim = (limit == null || limit <= 0) ? 10 : Math.min(limit, 30);
        return neo4jClient.query(cypher.toString())
                .bind(scriptType).to("type")
                .bind(playerCnt).to("pc")
                .bind(tag).to("tag")
                .bind(lim).to("limit")
                .fetch().all().stream()
                .map(m -> new HashMap<>(m))   // Neo4j 结果转可变 Map（防止调用方修改抛异常）
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 图谱快照（P2 可视化）—— 返回限量的剧本节点 + 节点间 RELATED_TO 边
     * <p>节点含聚合字段（tags/types/authors/charCount），前端可直接渲染关系图。
     *
     * @param limit 剧本节点数上限（默认 30，封顶 80），边数封顶 200
     * @return {"scripts": [...], "links": [{source, target, weight}, ...]}
     */
    public Map<String, Object> graphSnapshot(Integer limit) {
        int lim = (limit == null || limit <= 0) ? 30 : Math.min(limit, 80);

        // 1) top 剧本（评分降序，聚合标签/类型/作者/角色数）
        List<Map<String, Object>> scripts = neo4jClient.query("""
                MATCH (s:Script)
                OPTIONAL MATCH (s)-[:HAS_TAG]->(t:Tag)
                OPTIONAL MATCH (s)-[:HAS_TYPE]->(ty:Type)
                OPTIONAL MATCH (s)-[:WRITTEN_BY]->(a:Author)
                OPTIONAL MATCH (s)-[:HAS_CHARACTER]->(c:Character)
                WITH s, collect(DISTINCT t.name) AS tags, collect(DISTINCT ty.name) AS types,
                     collect(DISTINCT a.name) AS authors, count(DISTINCT c) AS charCount
                RETURN s.scriptId AS scriptId, s.name AS name, s.scriptType AS scriptType,
                       s.mark AS mark, s.image AS image, s.price AS price, tags, types, authors, charCount
                ORDER BY s.mark IS NULL, s.mark DESC, s.name ASC
                LIMIT $limit
                """)
                .bind(lim).to("limit")
                .fetch().all().stream()
                .map(m -> new HashMap<>(m))
                .collect(java.util.stream.Collectors.toList());

        // 2) 已选剧本之间的 RELATED_TO 边（无向边按 scriptId 升序方向去重，weight 降序）
        List<Map<String, Object>> links = new ArrayList<>();
        List<Object> ids = scripts.stream()
                .map(m -> m.get("scriptId"))
                .collect(java.util.stream.Collectors.toList());
        if (!ids.isEmpty()) {
            links = neo4jClient.query("""
                    MATCH (a:Script)-[r:RELATED_TO]-(b:Script)
                    WHERE a.scriptId IN $ids AND b.scriptId IN $ids AND a.scriptId < b.scriptId
                    RETURN a.scriptId AS source, b.scriptId AS target, r.weight AS weight
                    ORDER BY weight DESC
                    LIMIT 200
                    """)
                    .bind(ids).to("ids")
                    .fetch().all().stream()
                    .map(m -> new HashMap<>(m))
                    .collect(java.util.stream.Collectors.toList());
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("scripts", scripts);
        snapshot.put("links", links);
        return snapshot;
    }

    /** 对象转 Integer（角色 gender/age 可能为 null 或数字类型） */
    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
