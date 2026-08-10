package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.core.saver.CodeSaveResult;
import com.cg.yangaicodemother.core.saver.CodeSaver;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.cg.yangaicodemother.service.AppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodeGenFacade 单元测试。
 *
 * <p>不启动 Spring、不调用真实 LLM、不写磁盘：
 * 用 Mockito mock 住 {@link AiCodeGeneratorService}，并用 mockStatic 桩掉 {@link CodeSaver}，
 * 只验证门面自身的编排逻辑（类型分发、结果组装、参数/结果校验、异常语义）。
 */
class CodeGenFacadeTest {

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Mock
    private AppService appService;

    @InjectMocks
    private CodeGenFacade codeGenFacade;

    private MockedStatic<CodeSaver> codeSaver;

    private AutoCloseable mockitoResources;

    private static final String SAVE_DIR = "E:/tmp/code_output/html_1";

    private static final Long APP_ID = 1L;

    @BeforeEach
    void setUp() {
        mockitoResources = MockitoAnnotations.openMocks(this);
        codeSaver = Mockito.mockStatic(CodeSaver.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        codeSaver.close();
        mockitoResources.close();
    }

    // ==================== 正常流程 ====================

    @Test
    void generate_html_shouldCallAiServiceAndBuildResult() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>blog</html>");
        aiResult.setDescription("一个博客");
        when(aiCodeGeneratorService.generateCode("做一个博客")).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveHtml(aiResult, APP_ID))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html")));

        CodeGenResult result = codeGenFacade.generate("做一个博客", "html", APP_ID);

        assertNotNull(result);
        assertEquals(CodeGenTypeEnum.HTML.getValue(), result.getCodeGenType());
        assertEquals("<html>blog</html>", result.getHtmlCode());
        assertEquals("一个博客", result.getDescription());
        assertEquals(new File(SAVE_DIR).getAbsolutePath(), result.getSaveDir());
        assertEquals(List.of("index.html"), result.getFileNames());
        assertNull(result.getCssCode(), "HTML 模式不应有 cssCode");
        assertNull(result.getJsCode(), "HTML 模式不应有 jsCode");

