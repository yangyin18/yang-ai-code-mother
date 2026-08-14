package com.cg.yangaicodemother.controller;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.ai.AiChatService;
import com.cg.yangaicodemother.annotation.AuthCheck;
import com.cg.yangaicodemother.common.BaseResponse;
import com.cg.yangaicodemother.common.ResultUtils;
import com.cg.yangaicodemother.core.CodeGenFacade;
import com.cg.yangaicodemother.core.CodeGenResult;
import com.cg.yangaicodemother.core.CodeGenStreamCallback;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.exception.ThrowUtils;
import com.cg.yangaicodemother.model.dto.ChatHistoryAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryCursorRequest;
import com.cg.yangaicodemother.model.dto.ChatHistoryQueryRequest;
import com.cg.yangaicodemother.model.dto.ChatMessageAddRequest;
import com.cg.yangaicodemother.model.dto.ChatSendRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.MessageTypeEnum;
import com.cg.yangaicodemother.model.vo.ChatConversationVO;
import com.cg.yangaicodemother.model.vo.ChatCursorVO;
import com.cg.yangaicodemother.model.vo.ChatHistoryVO;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.cg.yangaicodemother.service.AppService;
import com.cg.yangaicodemother.service.ChatHistoryService;
import com.mybatisflex.core.paginate.Page;
import dev.langchain4j.service.TokenStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话历史接口。
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    private final AiChatService aiChatService;

    /** 对话即改代码：文字回复完成后自动重新生成应用代码 */
    private final CodeGenFacade codeGenFacade;

    /** 对话即改代码：已部署应用自动重新部署到 nginx */
    private final AppService appService;

    /**
     * 流式心跳调度器：SSE 首 token 可能较慢(模型侧波动)，每 5s 推一个 heartbeat，
     * 让前端能确认连接存活、展示真实等待时间。守护线程 + 共享实例。
     */
    private static final ScheduledExecutorService SSE_HEARTBEAT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat-chat");
                t.setDaemon(true);
                return t;
            });

    // ==================== 用户端：保存与查询 ====================

    /**
     * 保存一条对话消息（用户）。仅应用创建者与管理员可写。
     * POST /chat/add，body 示例：{"appId": 1, "messageType": "user", "message": "帮我做一个登录页"}
     *
     * @param addRequest 保存请求
     * @param request    HttpServletRequest
     * @return 是否成功
     */
    @PostMapping("/add")
    @AuthCheck
    public BaseResponse<Boolean> addMessage(@RequestBody ChatMessageAddRequest addRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(addRequest == null, ErrorCode.PARAMS_ERROR);
        boolean result = chatHistoryService.addMessage(addRequest, request);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "保存失败");
        return ResultUtils.success(true);
    }

    /**
     * 发送对话消息并流式接收 AI 回复（SSE）。完整闭环：
     *   落库用户消息(user) → 组装上下文(应用说明 + 最近对话) → 流式生成 AI 回复 → 落库(ai/error)。
     * 事件：
     *   started   连接建立
     *   heartbeat 每 5s 保活（首 token 前）
     *   message   增量文本（AI 回复正文）
     *   complete  落库的 AI 消息记录（JSON，含 id / createTime）
     *   error     AI 回复失败（已落库 error 消息）
     *   appUpdating 文字回复后开始自动改代码（对话即改代码）
     *   codeChunk 代码流式增量（原始 token，前端实时打出 + 节流刷新预览）
     *   appUpdated 代码生成完成（JSON，含新代码 / 部署地址）
     * POST /chat/send，body 示例：{"appId": 1, "message": "帮我把登录页改成深色主题"}
     *
     * @param sendRequest 发送请求
     * @param request     HttpServletRequest
     * @return SSE 流
     */
    @PostMapping("/send")
    @AuthCheck
    public SseEmitter sendMessage(@RequestBody ChatSendRequest sendRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(sendRequest == null || sendRequest.getAppId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(sendRequest.getMessage()),
                ErrorCode.PARAMS_ERROR, "消息内容不能为空");

        // 1. 落库用户消息（内部校验应用归属：创建者或管理员）
        ChatHistoryVO userMessage = chatHistoryService.sendUserMessage(sendRequest, request);

        // 2. 组装发给 AI 的消息：应用说明注入 system 模板，最近对话由 Redis 短缓存记忆提供
        //    （缓存未命中自动从 MySQL 重建，无需再手动拼历史进 userMessage）
        Long appId = sendRequest.getAppId();
        App app = appService.getById(appId);
        String appDescription = StrUtil.blankToDefault(app != null ? app.getInitPrompt() : null, "无应用说明");

        SseEmitter emitter = new SseEmitter(600_000L);

        // 统一互斥标记：所有终止分支（complete / error / timeout / 客户端断开）都从这里置位，
        // 保证只收尾一次。关键点：不再调用 emitter.completeWithError()——它会在 emitter.onError
        // 回调里再触发一次 onError，若回调里再 send()/completeWithError() 就形成递归写库
        // （曾造成客户端一断开，每轮回调落一条 error，1 秒几十条）。
        AtomicBoolean finished = new AtomicBoolean(false);
        // 客户端连接是否已断开（推送失败）：断开时流式文本不完整，落库应以完整响应为准
        AtomicBoolean disconnected = new AtomicBoolean(false);
        // 心跳任务引用（先声明再赋值，收尾工具里统一取消）
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();
        // 收尾工具：取消心跳；幂等，可被任意终止分支安全调用
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                ScheduledFuture<?> h = heartbeatRef.get();
                if (h != null) {
                    h.cancel(false);
                }
            }
        };

        // 首 token 前的保活心跳：每 5s 推一个 heartbeat 事件，让前端看到连接真实存活
        final long[] heartbeatStart = {System.currentTimeMillis()};
        ScheduledFuture<?> heartbeat = SSE_HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            if (finished.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data("{\"elapsedMs\":" + (System.currentTimeMillis() - heartbeatStart[0]) + "}"));
            } catch (IOException e) {
                // 客户端断开：标记并收尾即可，不再 completeWithError（会触发 onError 递归）
                disconnected.set(true);
                finish.run();
            }
        }, 5, 5, TimeUnit.SECONDS);
        heartbeatRef.set(heartbeat);

        emitter.onTimeout(() -> {
            finish.run();
            emitter.complete();
        });
        emitter.onError(e -> {
            // 连接级错误（客户端断开 / flush 失败）：只收尾。
            // 绝不能在此调用 emitter.send()（连接已不可用，必然再次抛错）
            // 或 emitter.completeWithError()（会再次进入本回调，递归写库）。
            disconnected.set(true);
            finish.run();
        });

        // 立即告知前端连接已建立
        try {
            emitter.send(SseEmitter.event().name("started").data("{}"));
        } catch (IOException e) {
            disconnected.set(true);
            finish.run();
            return emitter;
        }

        // 3. 流式请求 AI 回复，边收边推给前端，结束时落库
        StringBuilder replyBuilder = new StringBuilder();
        TokenStream tokenStream = aiChatService.chatStream(appId, appDescription, sendRequest.getMessage());
        tokenStream
                .onPartialResponse(partial -> {
                    // 过滤纯空白增量，避免拼出多余空格
                    if (StrUtil.isBlank(partial) || finished.get()) {
                        return;
                    }
                    replyBuilder.append(partial);
                    try {
                        emitter.send(SseEmitter.event().name("message").data(partial));
                    } catch (IOException e) {
                        // 客户端断开：停止推送；回复仍在后台完成，落库用完整响应文本
                        disconnected.set(true);
                        finish.run();
                    }
                })
                .onCompleteResponse(response -> withAppClassLoader(() -> {
                    finish.run();
                    // 以流式收到的文本为准落库（与前端展示一致）；
                    // 若连接已断开/流为空，回退到完整响应文本
                    String reply = replyBuilder.toString();
                    if (disconnected.get() || StrUtil.isBlank(reply)) {
                        reply = response.aiMessage().text();
                    }
                    try {
                        ChatHistoryVO aiMessage = chatHistoryService.addServerMessageVO(
                                sendRequest.getAppId(), userMessage.getUserId(), MessageTypeEnum.AI, reply);
                        // 客户端未断开时才推送 complete（断开时容器通常已自动 complete，
                        // 再 send 会抛 IllegalStateException 并误判为落库失败）
                        if (!disconnected.get()) {
                            try {
                                emitter.send(SseEmitter.event().name("complete").data(aiMessage));
                            } catch (IOException | IllegalStateException e) {
                                // 客户端断开 / emitter 已被容器完成：回复已落库，静默即可
                                disconnected.set(true);
                            }
                        }
                        // 对话即改代码：文字回复完成后，自动重新生成应用代码并重新部署，
                        // 通过 appUpdating / codeChunk / appUpdated 事件推给前端刷新预览。
                        // 失败只记录日志，不影响已落库的 AI 回复；SSE 由 applyChatUpdateToPreview 收尾关闭。
                        if (!disconnected.get()) {
                            applyChatUpdateToPreview(sendRequest.getAppId(), sendRequest.getMessage(),
                                    emitter, disconnected, request);
                        }
                    } catch (Exception e) {
                        log.error("AI 回复落库失败", e);
                    }
                }))
                .onError(error -> {
                    // AI 生成失败（非连接断开）才算真实失败：落 ERROR 并尝试通知前端
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        chatHistoryService.addServerMessageVO(
                                sendRequest.getAppId(), userMessage.getUserId(),
                                MessageTypeEnum.ERROR, "AI 回复失败：" + error.getMessage());
                        // 落库成功后才尝试通知前端；断开/已完成后静默
                        try {
                            emitter.send(SseEmitter.event().name("error").data("AI 回复失败，请稍后重试"));
                        } catch (IOException | IllegalStateException e) {
                            // 客户端已断开：错误已落库，静默即可
                        }
                        try {
                            emitter.complete();
                        } catch (IllegalStateException e) {
                            // 已被容器 complete，忽略
                        }
                    } catch (Exception ex) {
                        log.error("AI 回复失败落库异常", ex);
                    }
                })
                .start();
        return emitter;
    }

    /**
     * 以应用类加载器执行任务（AI 流式回调线程专用）。
     *
     * <p>langchain4j 的流式回调在内部线程池执行，这些线程的线程上下文类加载器（TCCL）
     * 在 Spring Boot 可执行 jar 里可能是 JVM 系统类加载器，读不到 BOOT-INF/classes 下的类。
     * 而回调里要落库、要重新生成代码，MyBatis-Flex 的 lambda 查询内部按 TCCL 解析实体类，
     * 会抛 ClassNotFoundException（历史故障：对话即改代码在流式线程上静默失败）。
     * 这里临时把 TCCL 切成应用类加载器（本控制器所在加载器），执行完恢复，保证回调线程上
     * 的任何数据库 / 类加载操作都可用。
     */
    private static void withAppClassLoader(Runnable action) {
        ClassLoader appLoader = ChatHistoryController.class.getClassLoader();
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        if (original != appLoader) {
            thread.setContextClassLoader(appLoader);
        }
        try {
            action.run();
        } finally {
            if (original != appLoader) {
                thread.setContextClassLoader(original);
            }
        }
    }

    /**
     * 「对话即改代码」：文字回复完成后，用完整对话上下文（应用说明 + 最近对话 + 用户最新消息）
     * 流式重新生成应用代码，已部署的应用复用 deployKey 重新部署。
     * 通过三个 SSE 事件通知前端实时刷新右侧代码 / 预览：
     * <ul>
     *   <li>appUpdating  开始应用修改（前端显示「正在应用你的修改」，清空流式代码区）；</li>
     *   <li>codeChunk    代码流式增量（原始 token，不 trim），前端实时打出并 400ms 节流刷新预览；</li>
     *   <li>appUpdated   完成，payload 含完整代码（未部署时 deployUrl 为 null，前端 srcdoc 预览）
     *                    或新部署地址（已部署时）。</li>
     * </ul>
     * SSE 流在本方法内收尾（codegen 的 onComplete / onError 里只调用一次 {@code emitter.complete()}），
     * 因此调用方在文字回复完成后不能再 complete，否则流会在代码流完前关闭。
     * 整个流程包 try/catch：codegen 或部署失败只记录日志，不影响已完成的文字回复。
     *
     * @param appId       应用 id
     * @param userMessage 当前用户消息
     * @param emitter     SSE 发射器
     * @param disconnected 客户端连接断开标记（推送失败时置位）
     */
    private void applyChatUpdateToPreview(Long appId, String userMessage,
                                          SseEmitter emitter, AtomicBoolean disconnected,
                                          HttpServletRequest request) {
        // appUpdating 只作为「开始应用修改」的信号：不携带任何目录/路径信息，
        // 避免把服务器上的绝对路径暴露给客户端
        try {
            emitter.send(SseEmitter.event().name("appUpdating").data("{}"));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开 / emitter 已被容器完成：无需继续
            disconnected.set(true);
            return;
        }
        // SSE 收尾只做一次：codegen 完成 / 失败 / 启动即失败，都只关一次流
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable closeStream = () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (IllegalStateException e) {
                    // 已被容器 complete，忽略
                }
            }
        };
        try {
            // 用完整上下文作为重新生成的需求，保证改动贴合对话脉络；生成类型取应用的 codeGenType
            String requirement = chatHistoryService.buildChatContext(appId, userMessage);
            codeGenFacade.generateStream(requirement, new CodeGenStreamCallback() {
                @Override
                public void onPartial(String partialText) {
                    // 代码以原始 token 流式推送（不 trim），纯空白增量忽略
                    if (disconnected.get() || StrUtil.isBlank(partialText)) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().name("codeChunk").data(partialText));
                    } catch (IOException | IllegalStateException e) {
                        // 客户端断开：停止推送
                        disconnected.set(true);
                    }
                }

                @Override
                public void onFileWritten(String path) {
                    // Vue 深度开发：每个项目文件真实写入后推 file 事件（只含相对路径），
                    // 前端据此展示「真实工具调用」——writeFile 工具逐个落盘，不是假进度条
                    if (disconnected.get() || StrUtil.isBlank(path)) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().name("file")
                                .data("{\"path\":\"" + escapeJson(path) + "\"}"));
                    } catch (IOException | IllegalStateException e) {
                        // 客户端断开：停止推送
                        disconnected.set(true);
                    }
                }

                @Override
                public void onComplete(CodeGenResult genResult) {
                    try {
                        // 已部署的应用复用 deployKey 重新部署；未部署仅更新代码文件，前端用 srcdoc 预览
                        String deployUrl = null;
                        App app = appService.getById(appId);
                        if (app != null && StrUtil.isNotBlank(app.getDeployKey())) {
                            DeployResult deployResult = appService.redeployAppStream(appId, request, msg -> {
                                // 重新部署（npm install/build）期间把阶段与输出逐行推给前端,实现「部署中一直有字符串反馈」
                                if (disconnected.get() || StrUtil.isBlank(msg)) {
                                    return;
                                }
                                try {
                                    emitter.send(SseEmitter.event().name("progress").data(msg));
                                } catch (IOException | IllegalStateException e) {
                                    disconnected.set(true);
                                }
                            });
                            deployUrl = deployResult.deployUrl();
                        }
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("fileNames", genResult.getFileNames());
                        payload.put("htmlCode", genResult.getHtmlCode());
                        payload.put("cssCode", genResult.getCssCode());
                        payload.put("jsCode", genResult.getJsCode());
                        payload.put("deployUrl", deployUrl);
                        payload.put("updateTime", LocalDateTime.now());
                        if (!disconnected.get()) {
                            try {
                                emitter.send(SseEmitter.event().name("appUpdated").data(payload));
                            } catch (IOException | IllegalStateException e) {
                                disconnected.set(true);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("对话自动更新应用失败（不影响文字回复），appId={}", appId, e);
                    } finally {
                        closeStream.run();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("对话自动更新应用失败（不影响文字回复），appId={}", appId, error);
                    closeStream.run();
                }
            }, appId);
        } catch (Exception e) {
            // buildChatContext / generateStream 同步段出错：记录并关闭流，前端据此结束等待
            log.warn("对话自动更新应用失败（不影响文字回复），appId={}", appId, e);
            closeStream.run();
        }
    }

    /** SSE 事件里 JSON 字符串的最小转义（保存目录 / 文件路径均不含换行与制表符） */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 查询当前用户的「我的对话」会话列表：每个应用的最新消息摘要，
     * 按最近活跃（应用 updateTime）倒序，上限 50 个。
     * GET /chat/my/conversations
     *
     * @param request HttpServletRequest
     * @return 会话摘要列表
     */
    @GetMapping("/my/conversations")
    @AuthCheck
    public BaseResponse<List<ChatConversationVO>> listMyConversations(HttpServletRequest request) {
        List<ChatConversationVO> conversations = chatHistoryService.getMyConversations(request);
        return ResultUtils.success(conversations);
    }

    /**
     * 分页加载某个应用的对话历史（用户）。按创建时间倒序（最新在前），
     * 每页默认 10 条（最多 20 条）：首屏取第 1 页即最新 10 条，
     * 向上滚动翻页加载更早的历史。仅应用创建者与管理员可见。
     * GET /chat/list/page?appId=1&pageNum=1&pageSize=10
     *
     * @param queryRequest 分页 + 过滤条件
     * @param request      HttpServletRequest
     * @return 对话历史分页数据
     */
    @GetMapping("/list/page")
    @AuthCheck
    public BaseResponse<Page<ChatHistoryVO>> listChatHistoryByPage(ChatHistoryQueryRequest queryRequest, HttpServletRequest request) {
        if (queryRequest == null) {
            queryRequest = new ChatHistoryQueryRequest();
        }
        Page<ChatHistoryVO> historyPage = chatHistoryService.getChatHistoryPage(queryRequest, request);
        return ResultUtils.success(historyPage);
    }

    /**
     * 游标查询某个应用的对话历史（用户，keyset 分页）。
     * 加载「比 cursorId 更早」的一页（createTime DESC + id DESC），每页默认 10 条、最多 20 条。
     * 首次进入不传 cursorId 取最新一页；前端倒序后升序展示，「加载更多」时传当前最旧消息的 id。
     * GET /chat/cursor/list?appId=1&cursorId=xxx&size=10
     *
     * @param cursorRequest 游标请求
     * @param request       HttpServletRequest
     * @return 本页消息 + 是否还有更早
     */
    @GetMapping("/cursor/list")
    @AuthCheck
    public BaseResponse<ChatCursorVO> listChatHistoryByCursor(ChatHistoryCursorRequest cursorRequest, HttpServletRequest request) {
        ChatCursorVO cursorVO = chatHistoryService.getChatHistoryByCursor(cursorRequest, request);
        return ResultUtils.success(cursorVO);
    }

    // ==================== 管理端：对话历史监管 ====================

    /**
     * 分页查询全部应用的对话历史（管理员）。默认按创建时间倒序，便于内容监管。
     * GET /chat/admin/list/page?pageNum=1&pageSize=20&appId=1&userId=1&messageType=user
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 对话历史分页数据
     */
    @GetMapping("/admin/list/page")
    @AuthCheck(role = "admin")
    public BaseResponse<Page<ChatHistoryVO>> adminListChatHistoryByPage(ChatHistoryAdminQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new ChatHistoryAdminQueryRequest();
        }
        Page<ChatHistoryVO> historyPage = chatHistoryService.adminGetChatHistoryPage(queryRequest);
        return ResultUtils.success(historyPage);
    }

}
