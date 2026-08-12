package com.cg.yangaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史视图对象。
 */
@Data
public class ChatHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    private Long id;

    /**
     * 所属应用 id
     */
    private Long appId;

    /**
     * 发送用户 id
     */
    private Long userId;

    /**
     * 消息类型：user / ai / error
     */
    private String messageType;

    /**
     * 消息内容
     */
    private String message;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
