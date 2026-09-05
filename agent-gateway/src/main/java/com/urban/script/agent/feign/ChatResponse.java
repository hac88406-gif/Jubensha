package com.urban.script.agent.feign;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AI 对话响应 DTO（Python Agent 返回）
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class ChatResponse {

    /** 业务码：200=正常，500=Agent 繁忙 / Sentinel fallback */
    private Integer code;

    /** Agent 回复文本 */
    private String message;

    /** Agent 返回的结构化数据（可选，如推荐场次列表） */
    private Object data;

    /** 意图识别结果（可选，如 book_session / query_script / unknown） */
    private String intent;

    /** 工具调用痕迹（可选，Agent 内部 tool call 记录，便于调试） */
    private List<Map<String, Object>> toolCalls;

    /** Sentinel fallback 专用构造器 —— 用于硬编码降级 */
    public static ChatResponse fallback(String msg) {
        return ChatResponse.builder()
                .code(200)
                .message(msg)
                .build();
    }

    /** 正常响应构造器 */
    public static ChatResponse success(String message, Object data) {
        return ChatResponse.builder()
                .code(200)
                .message(message)
                .data(data)
                .build();
    }
}
