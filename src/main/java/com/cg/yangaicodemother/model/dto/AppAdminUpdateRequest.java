package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用更新请求（管理员）。
 *
 * <p>除 id 外均可选：支持更新应用名称、需求描述、应用封面、优先级。为空的字段不更新。</p>
 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用初始化的 prompt（需求描述）
     */
    private String initPrompt;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 优先级（数字越大越靠前，精选列表按此排序）
     */
    private Integer priority;

}
