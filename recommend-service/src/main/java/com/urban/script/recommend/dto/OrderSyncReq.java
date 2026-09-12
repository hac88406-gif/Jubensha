package com.urban.script.recommend.dto;

import lombok.Data;

/**
 * 订单同步请求 DTO（内部接口）
 * <p>
 * order-service 支付成功后调用 /recommend/internal/sync/order，
 * 把「玩家-剧本」游玩关系写入 Neo4j，用于协同过滤推荐。
 */
@Data
public class OrderSyncReq {
    /** 玩家用户 ID */
    private Long userId;
    /** 玩家用户名（用于 User 节点展示） */
    private String username;
    /** 剧本 ID */
    private Long scriptId;
}
