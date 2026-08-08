package com.cg.yangaicodemother.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 多文件代码结果
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiFileCodeResult {

    /**
     * html代码
     */
    private String htmlCode;

    /**
     * css代码
     */
    private String cssCode;

    /**
     * js代码
     */
    private String jsCode;

    /**
     * 功能描述
     */
    private String description;
}