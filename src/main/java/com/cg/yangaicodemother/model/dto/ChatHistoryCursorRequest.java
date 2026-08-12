package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 游标查询对话历史请求（keyset 分页）。
 *
 * <p>按消息 id 定位游标：查询「比 cursorId 更早」的消息（createTime DESC + id DESC），
 * 首次进入不传 cursorId 即取该应用最新的一页，前端拿到后倒序展示。
 */
@Data
public class ChatHistoryCursorRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long appId;

    /**
     * 游标 id：加载比该消息更早的历史；首次加载（最新一页）不传
     */
    private Long cursorId;

    /**
     * 每页条数，默认 10，最多 20
     */
    private Integer size;
}
