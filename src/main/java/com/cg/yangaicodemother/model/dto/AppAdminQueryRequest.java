package com.cg.yangaicodemother.model.dto;

import com.cg.yangaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用分页查询请求（管理员）。
 *
 * <p>支持按除时间外的任意字段过滤，每页数量不限。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppAdminQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（精确匹配）
     */
    private Long id;

    /**
     * 应用名称（模糊匹配）
     */
    private String appName;

    /**
     * 代码生成类型（精确匹配）
     */
    private String codeGenType;

    /**
     * 部署标识（模糊匹配）
     */
    private String deployKey;

    /**
     * 优先级（精确匹配）
     */
    private Integer priority;

    /**
     * 创建用户 id（精确匹配）
     */
    private Long userId;

}
