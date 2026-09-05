package com.urban.script.common;

/**
 * Redis Key 常量类
 *
 * <p>集中管理所有 Redis key 前缀，避免业务层硬编码散落字符串。
 * 使用方式：{@code RedisKeyConstant.SESSION_KEY + sessionId}
 *
 * @author urban-script-reservation
 */
public final class RedisKeyConstant {

    // ========================================================================
    // Key 前缀定义
    // ========================================================================

    /**
     * 场次 Redis HASH key 前缀
     * <p>完整 key: {@code session:{sessionId}}
     * <p>存储结构：HASH，field 包含 capacity / booked / dmId / sessionDate 等
     */
    public static final String SESSION_KEY = "session:";

    /**
     * 预约分布式锁前缀（用于防止同一用户对同一场次重复提交）
     * <p>完整 key: {@code reserve:lock:{sessionId}:{userId}}
     * <p>使用 SETNX + EX 实现，TTL 建议 10 秒
     */
    public static final String RESERVE_LOCK = "reserve:lock:";

    /**
     * Agent 会话记忆前缀（LangGraph / LangChain 用于多轮对话上下文存储）
     * <p>完整 key: {@code agent:session:{userId}}
     */
    public static final String AGENT_SESSION = "agent:session:";

    // ========================================================================
    // 禁止实例化
    // ========================================================================

    private RedisKeyConstant() {
        throw new UnsupportedOperationException("RedisKeyConstant is a utility class, cannot be instantiated");
    }
}
