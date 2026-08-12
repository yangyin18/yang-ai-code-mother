package com.cg.yangaicodemother.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 游标查询对话历史结果（keyset 分页）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatCursorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本页消息（按 createTime DESC + id DESC，即新→旧；前端倒序后升序展示）
     */
    private List<ChatHistoryVO> records;

    /**
     * 是否还有更早的消息（本页已取满 size 条则很可能还有）
     */
    private boolean hasMore;
}
