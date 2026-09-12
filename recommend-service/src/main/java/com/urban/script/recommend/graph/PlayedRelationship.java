package com.urban.script.recommend.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * 玩家-剧本 游玩关系（带游玩次数）
 */
@Data
@RelationshipProperties
public class PlayedRelationship {

    @RelationshipId
    private Long id;

    @TargetNode
    private ScriptNode script;

    /** 游玩次数 */
    private Integer count;

    public PlayedRelationship() {}

    public PlayedRelationship(ScriptNode script, Integer count) {
        this.script = script;
        this.count = count;
    }
}
