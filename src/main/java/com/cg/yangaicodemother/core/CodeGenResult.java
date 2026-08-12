package com.cg.yangaicodemother.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代码生成结果（门面层返回给 Controller / 调用方的统一结果对象）。
 *
 * <p>HTML 模式只填充 htmlCode / description；
 * 多文件模式额外填充 cssCode / jsCode。
 */
@Data
@Builder
public class CodeGenResult {

    /**
     * 生成类型 value（html / multi_file），见 {@link com.cg.yangaicodemother.model.enums.CodeGenTypeEnum}
     */
    private String codeGenType;

    /**
     * 功能描述
     */
    private String description;

    /**
     * HTML 代码（两种模式都有）
     */
    private String htmlCode;

    /**
     * CSS 代码（仅多文件模式）
     */
    private String cssCode;

    /**
     * JS 代码（仅多文件模式）
     */
    private String jsCode;

    /**
     * 文件保存目录（服务端绝对路径，仅供服务端内部使用）。
     * {@code @JsonIgnore}：绝不序列化给客户端，避免把服务器上的绝对路径暴露给用户。
     */
    @JsonIgnore
    private String saveDir;

    /**
     * 生成的文件名列表
     */
    private List<String> fileNames;
}
