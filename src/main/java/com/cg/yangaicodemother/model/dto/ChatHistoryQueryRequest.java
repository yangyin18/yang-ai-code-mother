package com.cg.yangaicodemother.model.dto;

import com.cg.yangaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话历史分页查询请求（用户）。
 *
 * <p>继承 {@link PageRequest}，按创建时间倒序（最新在前）加载某个应用的对话，
 * 每页默认 10 条、最多 20 条——前端首屏取第 1 页即最新 10 条，
 * 向上滚动翻页加载更早的历史记录。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 所属应用 id（必填）
     */
    private Long appId;

}
