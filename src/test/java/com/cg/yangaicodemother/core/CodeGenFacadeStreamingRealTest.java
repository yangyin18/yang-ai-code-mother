package com.cg.yangaicodemother.core;

import com.cg.yangaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.cg.yangaicodemother.ai.ChatModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeGenFacade 流式方法真实调用 LLM 的端到端测试。
 *
 * <p>验证 StreamingChatModel 接线正确：partial 回调确实触发（增量文本打屏），
 * 完成后 JSON 解析并落盘。需要联网且 api-key 有效。
 */
@SpringBootTest(
        classes = {ChatModelConfig.class, AiCodeGeneratorServiceFactory.class, CodeGenFacade.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class CodeGenFacadeStreamingRealTest {

    @Autowired
    private CodeGenFacade codeGenFacade;

    @Test
    void generateHtmlStream_shouldStreamAndSave() throws Exception {
        CompletableFuture<CodeGenResult> future = new CompletableFuture<>();
        List<String> partials = new ArrayList<>();

        codeGenFacade.generateHtmlStream("做一个简单的博客，不超过20行代码", new CodeGenStreamCallback() {
            @Override
            public void onPartial(String partialText) {
                partials.add(partialText);
                System.out.print(partialText);
            }

            @Override
            public void onComplete(CodeGenResult result) {
                future.complete(result);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        CodeGenResult result = future.get(180, TimeUnit.SECONDS);
        System.out.println();
        System.out.println("========== 流式 generateHtmlStream 结果 ==========");
        System.out.println("partial 回调次数 = " + partials.size());
        System.out.println("saveDir          = " + result.getSaveDir());
        System.out.println("fileNames        = " + result.getFileNames());
        System.out.println("================================================");

        assertTrue(partials.size() > 0, "应至少触发一次 partial 回调");
        assertNotNull(result.getHtmlCode());
        assertTrue(result.getHtmlCode().length() > 0, "HTML 代码不应为空");
        File dir = new File(result.getSaveDir());
        assertTrue(dir.isDirectory(), "保存目录应存在");
        assertTrue(new File(dir, "index.html").isFile(), "应生成 index.html");
    }
}
