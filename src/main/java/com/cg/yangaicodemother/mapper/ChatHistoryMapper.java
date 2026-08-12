package com.cg.yangaicodemother.mapper;

import com.cg.yangaicodemother.model.entity.ChatHistory;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话历史 映射层。
 *
 * @author 34488
 * @since 2026-08-10
 */
@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

}
