package com.urban.script.recommend.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 剧本节点 —— Neo4j 知识图谱核心实体
 */
@Data
@Node("Script")
public class ScriptNode {

    /** 剧本 ID（与 MySQL script_info.id 一致） */
    @Id
    private Long scriptId;

    private String name;
    private String scriptType;
    private Integer playerMin;
    private Integer playerMax;
    private BigDecimal price;
    private BigDecimal mark;
    private String image;

    /** 剧本 → 标签 */
    @Relationship(type = "HAS_TAG", direction = Relationship.Direction.OUTGOING)
    private Set<TagNode> tags = new HashSet<>();

    /** 剧本 → 作者 */
    @Relationship(type = "WRITTEN_BY", direction = Relationship.Direction.OUTGOING)
    private AuthorNode author;

    public ScriptNode() {}

    public ScriptNode(Long scriptId, String name) {
        this.scriptId = scriptId;
        this.name = name;
    }
}
