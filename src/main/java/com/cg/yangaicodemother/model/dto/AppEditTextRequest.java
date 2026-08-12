package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 应用代码「直接改文字」请求（可视化编辑里选中元素改文字后同步到代码文件）。
 *
 * <p>不调 AI：后端在已生成代码文件里把 {@code oldText} 全局替换为 {@code newText} 并写回，
 * 只影响目标文字，其余代码原样保留。appId 必填；oldText / newText 均必填且非空。</p>
 */
@Data
public class AppEditTextRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long appId;

    /**
     * 原文字：选中的元素在当前代码里的文本（必填，须在代码文件中出现）
     */
    private String oldText;

    /**
     * 新文字：用户改后的文本（必填）
     */
    private String newText;

}
