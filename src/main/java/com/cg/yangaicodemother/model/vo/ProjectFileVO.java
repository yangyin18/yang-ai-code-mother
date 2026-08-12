package com.cg.yangaicodemother.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 项目文件视图：路径（含目录）+ 内容。
 * 供「查看代码」通用展示：html/multi_file 是 index.html 等扁平文件，
 * Vue 项目则是 src/App.vue 等嵌套路径。内容由磁盘读取，不消耗 AI token。
 */
@Data
public class ProjectFileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 项目内相对路径，如 index.html / src/App.vue */
    private String path;

    /** 文件内容 */
    private String content;
}