        verify(aiCodeGeneratorService).generateCode("做一个博客");
        verify(aiCodeGeneratorService, never()).generateMultiCode(anyString());
        codeSaver.verify(() -> CodeSaver.saveHtml(aiResult, APP_ID));
    }

    @Test
    void generate_multiFile_shouldCallAiServiceAndBuildResult() {
        MultiFileCodeResult aiResult = new MultiFileCodeResult();
        aiResult.setHtmlCode("<html>");
        aiResult.setCssCode("body{}");
        aiResult.setJsCode("console.log(1)");
        aiResult.setDescription("多文件博客");
        when(aiCodeGeneratorService.generateMultiCode("做一个博客")).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveMultiFile(aiResult, APP_ID))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html", "style.css", "script.js")));

        CodeGenResult result = codeGenFacade.generate("做一个博客", "multi_file", APP_ID);

        assertNotNull(result);
        assertEquals(CodeGenTypeEnum.MULTI_FILE.getValue(), result.getCodeGenType());
        assertEquals("<html>", result.getHtmlCode());
        assertEquals("body{}", result.getCssCode());
        assertEquals("console.log(1)", result.getJsCode());
        assertEquals("多文件博客", result.getDescription());
        assertEquals(new File(SAVE_DIR).getAbsolutePath(), result.getSaveDir());
        assertEquals(List.of("index.html", "style.css", "script.js"), result.getFileNames());

        verify(aiCodeGeneratorService).generateMultiCode("做一个博客");
        verify(aiCodeGeneratorService, never()).generateCode(anyString());
        codeSaver.verify(() -> CodeSaver.saveMultiFile(aiResult, APP_ID));
    }

    @Test
    void generateHtml_shouldWorkDirectlyWithoutTypeParam() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>blog</html>");
        when(aiCodeGeneratorService.generateCode("博客")).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveHtml(any(HtmlCodeResult.class), any()))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html")));

        CodeGenResult result = codeGenFacade.generateHtml("博客", APP_ID);

        assertEquals("html", result.getCodeGenType());
        verify(aiCodeGeneratorService).generateCode("博客");
    }

    // ==================== 参数校验 ====================

    @Test
    void generate_unknownType_shouldThrowParamsError() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("博客", "vue", APP_ID));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generate_nullType_shouldThrowParamsError() {
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("博客", null, APP_ID));
    }

    @Test
    void generate_blankMessage_shouldThrowParamsError() {
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("", "html", APP_ID));
        assertThrows(BusinessException.class, () -> codeGenFacade.generate(null, "html", APP_ID));
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("   ", "html", APP_ID));
        verifyNoInteractions(aiCodeGeneratorService);
    }

    // ==================== AI 结果校验 ====================

    @Test
    void generateHtml_blankAiCode_shouldThrowSystemError() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode(" ");
        when(aiCodeGeneratorService.generateCode(anyString())).thenReturn(aiResult);

        BusinessException ex = assertThrows(BusinessException.class, () -> codeGenFacade.generateHtml("博客", APP_ID));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
        codeSaver.verifyNoInteractions();
    }

    @Test
    void generateHtml_nullAiResult_shouldThrowSystemError() {
        when(aiCodeGeneratorService.generateCode(anyString())).thenReturn(null);
        assertThrows(BusinessException.class, () -> codeGenFacade.generateHtml("博客", APP_ID));
    }

    @Test
    void generateMultiFile_blankFile_shouldThrowSystemError() {
        MultiFileCodeResult aiResult = new MultiFileCodeResult();
        aiResult.setHtmlCode("<html>");
        aiResult.setCssCode("");
        aiResult.setJsCode("console.log(1)");
        when(aiCodeGeneratorService.generateMultiCode(anyString())).thenReturn(aiResult);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("博客", "multi_file", APP_ID));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void generateMultiFile_nullAiResult_shouldThrowSystemError() {
        when(aiCodeGeneratorService.generateMultiCode(anyString())).thenReturn(null);
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("博客", "multi_file", APP_ID));
    }

    // ==================== 应用模式（按 appId 生成） ====================

    @Test
    void generate_appMode_html_shouldCombineInitPromptAndUseAppType() {
        App app = new App();
        app.setId(APP_ID);
        app.setInitPrompt("你是前端专家");
        app.setCodeGenType("html");
        when(appService.getById(APP_ID)).thenReturn(app);

        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>app</html>");
        aiResult.setDescription("应用网页");
        String prompt = "你是前端专家\n\n用户需求：\n做一个登录页";
        when(aiCodeGeneratorService.generateCode(prompt)).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveHtml(aiResult, APP_ID))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html")));

        CodeGenResult result = codeGenFacade.generate("做一个登录页", APP_ID);

        assertEquals(CodeGenTypeEnum.HTML.getValue(), result.getCodeGenType());
        assertEquals("<html>app</html>", result.getHtmlCode());
        verify(appService).getById(APP_ID);
        verify(aiCodeGeneratorService).generateCode(prompt);
        codeSaver.verify(() -> CodeSaver.saveHtml(aiResult, APP_ID));
    }

    @Test
    void generate_appMode_multiFile_shouldUseAppType() {
        App app = new App();
        app.setId(APP_ID);
        app.setInitPrompt("你是前端专家");
        app.setCodeGenType("multi_file");
        when(appService.getById(APP_ID)).thenReturn(app);

        MultiFileCodeResult aiResult = new MultiFileCodeResult();
        aiResult.setHtmlCode("<html>");
        aiResult.setCssCode("body{}");
        aiResult.setJsCode("js()");
        String prompt = "你是前端专家\n\n用户需求：\n做一个登录页";
        when(aiCodeGeneratorService.generateMultiCode(prompt)).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveMultiFile(aiResult, APP_ID))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html", "style.css", "script.js")));

        CodeGenResult result = codeGenFacade.generate("做一个登录页", APP_ID);

        assertEquals(CodeGenTypeEnum.MULTI_FILE.getValue(), result.getCodeGenType());
        verify(appService).getById(APP_ID);
        verify(aiCodeGeneratorService).generateMultiCode(prompt);
    }

    @Test
    void generate_appMode_blankInitPrompt_shouldUseRawRequirement() {
        App app = new App();
        app.setId(APP_ID);
        app.setInitPrompt("   ");
        app.setCodeGenType("html");
        when(appService.getById(APP_ID)).thenReturn(app);

        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>x</html>");
        when(aiCodeGeneratorService.generateCode("做一个登录页")).thenReturn(aiResult);
        codeSaver.when(() -> CodeSaver.saveHtml(any(HtmlCodeResult.class), any()))
                .thenReturn(new CodeSaveResult(SAVE_DIR, List.of("index.html")));

        codeGenFacade.generate("做一个登录页", APP_ID);

        verify(aiCodeGeneratorService).generateCode("做一个登录页");
    }

    @Test
    void generate_appMode_appNotFound_shouldThrowNotFound() {
        when(appService.getById(APP_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("做一个登录页", APP_ID));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generate_appMode_nullAppId_shouldThrowParamsError() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("做一个登录页", null));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(appService);
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generate_appMode_missingType_shouldThrowParamsError() {
        App app = new App();
        app.setId(APP_ID);
        app.setInitPrompt("你是前端专家");
        app.setCodeGenType(null);
        when(appService.getById(APP_ID)).thenReturn(app);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("做一个登录页", APP_ID));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generate_appMode_blankRequirement_shouldThrowParamsError() {
        App app = new App();
        app.setId(APP_ID);
        app.setInitPrompt("你是前端专家");
        app.setCodeGenType("html");
        when(appService.getById(APP_ID)).thenReturn(app);

        assertThrows(BusinessException.class, () -> codeGenFacade.generate("   ", APP_ID));
        verifyNoInteractions(aiCodeGeneratorService);
    }
}
