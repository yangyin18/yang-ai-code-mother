package com.cg.yangaicodemother.core;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.parser.CodeFile;
import com.cg.yangaicodemother.core.parser.CodeParseResult;
import com.cg.yangaicodemother.core.parser.CodeParser;
import com.cg.yangaicodemother.core.parser.CodeParserException;
import com.cg.yangaicodemother.core.saver.CodeSaver;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.cg.yangaicodemother.service.AppService;
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
 * <p>对外屏蔽「加载应用 → 拼 prompt → AI 调用 → 结果校验 → 文件落盘」的完整流程。
 * 分两种模式：
 * <ul>
 *   <li><b>应用模式</b>：传入 {@code appId}，门面加载应用，用其 {@code initPrompt} 作为基础指令、
 *       叠加本次需求调用 AI，生成类型取应用的 {@code codeGenType}，保存目录按应用 id 命名；</li>
 *   <li><b>基础模式</b>：显式传 {@code codeGenTypeValue}，直接按用户消息生成（不拼 initPrompt）。</li>
 * </ul>
 *
 * <p>流式方法（*Stream）通过 {@link TokenStream} 边生成边回调 {@link CodeGenStreamCallback}，
 * 结束时在回调线程内完成 JSON 解析与文件落盘，调用方不要依赖返回值。
 */
@Slf4j
@Service
public class CodeGenFacade {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Resource
    private AppService appService;

    // ==================== 非流式 ====================

    /**
     * 为应用生成代码（应用模式）。加载应用后用 initPrompt + 本次需求调用 AI，
     * 生成类型取应用的 codeGenType，保存目录按应用 id 命名。
     *
     * @param userMessage 本次生成的具体需求描述
     * @param appId       应用 id
     * @return 生成结果（含代码内容与保存目录）
     */
    public CodeGenResult generate(String userMessage, Long appId) {
        App app = loadApp(appId);
        validateUserMessage(userMessage);
        CodeGenTypeEnum type = resolveAppType(app);
        return switch (type) {
            case HTML -> generateHtml(userMessage, app.getId(), app.getInitPrompt());
            case MULTI_FILE -> generateMultiFile(userMessage, app.getId(), app.getInitPrompt());
        };
    }

    /**
     * 按显式生成类型生成代码（基础模式，不拼 initPrompt）。
     *
     * @param userMessage      用户需求描述
     * @param codeGenTypeValue 生成类型 value（html / multi_file），见 {@link CodeGenTypeEnum}
     * @param appId            应用 id，用于命名保存目录
     * @return 生成结果（含代码内容与保存目录）
     */
    public CodeGenResult generate(String userMessage, String codeGenTypeValue, Long appId) {
        CodeGenTypeEnum type = resolveType(codeGenTypeValue);
        return switch (type) {
            case HTML -> generateHtml(userMessage, appId);
            case MULTI_FILE -> generateMultiFile(userMessage, appId);
        };
    }

    /**
     * 生成单文件 HTML 网页并保存（基础模式）。
     */
    public CodeGenResult generateHtml(String userMessage, Long appId) {
        validateUserMessage(userMessage);
        return generateHtml(userMessage, appId, null);
    }

    /**
     * 生成多文件（index.html + style.css + script.js）网页并保存（基础模式）。
     */
    public CodeGenResult generateMultiFile(String userMessage, Long appId) {
        validateUserMessage(userMessage);
        return generateMultiFile(userMessage, appId, null);
    }

    // ==================== 流式 ====================

    /**
     * 为应用流式生成代码（应用模式）。用 initPrompt + 本次需求调用 AI，
     * 生成类型取应用的 codeGenType，保存目录按应用 id 命名。
     *
     * @param userMessage 本次生成的具体需求描述
     * @param callback    流式回调（partial / complete / error）
     * @param appId       应用 id
     */
    public void generateStream(String userMessage, CodeGenStreamCallback callback, Long appId) {
        App app = loadApp(appId);
        validateUserMessage(userMessage);
        CodeGenTypeEnum type = resolveAppType(app);
        switch (type) {
            case HTML -> generateHtmlStream(userMessage, callback, app.getId(), app.getInitPrompt());
            case MULTI_FILE -> generateMultiFileStream(userMessage, callback, app.getId(), app.getInitPrompt());
        }
    }

