package com.urban.script.agent.feign;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * AI 对话请求 DTO（发送给 Python Agent）
 *
 * @author urban-script-reservation
 */
@Data
public class ChatRequest {

    /** 用户 ID（Python Agent 会话记忆用） */
    private Long userId;

    /**
     * 用户最新输入 —— 必填、不能为空字符串 / 全空白。
     * Controller 方法已加 {@code @Valid}，此处校验失败会返回 400。
     */
    @NotBlank(message = "message 不能为空")
    private String message;

    /** 会话历史（多轮对话上下文，Python Agent 的 LangGraph 用） */
    private List<Message> history;

    /** 可选：sessionId，如果 Agent 需要知道玩家正在谈哪个场次 */
    private Long sessionId;

    @Data
    public static class Message {
        /** "user" 或 "assistant" */
        private String role;
        private String content;
    }
}
