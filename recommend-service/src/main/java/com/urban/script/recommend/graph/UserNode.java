package com.urban.script.recommend.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * 玩家节点
 */
@Data
@Node("User")
public class UserNode {

    @Id
    private Long userId;

    private String username;

    /** 玩家 → 玩过的剧本 */
    @Relationship(type = "PLAYED", direction = Relationship.Direction.OUTGOING)
    private Set<PlayedRelationship> playedScripts = new HashSet<>();

    public UserNode() {}

    public UserNode(Long userId) {
        this.userId = userId;
    }
}
