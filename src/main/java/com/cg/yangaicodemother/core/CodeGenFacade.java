package com.cg.yangaicodemother.core;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.core.parser.CodeParseResult;
import com.cg.yangaicodemother.core.parser.CodeParser;
import com.cg.yangaicodemother.core.parser.CodeParserException;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * 代码生成门面。
 *
 * <p>对外屏蔽「生成类型校验 → AI 调用 → 结果校验 → 文件落盘」的完整流程，
 * Controller / 调用方只需传入需求描述与生成类型即可拿到最终结果。
 * 内部编排 {@link AiCodeGeneratorService} 与 {@link CodeFileSaver}。
 *
 * <p>流式方法（*Stream）通过 {@link TokenStream} 边生成边回调 {@link CodeGenStreamCallback}，
 * 结束时在回调线程内完成 JSON 解析与文件落盘，调用方不要依赖返回值。
 */
@Slf4j
@Service
public class CodeGenFacade {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    // ==================== 非流式 ====================

    /**
     * 按生成类型生成代码并保存到磁盘。
     *
     * @param userMessage      用户需求描述
     * @param codeGenTypeValue 生成类型 value（html / multi_file），见 {@link CodeGenTypeEnum}
     * @return 生成结果（含代码内容与保存目录）
     */
    public CodeGenResult generate(String userMessage, String codeGenTypeValue) {
        CodeGenTypeEnum type = resolveType(codeGenTypeValue);
        return switch (type) {
            case HTML -> generateHtml(userMessage);
            case MULTI_FILE -> generateMultiFile(userMessage);
        };
    }

    /**
     * 生成单文件 HTML 网页并保存。
     */
    public CodeGenResult generateHtml(String userMessage) {
        validateUserMessage(userMessage);
        HtmlCodeResult result = aiCodeGeneratorService.generateCode(userMessage);
        if (result == null || StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成有效的代码");
        }
        File saveDir = CodeFileSaver.saveHtmlCodeResult(result);
        log.info("HTML 代码生成并保存成功，目录：{}", saveDir.getAbsolutePath());
        return buildHtmlResult(result, saveDir);
    }

    /**
     * 生成多文件（index.html + style.css + script.js）网页并保存。
     */
    public CodeGenResult generateMultiFile(String userMessage) {
        validateUserMessage(userMessage);
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiCode(userMessage);
        if (result == null || StrUtil.hasBlank(result.getHtmlCode(), result.getCssCode(), result.getJsCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成有效的多文件代码");
        }
        File saveDir = CodeFileSaver.saveMultiFileCodeResult(result);
        log.info("多文件代码生成并保存成功，目录：{}", saveDir.getAbsolutePath());
        return buildMultiFileResult(result, saveDir);
    }

    // ==================== 流式 ====================

    /**
     * 按生成类型流式生成代码，边生成边回调，结束后落盘。
     *
     * @param userMessage      用户需求描述
     * @param codeGenTypeValue 生成类型 value（html / multi_file）
     * @param callback         流式回调（partial / complete / error）
     */
    public void generateStream(String userMessage, String codeGenTypeValue, CodeGenStreamCallback callback) {
        CodeGenTypeEnum type = resolveType(codeGenTypeValue);
        switch (type) {
            case HTML -> generateHtmlStream(userMessage, callback);
            case MULTI_FILE -> generateMultiFileStream(userMessage, callback);
        }
    }

    /**
     * 单文件 HTML 流式生成。
     */
    public void generateHtmlStream(String userMessage, CodeGenStreamCallback callback) {
        validateUserMessage(userMessage);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeStream(userMessage);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleHtmlComplete(response, callback))
                .onError(callback::onError)
                .start();
    }

    /**
     * 多文件（html、css、js）流式生成。
     */
    public void generateMultiFileStream(String userMessage, CodeGenStreamCallback callback) {
        validateUserMessage(userMessage);
        TokenStream tokenStream = aiCodeGeneratorService.generateMultiCodeStream(userMessage);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleMultiFileComplete(response, callback))
                .onError(callback::onError)
                .start();
    }

    private void handleHtmlComplete(ChatResponse response, CodeGenStreamCallback callback) {
        try {
            // 交给解析器：JSON / Markdown / 裸 HTML 都能处理
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.HTML);
            File saveDir = CodeFileSaver.saveFiles(parsed.files(), CodeGenTypeEnum.HTML.getValue());
            log.info("HTML 代码流式生成并保存成功，目录：{}", saveDir.getAbsolutePath());
            callback.onComplete(buildResultFromFiles(parsed, CodeGenTypeEnum.HTML, saveDir));
        } catch (CodeParserException e) {
            callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("HTML 流式结果处理失败", e);
            callback.onError(e);
        }
    }

    private void handleMultiFileComplete(ChatResponse response, CodeGenStreamCallback callback) {
        try {
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.MULTI_FILE);
            File saveDir = CodeFileSaver.saveFiles(parsed.files(), CodeGenTypeEnum.MULTI_FILE.getValue());
            log.info("多文件代码流式生成并保存成功，目录：{}", saveDir.getAbsolutePath());
            callback.onComplete(buildResultFromFiles(parsed, CodeGenTypeEnum.MULTI_FILE, saveDir));
        } catch (CodeParserException e) {
            callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("多文件流式结果处理失败", e);
            callback.onError(e);
        }
    }

    // ==================== 私有工具 ====================

    private CodeGenTypeEnum resolveType(String codeGenTypeValue) {
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(codeGenTypeValue);
        if (type == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的代码生成类型：" + codeGenTypeValue);
        }
        return type;
    }

    private void validateUserMessage(String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "需求描述不能为空");
        }
    }

    /** 把解析出的代码文件归一化成对外结果实体 */
    private CodeGenResult buildResultFromFiles(CodeParseResult parsed, CodeGenTypeEnum type, File saveDir) {
        CodeGenResult.CodeGenResultBuilder builder = CodeGenResult.builder()
                .codeGenType(type.getValue())
                .description(parsed.description())
                .saveDir(saveDir.getAbsolutePath())
                .fileNames(parsed.files().stream().map(CodeFile::name).toList());
        for (CodeFile file : parsed.files()) {
            switch (file.name()) {
                case "index.html" -> builder.htmlCode(file.content());
                case "style.css" -> builder.cssCode(file.content());
                case "script.js" -> builder.jsCode(file.content());
                default -> {
                    // 未知文件名忽略，只进 fileNames 列表
                }
            }
        }
        return builder.build();
    }

    private CodeGenResult buildHtmlResult(HtmlCodeResult result, File saveDir) {
        return CodeGenResult.builder()
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                .description(result.getDescription())
                .htmlCode(result.getHtmlCode())
                .saveDir(saveDir.getAbsolutePath())
                .fileNames(List.of("index.html"))
                .build();
    }

    private CodeGenResult buildMultiFileResult(MultiFileCodeResult result, File saveDir) {
        return CodeGenResult.builder()
                .codeGenType(CodeGenTypeEnum.MULTI_FILE.getValue())
                .description(result.getDescription())
                .htmlCode(result.getHtmlCode())
                .cssCode(result.getCssCode())
                .jsCode(result.getJsCode())
                .saveDir(saveDir.getAbsolutePath())
                .fileNames(List.of("index.html", "style.css", "script.js"))
                .build();
    }
}
