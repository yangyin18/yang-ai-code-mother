package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorService;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodeGenFacade 流式方法单元测试。
 *
 * <p>不启动 Spring、不调用真实 LLM：mock 住 {@link AiCodeGeneratorService} 返回的
 * {@link TokenStream}，分别触发 partial / complete / error 回调，验证门面层的
 * 文本转发、JSON 解析、落盘编排与异常语义。
 */
class CodeGenFacadeStreamingTest {

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @InjectMocks
    private CodeGenFacade codeGenFacade;

    private MockedStatic<CodeFileSaver> codeFileSaver;

    private AutoCloseable mockitoResources;

    private static final String SAVE_DIR = "E:/tmp/code_output/html_stream_1";

    /** 简单的回调收集器，把各个阶段的结果记下来供断言 */
    private static class CallbackCollector implements CodeGenStreamCallback {
        final List<String> partials = new ArrayList<>();
        CodeGenResult result;
        Throwable error;

        @Override
        public void onPartial(String partialText) {
            partials.add(partialText);
        }

        @Override
        public void onComplete(CodeGenResult result) {
            this.result = result;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }
    }

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

    private TokenStream mockTokenStream() {
        TokenStream tokenStream = mock(TokenStream.class);
        when(tokenStream.onPartialResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onCompleteResponse(any())).thenReturn(tokenStream);
        when(tokenStream.onError(any())).thenReturn(tokenStream);
        return tokenStream;
    }

    private void fireComplete(TokenStream tokenStream, String json) {
        ArgumentCaptor<Consumer<ChatResponse>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(tokenStream).onCompleteResponse(captor.capture());
        captor.getValue().accept(ChatResponse.builder().aiMessage(AiMessage.from(json)).build());
    }

    private void firePartial(TokenStream tokenStream, String text) {
        ArgumentCaptor<Consumer<String>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(tokenStream).onPartialResponse(captor.capture());
        captor.getValue().accept(text);
    }

    private void fireError(TokenStream tokenStream, Throwable error) {
        ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(tokenStream).onError(captor.capture());
        captor.getValue().accept(error);
    }

    // ==================== 正常流程 ====================

    @Test
    void generateHtmlStream_shouldForwardPartialAndComplete() {
        TokenStream tokenStream = mockTokenStream();
        when(aiCodeGeneratorService.generateCodeStream("博客")).thenReturn(tokenStream);
        codeFileSaver.when(() -> CodeFileSaver.saveFiles(any(), anyString()))
                .thenReturn(new File(SAVE_DIR));
        CallbackCollector callback = new CallbackCollector();

        codeGenFacade.generateHtmlStream("博客", callback);

        // 增量文本被转发给调用方
        firePartial(tokenStream, "{\"htm");
        firePartial(tokenStream, "lCode\"");
        assertEquals(List.of("{\"htm", "lCode\""), callback.partials);

        // 完整响应被解析、落盘并回调 onComplete
        fireComplete(tokenStream, "{\"htmlCode\":\"<html>stream</html>\",\"description\":\"流式博客\"}");
        assertNull(callback.error);
        assertNotNull(callback.result);
        assertEquals("html", callback.result.getCodeGenType());
        assertEquals("<html>stream</html>", callback.result.getHtmlCode());
        assertEquals("流式博客", callback.result.getDescription());
        assertEquals(new File(SAVE_DIR).getAbsolutePath(), callback.result.getSaveDir());
        assertEquals(List.of("index.html"), callback.result.getFileNames());

        verify(aiCodeGeneratorService).generateCodeStream("博客");
        codeFileSaver.verify(() -> CodeFileSaver.saveFiles(any(), anyString()));
        verify(tokenStream).start();
    }

    @Test
    void generateMultiFileStream_shouldParseAndComplete() {
        TokenStream tokenStream = mockTokenStream();
        when(aiCodeGeneratorService.generateMultiCodeStream("博客")).thenReturn(tokenStream);
        codeFileSaver.when(() -> CodeFileSaver.saveFiles(any(), anyString()))
                .thenReturn(new File(SAVE_DIR));
        CallbackCollector callback = new CallbackCollector();

        codeGenFacade.generateMultiFileStream("博客", callback);

        fireComplete(tokenStream,
                "{\"htmlCode\":\"<html>\",\"cssCode\":\"body{}\",\"jsCode\":\"js()\",\"description\":\"多文件\"}");
        assertNull(callback.error);
        assertNotNull(callback.result);
        assertEquals("multi_file", callback.result.getCodeGenType());
        assertEquals("<html>", callback.result.getHtmlCode());
        assertEquals("body{}", callback.result.getCssCode());
        assertEquals("js()", callback.result.getJsCode());
        assertEquals(List.of("index.html", "style.css", "script.js"), callback.result.getFileNames());

        codeFileSaver.verify(() -> CodeFileSaver.saveFiles(any(), anyString()));
        verify(tokenStream).start();
    }

    // ==================== 异常路径 ====================

    @Test
    void generateHtmlStream_badJson_shouldCallError() {
        TokenStream tokenStream = mockTokenStream();
        when(aiCodeGeneratorService.generateCodeStream(anyString())).thenReturn(tokenStream);
        CallbackCollector callback = new CallbackCollector();

        codeGenFacade.generateHtmlStream("博客", callback);
        fireComplete(tokenStream, "not a json");

        assertNull(callback.result);
        assertNotNull(callback.error, "非法 JSON 应回调 onError");
        codeFileSaver.verifyNoInteractions();
    }

    @Test
    void generateHtmlStream_blankCode_shouldCallSystemError() {
        TokenStream tokenStream = mockTokenStream();
        when(aiCodeGeneratorService.generateCodeStream(anyString())).thenReturn(tokenStream);
        CallbackCollector callback = new CallbackCollector();

        codeGenFacade.generateHtmlStream("博客", callback);
        fireComplete(tokenStream, "{\"htmlCode\":\" \",\"description\":\"空\"}");

        assertNull(callback.result);
        assertNotNull(callback.error);
        assertTrue(callback.error instanceof BusinessException);
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), ((BusinessException) callback.error).getCode());
        codeFileSaver.verifyNoInteractions();
    }

    @Test
    void generateHtmlStream_aiError_shouldForwardError() {
        TokenStream tokenStream = mockTokenStream();
        when(aiCodeGeneratorService.generateCodeStream(anyString())).thenReturn(tokenStream);
        CallbackCollector callback = new CallbackCollector();

        codeGenFacade.generateHtmlStream("博客", callback);
        fireError(tokenStream, new RuntimeException("网络中断"));

        assertNull(callback.result);
        assertNotNull(callback.error);
        assertEquals("网络中断", callback.error.getMessage());
    }

    // ==================== 参数校验（同步抛异常） ====================

    @Test
    void generateStream_unknownType_shouldThrowParamsError() {
        CallbackCollector callback = new CallbackCollector();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> codeGenFacade.generateStream("博客", "vue", callback));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generateHtmlStream_blankMessage_shouldThrowParamsError() {
        CallbackCollector callback = new CallbackCollector();
        assertThrows(BusinessException.class, () -> codeGenFacade.generateHtmlStream("", callback));
        assertThrows(BusinessException.class, () -> codeGenFacade.generateHtmlStream(null, callback));
        verifyNoInteractions(aiCodeGeneratorService);
    }
}
