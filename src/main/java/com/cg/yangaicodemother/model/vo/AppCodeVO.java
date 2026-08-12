package com.cg.yangaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 应用代码视图：查看某个应用已生成的代码文件（供前端「查看代码」弹窗展示）。
 */
@Data
public class AppCodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应用 id */
    private Long id;

    /** 应用名称 */
    private String appName;

    /** 已部署访问地址（未部署为 null） */
    private String deployUrl;

    /** 代码生成类型（html / multi_file），用于定位保存目录 */
    private String codeGenType;

    /** 实际存在的代码文件名列表（如 index.html / style.css / script.js） */
    private List<String> fileNames;

    private String htmlCode;

    private String cssCode;

    private String jsCode;

    /**
     * 完整文件清单（path + content），html/multi_file/vue 通用。
     * 前端优先用它渲染文件树；htmlCode/cssCode/jsCode 保留兼容旧逻辑。
     */
    private List<ProjectFileVO> files;
}
