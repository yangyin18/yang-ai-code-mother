package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用更新请求（用户）。
 *
 * <p>用户只能修改自己应用的名称（目前仅支持修改应用名称），id 与 appName 必填。</p>
 */
@Data
public class AppUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long id;

    /**
     * 应用名称（必填）
     */
    private String appName;

}
