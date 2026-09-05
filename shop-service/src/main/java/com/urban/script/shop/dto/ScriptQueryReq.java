package com.urban.script.shop.dto;

import lombok.Data;

/**
 * 剧本查询请求（玩家端 + 管理端共用）
 * <p>
 * 所有字段可选，只传非空的条件。MyBatis-Plus QueryWrapper 动态拼接。
 *
 * @author urban-script-reservation
 */
@Data
public class ScriptQueryReq {

    /** 所属店铺 id（可选） */
    private Long shopId;

    /** 剧本类型（可选: 硬核/情感/欢乐/机制） */
    private String type;

    /** 最少玩家数 ≤ 查询值？不，这里定义成"期望人数"：筛选 playerMin ≤ playerCnt ≤ playerMax 的剧本 */
    private Integer playerCnt;

    /** 剧本名称模糊匹配（可选） */
    private String nameKeyword;

    /** 上架状态：玩家端固定传 1；管理端可不传（=查全部） */
    private Integer status;
}
