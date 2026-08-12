package com.cg.yangaicodemother.model.dto;

import com.cg.yangaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话历史分页查询请求（管理员）。
 *
 * <p>支持按消息 id / 应用 id / 用户 id / 消息类型过滤，默认按创建时间倒序，
 * 便于内容监管。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryAdminQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 id（精确匹配）
     */
    private Long id;

    /**
     * 所属应用 id（精确匹配）
     */
    private Long appId;

    /**
     * 发送用户 id（精确匹配）
     */
    private Long userId;

    /**
     * 消息类型（精确匹配）：user / ai / error
     */
    private String messageType;

}
