package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 保存对话消息请求（用户）。
 *
 * <p>用户消息 / AI 消息 / 错误消息统一走这个请求落库；messageType 取值见
 * {@link com.cg.yangaicodemother.model.enums.MessageTypeEnum}。</p>
 */
@Data
public class ChatMessageAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属应用 id（必填）
     */
    private Long appId;

    /**
     * 消息类型（必填）：user / ai / error
     */
    private String messageType;

    /**
     * 消息内容（必填）
     */
    private String message;

}
