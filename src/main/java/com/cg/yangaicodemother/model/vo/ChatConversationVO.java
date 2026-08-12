package com.cg.yangaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话摘要视图对象：我的对话列表里每个应用的最近动态。
 */
@Data
public class ChatConversationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属应用 id
     */
    private Long appId;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 已部署应用的 nginx 访问地址，未部署为 null
     */
    private String deployUrl;

    /**
     * 最新一条消息的内容（截断显示）
     */
    private String latestMessage;

    /**
     * 最新一条消息的类型：user / ai / error
     */
    private String latestMessageType;

    /**
     * 最新一条消息的时间
     */
    private LocalDateTime latestTime;

    /**
     * 该应用的消息总数
     */
    private Long messageCount;

}
