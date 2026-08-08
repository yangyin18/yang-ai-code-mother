package com.cg.yangaicodemother.ai;

import com.cg.yangaicodemother.ai.model.HtmlCodeResult;
import com.cg.yangaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * AiCodeGeneratorService 单元测试。
 *
 * <p>不启动 Spring 容器（不依赖 MySQL / 真实 LLM API / 网络），
 * 用 Mockito 桩住 ChatModel，验证 AiServices 代理能正常返回模型结果，
 * 且 @SystemMessage(fromResource=...) 指定的提示词资源确实被拼进了发给模型的请求。
 */
class AiCodeGeneratorServiceTest {

    /**
     * 对任何返回 ChatResponse 的调用统一返回固定结果，并捕获请求中的 ChatRequest。
     *
     * <p>注意：langchain4j 对 POJO 返回类型（HtmlCodeResult / MultiFileCodeResult）
     * 按 JSON 反序列化，因此桩返回的内容必须是合法 JSON（多出的字段会被忽略，
     * 这份 JSON 同时兼容两个结果实体）。
     */
    private static final String MOCK_JSON = "{\"htmlCode\":\"<html>mock</html>\","
            + "\"cssCode\":\"body{margin:0}\","
            + "\"jsCode\":\"console.log('hi')\","
            + "\"description\":\"mock desc\"}";

    /** 对任何返回 ChatResponse 的调用统一返回固定结果，并捕获请求中的 ChatRequest */
    private final class StubChatModelAnswer implements Answer<Object> {
        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            if (invocation.getMethod().getReturnType() == ChatResponse.class) {
                for (Object arg : invocation.getArguments()) {
                    if (arg instanceof ChatRequest) {
                        lastRequest.set((ChatRequest) arg);
                    }
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(MOCK_JSON))
                        .tokenUsage(new TokenUsage(5, 10))
                        .finishReason(FinishReason.STOP)
                        .build();
            }
            if (invocation.getMethod().getReturnType() == ChatRequestParameters.class) {
                return ChatRequestParameters.builder().build();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        }
    }

    private AiCodeGeneratorService aiCodeGeneratorService;
    private final AtomicReference<ChatRequest> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class, new StubChatModelAnswer());
        aiCodeGeneratorService = AiServices.create(AiCodeGeneratorService.class, chatModel);
    }

    @Test
    void generateCode() {
        HtmlCodeResult result = aiCodeGeneratorService.generateCode("做一个简单的博客，不超过20行代码");
        assertEquals("<html>mock</html>", result.getHtmlCode());
        assertEquals("mock desc", result.getDescription());
        assertSystemPromptContains("整合到一个 HTML 文件");
    }

    @Test
    void generateMultiCode() {
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiCode("做一个简单的博客，不超过50行代码");

        assertEquals("<html>mock</html>", result.getHtmlCode());
        assertEquals("body{margin:0}", result.getCssCode());
        assertEquals("console.log('hi')", result.getJsCode());
        assertSystemPromptContains("三个核心文件");
    }

    /** 校验发给模型的请求里，系统提示词确实来自指定的提示词资源 */
    private void assertSystemPromptContains(String keyword) {
        ChatRequest request = lastRequest.get();
        assertNotNull(request, "模型应收到一个 ChatRequest");
        boolean hit = request.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .anyMatch(m -> m.text() != null && m.text().contains(keyword));
        assertTrue(hit, "请求应包含系统提示词，其中包含: " + keyword);
    }
}
