package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用创建请求（用户）。
 *
 * <p>initPrompt 必填（须填写应用初始化的 prompt）；其余字段可选。</p>
 */
@Data
public class AppCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt（必填）
     */
    private String initPrompt;

    /**
     * 代码生成类型（html / multi_file）
     */
    private String codeGenType;

}
