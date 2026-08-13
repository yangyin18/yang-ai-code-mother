package com.cg.yangaicodemother.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.ai.AiVueProjectService;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.ai.tools.VueProjectTokenBudget;
import com.cg.yangaicodemother.ai.tools.VueProjectTool;
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
import com.cg.yangaicodemother.service.VueImportRepairer;
import com.cg.yangaicodemother.service.VueProjectScaffolder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
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

    /** Vue 项目生成专用的流式模型（带 max_tokens 硬上限），见 ChatModelConfig#vueStreamingChatModel */
    @Resource
    private StreamingChatModel vueStreamingChatModel;

    /** 通用 ChatModel，供 Vue 同步生成路径使用（工具层的 token 预算仍兜底累计用量） */
    @Resource
    private ChatModel chatModel;

    /** Vue 项目生成的 token 硬上限（模型 max_tokens + 工具累计预算），默认 20000 */
    @Value("${code.vue.max-tokens:20000}")
    private Integer vueMaxTokens;

    /** Vue 项目文件数上限（对应提示词「文件总数＜30」），默认 30 */
    @Value("${code.vue.max-files:30}")
    private Integer vueMaxFiles;

    /** 生成完成后是否执行黄金兜底（补关键文件 + 修复缺失引用），默认开启 */
    @Value("${code.vue.scaffold-on-generate:true}")
    private Boolean vueScaffoldOnGenerate;

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
            case VUE_PROJECT -> generateVueProject(userMessage, app.getId(), app.getInitPrompt());
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
            case VUE_PROJECT -> generateVueProject(userMessage, appId, null);
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
            case VUE_PROJECT -> generateVueProjectStream(userMessage, callback, app.getId(), app.getInitPrompt());
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
            case VUE_PROJECT -> generateVueProjectStream(userMessage, callback, appId, null);
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
        // 统一净化：移除生成代码里的一切外部链接与跳转（安全兜底）
        CodeSanitizer.sanitize(result);
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
        // 统一净化：移除生成代码里的一切外部链接与跳转（安全兜底）
        CodeSanitizer.sanitize(result);
        File saveDir = CodeSaver.saveMultiFile(result, appId).dir();
        log.info("多文件代码生成并保存成功，目录：{}", saveDir.getAbsolutePath());
        return buildMultiFileResult(result, saveDir);
    }

    private void generateHtmlStream(String userMessage, CodeGenStreamCallback callback, Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        generateHtmlWithRetry(prompt, callback, appId, 1);
    }

    private void generateMultiFileStream(String userMessage, CodeGenStreamCallback callback,
                                         Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        generateMultiWithRetry(prompt, callback, appId, 1);
    }

    /**
     * 带「解析失败重试」的 HTML 流式生成：模型偶尔会输出对话性文本（如反问需求）而非代码，
     * 首次解析失败后用「上次输出不可用」的强约束提示重试一次，尽量保证应用有代码落盘。
     * 重试通过同一 SSE 流推送（前端 appUpdated 后以结构化结果覆盖展示），只在两次都失败时才 onError。
     */
    private void generateHtmlWithRetry(String prompt, CodeGenStreamCallback callback, Long appId, int attemptsLeft) {
        TokenStream tokenStream = aiCodeGeneratorService.generateCodeStream(prompt);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleHtmlComplete(response, callback, appId, prompt, attemptsLeft))
                .onError(callback::onError)
                .start();
    }

    private void generateMultiWithRetry(String prompt, CodeGenStreamCallback callback, Long appId, int attemptsLeft) {
        TokenStream tokenStream = aiCodeGeneratorService.generateMultiCodeStream(prompt);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleMultiFileComplete(response, callback, appId, prompt, attemptsLeft))
                .onError(callback::onError)
                .start();
    }

    private void handleHtmlComplete(ChatResponse response, CodeGenStreamCallback callback, Long appId,
                                    String prompt, int attemptsLeft) {
        // 客户端中途退出（断开连接）后门面标记取消：不再解析、落盘或推送，及时中断生成
        if (callback.isCancelled()) {
            log.info("生成已取消（客户端已断开），跳过 HTML 流式结果保存");
            return;
        }
        try {
            // 交给解析器：JSON / Markdown / 裸 HTML 都能处理
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.HTML);
            File saveDir = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.HTML.getValue(), appId).dir();
            log.info("HTML 代码流式生成并保存成功，目录：{}", saveDir.getAbsolutePath());
            callback.onComplete(buildResultFromFiles(parsed, CodeGenTypeEnum.HTML, saveDir));
        } catch (CodeParserException e) {
            if (attemptsLeft > 0) {
                log.warn("HTML 解析失败，用强约束提示重试一次（剩余 {} 次），原因：{}", attemptsLeft, e.getMessage());
                String retryPrompt = prompt + "\n\n注意：你上一次的输出没有解析出有效代码（"
                        + e.getMessage() + "）。请严格只输出符合要求的 JSON 代码，绝对不要输出任何解释、提问或 Markdown 标记。";
                generateHtmlWithRetry(retryPrompt, callback, appId, attemptsLeft - 1);
            } else {
                callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage()));
            }
        } catch (Exception e) {
            log.error("HTML 流式结果处理失败", e);
            callback.onError(e);
        }
    }

    private void handleMultiFileComplete(ChatResponse response, CodeGenStreamCallback callback, Long appId,
                                         String prompt, int attemptsLeft) {
        // 客户端中途退出（断开连接）后门面标记取消：不再解析、落盘或推送，及时中断生成
        if (callback.isCancelled()) {
            log.info("生成已取消（客户端已断开），跳过多文件流式结果保存");
            return;
        }
        try {
            CodeParseResult parsed = CodeParser.parse(response.aiMessage().text(), CodeGenTypeEnum.MULTI_FILE);
            File saveDir = CodeSaver.saveFiles(parsed.files(), CodeGenTypeEnum.MULTI_FILE.getValue(), appId).dir();
            log.info("多文件代码流式生成并保存成功，目录：{}", saveDir.getAbsolutePath());
            callback.onComplete(buildResultFromFiles(parsed, CodeGenTypeEnum.MULTI_FILE, saveDir));
        } catch (CodeParserException e) {
            if (attemptsLeft > 0) {
                log.warn("多文件解析失败，用强约束提示重试一次（剩余 {} 次），原因：{}", attemptsLeft, e.getMessage());
                String retryPrompt = prompt + "\n\n注意：你上一次的输出没有解析出有效代码（"
                        + e.getMessage() + "）。请严格只输出符合要求的 JSON 代码，绝对不要输出任何解释、提问或 Markdown 标记。";
                generateMultiWithRetry(retryPrompt, callback, appId, attemptsLeft - 1);
            } else {
                callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, e.getMessage()));
            }
        } catch (Exception e) {
            log.error("多文件流式结果处理失败", e);
            callback.onError(e);
        }
    }

    // ==================== Vue 项目生成 ====================

    /**
     * Vue 项目生成（同步）。模型通过 writeFile 工具把项目文件逐个写到
     * {@code vue_{appId}} 目录，完成后用工具记录的路径与描述组装结果。
     */
    private CodeGenResult generateVueProject(String userMessage, Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        String projectDir = prepareVueDir(appId);
        VueProjectTokenBudget budget = new VueProjectTokenBudget(vueMaxTokens, vueMaxFiles);
        VueProjectTool tool = new VueProjectTool(projectDir, budget, null);
        AiVueProjectService service = buildVueService(tool);
        String finalText = service.generateVueProject(prompt);
        List<String> fileNames = tool.writtenPaths();
        if (fileNames.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成任何项目文件");
        }
        // 黄金兜底：补缺失的关键文件 + 修复缺失引用，让磁盘源码可构建、可下载
        hardenVueProject(projectDir);
        log.info("Vue 项目生成并保存成功，目录：{}，文件数：{}，估算 tokens：{}",
                projectDir, fileNames.size(), tool.usedTokens());
        return buildVueResult(tool, projectDir, finalText);
    }

    /**
     * Vue 项目流式生成。模型先输出生成计划（onPartial 增量文本），
     * 再通过 writeFile 工具逐个写文件（onFileWritten 回调），最后输出完毕提示。
     */
    private void generateVueProjectStream(String userMessage, CodeGenStreamCallback callback,
                                          Long appId, String initPrompt) {
        String prompt = buildPrompt(initPrompt, userMessage);
        String projectDir = prepareVueDir(appId);
        VueProjectTokenBudget budget = new VueProjectTokenBudget(vueMaxTokens, vueMaxFiles);
        // 客户端断开后门面标记取消，writeFile 据此拒绝继续落盘，及时中断 Vue 项目生成
        VueProjectTool tool = new VueProjectTool(projectDir, budget, callback::onFileWritten, callback::isCancelled);
        AiVueProjectService service = buildVueService(tool);
        TokenStream tokenStream = service.generateVueProjectStream(prompt);
        tokenStream
                .onPartialResponse(callback::onPartial)
                .onCompleteResponse(response -> handleVueComplete(response, callback, projectDir, tool))
                .onError(callback::onError)
                .start();
    }

    /**
     * Vue 流式完成回调：用工具记录的相对路径列表 + 项目描述组装结果。
     * 同时记录模型侧真实 token 用量（Response#tokenUsage）与工具侧估算用量到日志。
     */
    private void handleVueComplete(ChatResponse response, CodeGenStreamCallback callback,
                                   String projectDir, VueProjectTool tool) {
        // 客户端中途退出（断开连接）后门面标记取消：不再加固 / 落盘 / 推送，及时中断生成
        if (callback.isCancelled()) {
            log.info("生成已取消（客户端已断开），跳过 Vue 项目结果加固与保存");
            return;
        }
        try {
            List<String> fileNames = tool.writtenPaths();
            if (fileNames.isEmpty()) {
                callback.onError(new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未生成任何项目文件"));
                return;
            }
            // 黄金兜底：补缺失的关键文件 + 修复缺失引用，让磁盘源码可构建、可下载
            hardenVueProject(projectDir);
            TokenUsage usage = response.tokenUsage();
            log.info("Vue 项目流式生成完成，目录：{}，文件数：{}，估算 tokens：{}，模型输出 tokens：{}",
                    projectDir, fileNames.size(), tool.usedTokens(),
                    usage == null ? 0 : usage.outputTokenCount());
            callback.onComplete(buildVueResult(tool, projectDir, response.aiMessage().text()));
        } catch (Exception e) {
            log.error("Vue 项目流式结果处理失败", e);
            callback.onError(e);
        }
    }

    /** 准备 Vue 项目保存目录：清空旧产物，保证「同一应用再次生成」不残留过期文件 */
    private String prepareVueDir(Long appId) {
        String dir = CodeSaver.resolveDir(CodeGenTypeEnum.VUE_PROJECT.getValue(), appId);
        FileUtil.del(dir);
        FileUtil.mkdir(dir);
        return dir;
    }

    /**
     * Vue 生成期加固：用黄金模板补齐缺失的关键文件（package.json / vite.config.js / index.html /
     * src/main.js / src/App.vue / src/router/index.js），再修复缺失的相对导入引用。
     * 使磁盘上的源码（含下载 ZIP）本身就是完整、可构建、可运行的工程。
     */
    private void hardenVueProject(String projectDir) {
        if (!Boolean.TRUE.equals(vueScaffoldOnGenerate)) {
            log.info("code.vue.scaffold-on-generate 关闭，跳过生成期加固");
            return;
        }
        List<String> scaffolded = VueProjectScaffolder.scaffold(new File(projectDir));
        int repaired = VueImportRepairer.repair(new File(projectDir));
        if (!scaffolded.isEmpty() || repaired > 0) {
            log.info("Vue 生成期加固完成：补齐基础文件 {} 个（{}），修复缺失引用 {} 个",
                    scaffolded.size(), scaffolded, repaired);
        }
    }

    /** 用 langchain4j AiServices 动态构建带 writeFile 工具的 Vue 生成服务（每次生成一个新实例） */
    private AiVueProjectService buildVueService(VueProjectTool tool) {
        return AiServices.builder(AiVueProjectService.class)
                .chatModel(chatModel)
                .streamingChatModel(vueStreamingChatModel)
                .tools(tool)
                .build();
    }

    /**
     * Vue 模式结果只含相对路径列表与描述，不携带代码内容（避免无谓的 token/传输开销）。
     * fileNames 为兜底后的实际落盘文件（排序稳定），使前端文件列表与下载 ZIP 包含补齐文件。
     */
    private CodeGenResult buildVueResult(VueProjectTool tool, String projectDir, String fallbackDescription) {
        return CodeGenResult.builder()
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .description(StrUtil.blankToDefault(tool.summary(), fallbackDescription))
                .saveDir(projectDir)
                .fileNames(listProjectFiles(new File(projectDir)))
                .build();
    }

    /** 递归列出工程实际文件（相对路径，排除 node_modules / dist / .git），排序保证稳定 */
    private List<String> listProjectFiles(File projectDir) {
        List<String> names = new ArrayList<>();
        collectProjectFiles(projectDir, projectDir, names);
        names.sort(String::compareTo);
        return names;
    }

    private void collectProjectFiles(File root, File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if ("node_modules".equals(name) || "dist".equals(name) || ".git".equals(name)) {
                    continue;
                }
                collectProjectFiles(root, f, out);
            } else {
                out.add(root.toPath().relativize(f.toPath()).toString().replace('\\', '/'));
            }
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
