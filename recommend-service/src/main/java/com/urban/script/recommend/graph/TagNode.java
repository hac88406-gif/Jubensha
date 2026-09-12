package com.urban.script.recommend.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 标签节点（细粒度标签：恐怖/古风/本格/变格...）
 */
@Data
@Node("Tag")
public class TagNode {

    @Id
    private String name;

    public TagNode() {}

    public TagNode(String name) {
        this.name = name;
    }
}