    /**
     * 按显式生成类型流式生成代码（基础模式，不拼 initPrompt）。
     */
    public void generateStream(String userMessage, String codeGenTypeValue,
                               CodeGenStreamCallback callback, Long appId) {
        CodeGenTypeEnum type = resolveType(codeGenTypeValue);
        switch (type) {
            case HTML -> generateHtmlStream(userMessage, callback, appId);
            case MULTI_FILE -> generateMultiFileStream(userMessage, callback, appId);
        }
    }

    /**
     * 单文件 HTML 流式生成（基础模式）。
     */
    public void generateHtmlStream(String userMessage, CodeGenStreamCallback callback, Long appId) {
        validateUserMessage(userMessage);
        generateHtmlStream(userMessage, callback, appId, null);
    }

    /**
     * 多文件（html、css、js）流式生成（基础模式）。
     */
    public void generateMultiFileStream(String userMessage, CodeGenStreamCallback callback, Long appId) {
        validateUserMessage(userMessage);
        generateMultiFileStream(userMessage, callback, appId, null);
    }

    // ==================== 私有实现 ====================

    private CodeGenResult generateHtml(String userMessage, Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        HtmlCodeResult result = aiCodeGeneratorService.generateCode(prompt);
        if (result == null || StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成有效的代码");
        }
        File saveDir = CodeSaver.saveHtml(result, appId).dir();
        log.info("HTML 代码生成并保存成功，目录：{}", saveDir.getAbsolutePath());
        return buildHtmlResult(result, saveDir);
    }

    private CodeGenResult generateMultiFile(String userMessage, Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiCode(prompt);
        if (result == null || StrUtil.hasBlank(result.getHtmlCode(), result.getCssCode(), result.getJsCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成有效的多文件代码");
        }
        File saveDir = CodeSaver.saveMultiFile(result, appId).dir();
        log.info("多文件代码生成并保存成功，目录：{}", saveDir.getAbsolutePath());
        return buildMultiFileResult(result, saveDir);
    }

    private void generateHtmlStream(String userMessage, CodeGenStreamCallback callback, Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeStream(prompt);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleHtmlComplete(response, callback, appId))
                .onError(callback::onError)
                .start();
    }

    private void generateMultiFileStream(String userMessage, CodeGenStreamCallback callback,
                                         Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        TokenStream tokenStream = aiCodeGeneratorService.generateMultiCodeStream(prompt);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleMultiFileComplete(response, callback, appId))
                .onError(callback::onError)
                .start();
    }

    private void handleHtmlComplete(ChatResponse response, CodeGenStreamCallback callback, Long appId) {
        try {
            // 交给解析器：JSON / Markdown / 裸 HTML 都能处理
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.HTML);
            File saveDir = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.HTML.getValue(), appId).dir();
            log.info("HTML 代码流式生成并保存成功，目录：{}", saveDir.getAbsolutePath());
            callback.onComplete(buildResultFromFiles(parsed, CodeGenTypeEnum.HTML, saveDir));
        } catch (CodeParserException e) {
            callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage()));
        } catch (Exception e) {
            log.error("HTML 流式结果处理失败", e);
            callback.onError(e);
        }
    }

    private void handleMultiFileComplete(ChatResponse response, CodeGenStreamCallback callback, Long appId) {
        try {
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.MULTI_FILE);
            File saveDir = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.MULTI_FILE.getValue(), appId).dir();
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

    /** 解析应用的生成类型；应用未配置有效类型时给出清晰提示 */
    private CodeGenTypeEnum resolveAppType(App app) {
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (type == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用未配置有效的代码生成类型");
        }
        return type;
    }

    /** 加载应用；应用不存在抛 NOT_FOUND */
    private App loadApp(Long appId) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }

    /**
     * 拼接发往 AI 的完整 prompt：应用的 initPrompt 作为基础指令，叠加本次需求。
     * 基础模式 initPrompt 为 null 时原样返回用户消息。
     */
    private String buildPrompt(String initPrompt, String userMessage) {
        if (StrUtil.isBlank(initPrompt)) {
            return userMessage;
        }
        return StrUtil.format("{}\n\n用户需求：\n{}", initPrompt, userMessage);
    }

    private void validateUserMessage(String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "需求描述不能为空");
        }
    }

    /** 把解析出的代码文件归一化成对外结果实体 */
    private CodeGenResult buildResultFromFiles(CodeParseResult parsed, CodeGenTypeEnum type, File saveDir) {
        List<String> fileNames = parsed.files().stream().map(CodeFile::name).toList();
        CodeGenResult.CodeGenResultBuilder builder = CodeGenResult.builder()
                .codeGenType(type.getValue())
                .description(parsed.description())
                .saveDir(saveDir.getAbsolutePath())
                .fileNames(fileNames);
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
