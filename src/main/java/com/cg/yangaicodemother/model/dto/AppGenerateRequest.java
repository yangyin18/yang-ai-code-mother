package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用代码生成请求。
 *
 * <p>appId 必填；requirement 为本次生成的具体需求描述（必填）。
 * 生成时会把应用的 initPrompt 作为基础指令、叠加 requirement 一起发给 AI。</p>
 */
@Data
public class AppGenerateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long appId;

    /**
     * 本次生成的具体需求描述（必填）
     */
    private String requirement;

}
