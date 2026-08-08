package com.cg.yangaicodemother.model.dto;

import com.cg.yangaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询请求（管理员）。
 *
 * <p>继承 {@link PageRequest}，含 pageNum / pageSize / sortField / sortOrder。
 * 各查询条件可选，为空则不参与过滤。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 id（精确匹配）
     */
    private Long id;

    /**
     * 账号（模糊匹配）
     */
    private String userAccount;

    /**
     * 昵称（模糊匹配）
     */
    private String userName;

    /**
     * 角色：user / admin（精确匹配）
     */
    private String userRole;

}
