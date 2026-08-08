package com.cg.yangaicodemother.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * HTML 代码结果
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HtmlCodeResult {

    /**
     * HTML 代码
     */
    private String htmlCode;

    /**
     * 描述
     */
    private String description;
}