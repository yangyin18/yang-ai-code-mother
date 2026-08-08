package com.cg.yangaicodemother.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.mybatisflex.core.BaseMapper;
import com.cg.yangaicodemother.model.entity.User;

/**
 * 用户 映射层。
 *
 * @author 34488
 * @since 2026-08-06
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
