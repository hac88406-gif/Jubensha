package com.urban.script.recommend.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 作者节点
 */
@Data
@Node("Author")
public class AuthorNode {

    @Id
    private String name;

    public AuthorNode() {}

    public AuthorNode(String name) {
        this.name = name;
    }
}
