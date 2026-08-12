package com.cg.yangaicodemother.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 应用代码「直接改样式」请求（可视化编辑里选中元素改颜色/内边距/外边距后同步到代码文件）。
 *
 * <p>不调 AI：后端在已生成代码里定位目标元素的开标签，把 {@code style} 里的属性
 * 合并进该元素的 style 属性（已存在则覆盖、没写则保留其它内联样式），其余代码原样保留。
 * 定位锚点优先级：id &gt; 元素文本 &gt; class 首段（前端会带上元素定位信息）。</p>
 */
@Data
public class AppEditStyleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（必填）
     */
    private Long appId;

    /**
     * 目标元素标签名，小写，如 'h1'（必填）
     */
    private String tag;

    /**
     * 元素 id（可为空，作为优先定位锚点）
     */
    private String id;

    /**
     * 元素完整 class 字符串（可为空，作为兜底定位锚点，取首段）
     */
    private String className;

    /**
     * 元素可见文本（可为空，作为文本定位锚点）
     */
    private String text;

    /**
     * 要修改的样式属性，如 {"color":"#ff0000","padding":"8px"}（必填且非空）
     */
    private Map<String, String> style;

}
