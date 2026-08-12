package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发送对话消息请求（流式对话）。
 */
@Data
public class ChatSendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long appId;

    /**
     * 用户消息内容（必填）
     */
    private String message;
}
