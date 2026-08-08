package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
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
 * 用 Mockito mock 住 {@link AiCodeGeneratorService}，并用 mockStatic 桩掉 {@link CodeFileSaver}，
 * 只验证门面自身的编排逻辑（类型分发、结果组装、参数/结果校验、异常语义）。
 */
class CodeGenFacadeTest {

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @InjectMocks
    private CodeGenFacade codeGenFacade;

    private MockedStatic<CodeFileSaver> codeFileSaver;

    private AutoCloseable mockitoResources;

    private static final String SAVE_DIR = "E:/tmp/code_output/html_1";

    @BeforeEach
    void setUp() {
        mockitoResources = MockitoAnnotations.openMocks(this);
        codeFileSaver = Mockito.mockStatic(CodeFileSaver.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        codeFileSaver.close();
        mockitoResources.close();
    }

    // ==================== 正常流程 ====================

    @Test
    void generate_html_shouldCallAiServiceAndBuildResult() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>blog</html>");
        aiResult.setDescription("一个博客");
        when(aiCodeGeneratorService.generateCode("做一个博客")).thenReturn(aiResult);
        codeFileSaver.when(() -> CodeFileSaver.saveHtmlCodeResult(aiResult)).thenReturn(new File(SAVE_DIR));

        CodeGenResult result = codeGenFacade.generate("做一个博客", "html");

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
        codeFileSaver.verify(() -> CodeFileSaver.saveHtmlCodeResult(aiResult));
    }

    @Test
    void generate_multiFile_shouldCallAiServiceAndBuildResult() {
        MultiFileCodeResult aiResult = new MultiFileCodeResult();
        aiResult.setHtmlCode("<html>");
        aiResult.setCssCode("body{}");
        aiResult.setJsCode("console.log(1)");
        aiResult.setDescription("多文件博客");
        when(aiCodeGeneratorService.generateMultiCode("做一个博客")).thenReturn(aiResult);
        codeFileSaver.when(() -> CodeFileSaver.saveMultiFileCodeResult(aiResult)).thenReturn(new File(SAVE_DIR));

        CodeGenResult result = codeGenFacade.generate("做一个博客", "multi_file");

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
        codeFileSaver.verify(() -> CodeFileSaver.saveMultiFileCodeResult(aiResult));
    }

    @Test
    void generateHtml_shouldWorkDirectlyWithoutTypeParam() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode("<html>blog</html>");
        when(aiCodeGeneratorService.generateCode("博客")).thenReturn(aiResult);
        codeFileSaver.when(() -> CodeFileSaver.saveHtmlCodeResult(any(HtmlCodeResult.class))).thenReturn(new File(SAVE_DIR));

        CodeGenResult result = codeGenFacade.generateHtml("博客");

        assertEquals("html", result.getCodeGenType());
        verify(aiCodeGeneratorService).generateCode("博客");
    }

    // ==================== 参数校验 ====================

    @Test
    void generate_unknownType_shouldThrowParamsError() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("博客", "vue"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generate_nullType_shouldThrowParamsError() {
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("博客", null));
    }

    @Test
    void generate_blankMessage_shouldThrowParamsError() {
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("", "html"));
        assertThrows(BusinessException.class, () -> codeGenFacade.generate(null, "html"));
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("   ", "html"));
        verifyNoInteractions(aiCodeGeneratorService);
    }

    // ==================== AI 结果校验 ====================

    @Test
    void generateHtml_blankAiCode_shouldThrowSystemError() {
        HtmlCodeResult aiResult = new HtmlCodeResult();
        aiResult.setHtmlCode(" ");
        when(aiCodeGeneratorService.generateCode(anyString())).thenReturn(aiResult);

        BusinessException ex = assertThrows(BusinessException.class, () -> codeGenFacade.generateHtml("博客"));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
        codeFileSaver.verifyNoInteractions();
    }

    @Test
    void generateHtml_nullAiResult_shouldThrowSystemError() {
        when(aiCodeGeneratorService.generateCode(anyString())).thenReturn(null);
        assertThrows(BusinessException.class, () -> codeGenFacade.generateHtml("博客"));
    }

    @Test
    void generateMultiFile_blankFile_shouldThrowSystemError() {
        MultiFileCodeResult aiResult = new MultiFileCodeResult();
        aiResult.setHtmlCode("<html>");
        aiResult.setCssCode("");
        aiResult.setJsCode("console.log(1)");
        when(aiCodeGeneratorService.generateMultiCode(anyString())).thenReturn(aiResult);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generate("博客", "multi_file"));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    void generateMultiFile_nullAiResult_shouldThrowSystemError() {
        when(aiCodeGeneratorService.generateMultiCode(anyString())).thenReturn(null);
        assertThrows(BusinessException.class, () -> codeGenFacade.generate("博客", "multi_file"));
    }
}
