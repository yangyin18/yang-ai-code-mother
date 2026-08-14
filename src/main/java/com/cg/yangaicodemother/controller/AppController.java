package com.cg.yangaicodemother.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import com.cg.yangaicodemother.annotation.AuthCheck;
import com.cg.yangaicodemother.common.BaseResponse;
import com.cg.yangaicodemother.common.DeleteRequest;
import com.cg.yangaicodemother.common.ResultUtils;
import com.cg.yangaicodemother.core.CodeGenFacade;
import com.cg.yangaicodemother.core.CodeGenResult;
import com.cg.yangaicodemother.core.CodeGenStreamCallback;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.exception.ThrowUtils;
import com.cg.yangaicodemother.model.dto.AppAdminCreateRequest;
import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppEditStyleRequest;
import com.cg.yangaicodemother.model.dto.AppEditTextRequest;
import com.cg.yangaicodemother.model.dto.AppGenerateRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.vo.AppCodeVO;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.service.AppService;
import com.cg.yangaicodemother.service.CoverService;
import com.cg.yangaicodemother.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用接口。
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    private final CodeGenFacade codeGenFacade;

    private final UserService userService;

    private final CoverService coverService;

    /**
     * 流式心跳调度器：SSE 首 token 可能较慢(模型侧波动)，每 5s 推一个 heartbeat，
     * 让前端能确认连接存活、展示真实等待时间，避免"看起来卡死/假流式"。
     * 守护线程 + 共享实例，不影响应用生命周期。
     */
    private static final ScheduledExecutorService SSE_HEARTBEAT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * 部署执行器：部署（尤其 Vue 首次 npm install）可能耗时数分钟，
     * 放到独立线程跑，让 SSE 端点立刻返回、进度事件随部署推进逐个推给前端。
     */
    private static final ExecutorService DEPLOY_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "app-deploy");
                t.setDaemon(true);
                return t;
            });

    // ==================== 用户端：我的应用 ====================

    /**
     * 创建应用（用户）。initPrompt 必填。
     * POST /app/add，body 示例：{"initPrompt": "生成一个待办事项网页", "appName": "待办"}
     *
     * @param createRequest 创建请求
     * @param request       HttpServletRequest
     * @return 新应用 id
     */
    @PostMapping("/add")
    @AuthCheck
    public BaseResponse<Long> addApp(@RequestBody AppCreateRequest createRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(createRequest == null, ErrorCode.PARAMS_ERROR);
        long appId = appService.createApp(createRequest, request);
        return ResultUtils.success(appId);
    }

    /**
     * 修改自己的应用（用户）。目前仅支持修改应用名称。
     * POST /app/update，body 示例：{"id": 1, "appName": "新名称"}
     *
     * @param updateRequest 更新请求
     * @param request       HttpServletRequest
     * @return 是否成功
     */
    @PostMapping("/update")
    @AuthCheck
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest updateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(updateRequest == null || updateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        boolean result = appService.updateApp(updateRequest, request);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
        return ResultUtils.success(true);
    }

    /**
     * 删除自己的应用（用户）。逻辑删除。
     * POST /app/delete，body 示例：{"id": 1}
     *
     * @param deleteRequest 删除请求
     * @param request       HttpServletRequest
     * @return 是否成功
     */
    @PostMapping("/delete")
    @AuthCheck
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        boolean result = appService.deleteApp(deleteRequest.getId(), request);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 查看应用详情（用户）。可查看任意未删除应用（含精选应用）。
     * GET /app/get?id=xxx
     *
     * @param id 应用 id
     * @return 应用信息
     */
    @GetMapping("/get")
    @AuthCheck
    public BaseResponse<AppVO> getAppById(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        AppVO appVO = appService.getAppById(id);
        ThrowUtils.throwIf(appVO == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return ResultUtils.success(appVO);
    }

    /**
     * 查看应用已生成的代码文件（供「查看代码」弹窗）。本人 / 管理员 / 已部署应用可查看。
     * GET /app/code?id=xxx
     *
     * @param id      应用 id
     * @param request HttpServletRequest
     * @return 代码内容（含 html/css/js 与文件名列表）
     */
    @GetMapping("/code")
    @AuthCheck
    public BaseResponse<AppCodeVO> getAppCode(Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        AppCodeVO appCodeVO = appService.getAppCode(id, request);
        return ResultUtils.success(appCodeVO);
    }

    /**
     * 直接修改应用代码里的文字（可视化编辑「选中元素 → 改文字 → 保存」，不调 AI）。
     * POST /app/code/edit-text
     * 把代码文件中出现的 oldText 全局替换为 newText 并写回，返回更新后的代码。
     * 权限：仅应用本人 / 管理员可改。
     *
     * @param editTextRequest 原文字 + 新文字
     * @param request         HttpServletRequest
     * @return 更新后的代码（前端刷新预览与文件列表）
     */
    @PostMapping("/code/edit-text")
    @AuthCheck
    public BaseResponse<AppCodeVO> editAppCodeText(@RequestBody AppEditTextRequest editTextRequest,
                                                   HttpServletRequest request) {
        ThrowUtils.throwIf(editTextRequest == null, ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        AppCodeVO appCodeVO = appService.editAppCodeText(
                editTextRequest.getAppId(), editTextRequest.getOldText(), editTextRequest.getNewText(), request);
        return ResultUtils.success(appCodeVO);
    }

    /**
     * 直接修改应用代码里目标元素的样式（可视化编辑「选中元素 → 改颜色/内边距/外边距 → 保存」，不调 AI）。
     * POST /app/code/edit-style
     * 在 index.html 里定位目标元素开标签，把 style 属性合并进其 style 属性并写回，返回更新后的代码。
     * 权限：仅应用本人 / 管理员可改。
     *
     * @param editStyleRequest 应用 id + 元素定位信息 + 要改的样式属性
     * @param request          HttpServletRequest
     * @return 更新后的代码（前端刷新预览与文件列表）
     */
    @PostMapping("/code/edit-style")
    @AuthCheck
    public BaseResponse<AppCodeVO> editAppCodeStyle(@RequestBody AppEditStyleRequest editStyleRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(editStyleRequest == null, ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        AppCodeVO appCodeVO = appService.editAppCodeStyle(editStyleRequest, request);
        return ResultUtils.success(appCodeVO);
    }

    /**
     * 分页查询自己的应用列表（用户）。每页最多 20 个，支持按名称模糊查询。
     * GET /app/my/list/page?pageNum=1&pageSize=20&name=待办
     *
     * @param queryRequest 分页 + 过滤条件
     * @param request      HttpServletRequest
     * @return 自己的应用分页数据
     */
    @GetMapping("/my/list/page")
    @AuthCheck
    public BaseResponse<Page<AppVO>> listMyAppByPage(AppQueryRequest queryRequest, HttpServletRequest request) {
        if (queryRequest == null) {
            queryRequest = new AppQueryRequest();
        }
        Page<AppVO> appPage = appService.getMyAppPage(queryRequest, request);
        return ResultUtils.success(appPage);
    }

    /**
     * 分页查询精选的应用列表（公开，无需登录，应用广场对游客可见）。精选 = 管理员手动置顶（priority &gt; 0）的应用，按优先级降序。
     * 用户部署应用不再自动进入广场，需管理员在「应用管理」设置优先级。
     * 每页最多 20 个，支持按名称模糊查询。
     * GET /app/featured/list/page?pageNum=1&pageSize=20&name=待办
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 精选应用分页数据
     */
    @GetMapping("/featured/list/page")
    public BaseResponse<Page<AppVO>> listFeaturedAppByPage(AppQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new AppQueryRequest();
        }
        Page<AppVO> appPage = appService.getFeaturedAppPage(queryRequest);
        return ResultUtils.success(appPage);
    }

    // ==================== 代码生成 ====================

    /**
     * 为应用生成代码（同步）。使用应用的 initPrompt 作为基础指令、叠加本次需求调用 AI，
     * 生成类型取应用的 codeGenType，代码保存到 {bizType}_{appId} 目录。
     * POST /app/generate，body 示例：{"appId": 1, "requirement": "做一个登录页"}
     *
     * @param generateRequest 生成请求
     * @return 生成结果（含代码内容与保存目录）
     */
    @PostMapping("/generate")
    @AuthCheck
    public BaseResponse<CodeGenResult> generate(@RequestBody AppGenerateRequest generateRequest,
                                                HttpServletRequest request) {
        ThrowUtils.throwIf(generateRequest == null, ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        ThrowUtils.throwIf(generateRequest.getAppId() == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(generateRequest.getRequirement()),
                ErrorCode.PARAMS_ERROR, "需求描述不能为空");
        CodeGenResult result = codeGenFacade.generate(generateRequest.getRequirement(), generateRequest.getAppId());
        // 代码已生成落盘：异步刷新对话页截图封面，失败不影响生成结果
        coverService.refreshCoverAsync(generateRequest.getAppId(), request);
        return ResultUtils.success(result);
    }

    /**
     * 为应用流式生成代码（SSE）。边生成边推送 message 事件（增量文本），
     * 全部完成并落盘后推送 complete 事件（CodeGenResult），异常推送 error 事件。
     * POST /app/generate/stream，body 示例：{"appId": 1, "requirement": "做一个登录页"}
     *
     * @param generateRequest 生成请求
     * @return SSE 流
     */
    @PostMapping("/generate/stream")
    @AuthCheck
    public SseEmitter generateStream(@RequestBody AppGenerateRequest generateRequest,
                                     HttpServletRequest request) {
        ThrowUtils.throwIf(generateRequest == null, ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        ThrowUtils.throwIf(generateRequest.getAppId() == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(generateRequest.getRequirement()),
                ErrorCode.PARAMS_ERROR, "需求描述不能为空");

        SseEmitter emitter = new SseEmitter(600_000L);

        // 客户端是否已断开（关页 / 返回 / abort fetch）：断开后不再推送 SSE，但生成继续在后台跑完并落盘
        AtomicBoolean clientGone = new AtomicBoolean(false);
        // 保活心跳是否已停止：首 token 到达或客户端断开后置 true
        AtomicBoolean done = new AtomicBoolean(false);

        // 首 token 前的保活心跳：每 5s 推一个 heartbeat 事件，让前端看到连接真实存活。
        // 首 token 一旦到达(message 事件)即取消，避免与代码流抢调度。
        final long[] heartbeatStart = {System.currentTimeMillis()};
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();
        // 停心跳（幂等）：客户端断开 / 首 token 到达后调用。只停心跳，绝不中断在途生成——
        // 用户中途退出后生成仍要继续跑完并保存，回来即可看到结果。
        Runnable stopHeartbeat = () -> {
            done.set(true);
            ScheduledFuture<?> h = heartbeatRef.get();
            if (h != null) {
                h.cancel(false);
            }
        };
        ScheduledFuture<?> heartbeat = SSE_HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            if (done.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data("{\"elapsedMs\":" + (System.currentTimeMillis() - heartbeatStart[0]) + "}"));
            } catch (IOException e) {
                // 客户端已断开：停心跳即可。生成继续在后台跑，绝不 completeWithError（会再触发 onError 递归）
                clientGone.set(true);
                stopHeartbeat.run();
            }
        }, 5, 5, TimeUnit.SECONDS);
        heartbeatRef.set(heartbeat);

        // 客户端中途退出（关页 / 返回 / abort fetch）或异步请求超时：连接已死，停心跳、不再推送。
        // 注意：绝不在这里中断生成——背景任务照常跑完并落盘，这是「退出后继续生成」的关键。
        emitter.onTimeout(stopHeartbeat);
        emitter.onError(e -> {
            // 连接级错误（客户端断开 / flush 失败）：只标记并停心跳。
            // 绝不能调用 emitter.completeWithError() —— 会再次进入本回调，递归推送。
            clientGone.set(true);
            stopHeartbeat.run();
        });
        emitter.onCompletion(() -> {
            clientGone.set(true);
            stopHeartbeat.run();
        });

        // 立即告知前端连接已建立（此时 LLM 首 token 可能还要等几秒到几十秒）
        try {
            emitter.send(SseEmitter.event().name("started").data("{}"));
        } catch (IOException e) {
            clientGone.set(true);
            stopHeartbeat.run();
        }

        CodeGenStreamCallback callback = new CodeGenStreamCallback() {
            @Override
            public void onPartial(String partialText) {
                // 过滤空白增量 token：部分模型流式输出会先吐空串/纯空格片段，
                // 前端逐条 append 会拼出多余空格。只跳过整段空白的片段，不做 trim，
                // 以免破坏单词间空格与代码缩进。
                if (StrUtil.isBlank(partialText)) {
                    return;
                }
                // 首 token 已到达，心跳没用了（幂等）
                stopHeartbeat.run();
                // 客户端已断开：跳过推送（避免逐块 IOException 噪音），生成继续在后台跑
                if (clientGone.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().name("message").data(partialText));
                } catch (Exception e) {
                    // 客户端已断开：标记后不再推送，生成继续
                    clientGone.set(true);
                }
            }

            @Override
            public void onComplete(CodeGenResult result) {
                // 生成已完成并由门面落盘保存；此处仅收尾 SSE 连接（客户端是否还在都不影响保存结果）
                try {
                    emitter.send(SseEmitter.event().name("complete").data(result));
                } catch (Exception ignored) {
                    // 客户端已断开，推送失败，忽略
                }
                // 代码已生成落盘：异步刷新对话页截图封面，失败不影响生成结果
                coverService.refreshCoverAsync(generateRequest.getAppId(), request);
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // 客户端断开时容器已 complete 异步请求，忽略
                }
            }

            @Override
            public void onFileWritten(String path) {
                if (clientGone.get()) {
                    return;
                }
                // Vue 项目模式：每个文件写入后推 file 事件，只带路径不带内容，省传输
                try {
                    emitter.send(SseEmitter.event()
                            .name("file")
                            .data("{\"path\":\"" + escapeJson(path) + "\"}"));
                } catch (Exception e) {
                    // 客户端已断开：标记后不再推送，文件写入本身已成功、继续生成
                    clientGone.set(true);
                }
            }

            @Override
            public void onError(Throwable error) {
                // 生成侧出错：若客户端仍在则推送错误，否则静默收尾
                try {
                    emitter.completeWithError(error);
                } catch (IllegalStateException ignored) {
                    // 异步请求已结束，忽略
                }
            }
        };
        // TokenStream 内部异步回调，这里直接启动即可；同步校验异常（应用不存在等）会直接抛出
        codeGenFacade.generateStream(generateRequest.getRequirement(), callback, generateRequest.getAppId());
        return emitter;
    }

    // ==================== Vue 项目下载 ====================

    /**
     * 下载应用已生成的代码为 ZIP 包（Vue 项目是多文件目录，浏览器无法单文件下载）。
     * 权限与「查看代码」一致：本人 / 管理员 / 已部署应用可下载。
     * POST /app/download，body 示例：{"id": 1}
     *
     * @param downloadRequest 下载请求（复用 DeleteRequest，只需 id）
     * @param request         HttpServletRequest
     * @return ZIP 文件流（application/zip 附件）
     */
    @PostMapping("/download")
    @AuthCheck
    public ResponseEntity<byte[]> downloadApp(@RequestBody DeleteRequest downloadRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(downloadRequest == null || downloadRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        // 归属校验后拿到保存目录（html/multi_file/vue 通用）
        String dirPath = appService.downloadAppCode(downloadRequest.getId(), request);
        File srcDir = new File(dirPath);
        if (!FileUtil.exist(srcDir) || !FileUtil.isDirectory(srcDir) || FileUtil.isEmpty(srcDir)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该应用还没有生成过代码");
        }
        File zipFile = new File(FileUtil.getTmpDir(), "app_" + downloadRequest.getId() + "_" + System.nanoTime() + ".zip");
        try {
            // 把目录内容（不含目录本身）打进 zip，解压即得项目文件
            ZipUtil.zip(zipFile, false, srcDir);
            byte[] data = FileUtil.readBytes(zipFile);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "app_" + downloadRequest.getId() + ".zip");
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } finally {
            FileUtil.del(zipFile);
        }
    }

    /**
     * SSE file 事件里 JSON 字符串的最小转义（路径由工具侧校验过，不含换行/制表符）。
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 网站部署 ====================

    /**
     * 部署自己的应用（用户）。把已生成的代码发布到 nginx，写回 deployKey 与部署时间。
     * 部署后不自动进入广场，需管理员在「应用管理」设置优先级。
     * POST /app/deploy，body 示例：{"id": 1}
     *
     * @param deleteRequest 部署请求（复用 DeleteRequest，只需 id）
     * @param request       HttpServletRequest
     * @return 部署结果（含 deployKey 与访问地址）
     */
    @PostMapping("/deploy")
    @AuthCheck
    public BaseResponse<DeployResult> deployApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        DeployResult result = appService.deployApp(deleteRequest.getId(), request);
        return ResultUtils.success(result);
    }

    /**
     * 部署自己的应用（用户），实时流式反馈进度。
     * 与 {@link #deployApp} 效果一致，但通过 SSE 把每个部署阶段和 npm 输出逐行推给前端：
     * 事件有 {@code started} / {@code heartbeat} / {@code progress}（阶段或 npm 输出行）/
     * {@code complete}（DeployResult JSON）/ {@code error}（失败原因）。
     * POST /app/deploy/stream，body 示例：{"id": 1}
     */
    @PostMapping("/deploy/stream")
    @AuthCheck
    public SseEmitter deployAppStream(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        Long appId = deleteRequest.getId();
        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        // 连接结束（超时/客户端断开）统一标记，后续回调直接跳过发送
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        emitter.onCompletion(() -> closed.set(true));
        // 部署可能数分钟无输出（首次 npm install 下载依赖），每 5s 保活心跳避免中间层断开
        ScheduledFuture<?> heartbeat = SSE_HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
            }
        }, 5, 5, TimeUnit.SECONDS);
        try {
            emitter.send(SseEmitter.event().name("started").data("{}"));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            heartbeat.cancel(false);
            return emitter;
        }
        DEPLOY_EXECUTOR.execute(() -> {
            try {
                DeployResult result = appService.deployAppStream(appId, request, msg -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(msg));
                    } catch (IOException | IllegalStateException e) {
                        closed.set(true);
                    }
                });
                heartbeat.cancel(false);
                if (!closed.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("complete").data(result));
                        emitter.complete();
                    } catch (IOException | IllegalStateException e) {
                        closed.set(true);
                    }
                }
            } catch (Exception e) {
                heartbeat.cancel(false);
                if (!closed.get()) {
                    try {
                        // 错误详情可能是多行 npm 输出，SSE data 一行的换行会破坏帧结构，压平成单行
                        String msg = e.getMessage() != null && StrUtil.isNotBlank(e.getMessage())
                                ? e.getMessage().replaceAll("\\r?\\n", " ")
                                : "部署失败";
                        emitter.send(SseEmitter.event().name("error").data(msg));
                        emitter.complete();
                    } catch (IOException | IllegalStateException ignored) {
                        // 连接已断开，无法再告知，忽略
                    }
                }
            }
        });
        return emitter;
    }

    // ==================== 管理端：应用管理 ====================

    /**
     * 新建应用卡片（管理员）。归属当前登录管理员，priority&gt;0 即进入应用广场。
     * POST /app/admin/create，body 示例：{"appName": "待办", "initPrompt": "生成一个待办事项网页", "codeGenType": "html", "priority": 5}
     *
     * @param createRequest 创建请求（含优先级）
     * @param request       HttpServletRequest
     * @return 新应用 id
     */
    @PostMapping("/admin/create")
    @AuthCheck(role = "admin")
    public BaseResponse<Long> adminCreateApp(@RequestBody AppAdminCreateRequest createRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(createRequest == null, ErrorCode.PARAMS_ERROR);
        long appId = appService.adminCreateApp(createRequest, request);
        return ResultUtils.success(appId);
    }

    /**
     * 根据 id 删除任意应用（管理员）。逻辑删除。
     * POST /app/admin/delete，body 示例：{"id": 1}
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/admin/delete")
    @AuthCheck(role = "admin")
    public BaseResponse<Boolean> adminDeleteApp(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        boolean result = appService.adminDeleteApp(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 更新任意应用（管理员）。支持更新应用名称、需求描述(initPrompt)、应用封面、优先级。
     * POST /app/admin/update，body 示例：{"id": 1, "appName": "新名", "initPrompt": "新需求", "cover": "url", "priority": 5}
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    @PostMapping("/admin/update")
    @AuthCheck(role = "admin")
    public BaseResponse<Boolean> adminUpdateApp(@RequestBody AppAdminUpdateRequest updateRequest) {
        ThrowUtils.throwIf(updateRequest == null || updateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        boolean result = appService.adminUpdateApp(updateRequest);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
        return ResultUtils.success(true);
    }

    /**
     * 分页查询应用列表（管理员）。支持按除时间外的任意字段过滤，每页数量不限。
     * GET /app/admin/list/page?pageNum=1&pageSize=20&appName=xx&userId=1&priority=5
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 应用分页数据
     */
    @GetMapping("/admin/list/page")
    @AuthCheck(role = "admin")
    public BaseResponse<Page<AppVO>> adminListAppByPage(AppAdminQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new AppAdminQueryRequest();
        }
        Page<AppVO> appPage = appService.adminGetAppPage(queryRequest);
        return ResultUtils.success(appPage);
    }

    /**
     * 根据 id 查看应用详情（管理员）。
     * GET /app/admin/get?id=xxx
     *
     * @param id 应用 id
     * @return 应用信息
     */
    @GetMapping("/admin/get")
    @AuthCheck(role = "admin")
    public BaseResponse<AppVO> adminGetAppById(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        AppVO appVO = appService.adminGetAppById(id);
        ThrowUtils.throwIf(appVO == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return ResultUtils.success(appVO);
    }

    // ==================== 应用封面 ====================

    /**
     * 获取应用封面（对话页截图 PNG）。
     * 应用广场（priority>0）或已部署（公开上线）的应用封面公开可见,游客也可查看;
     * 其余应用封面仅属主 / 管理员可见。
     * 前端 &lt;img src="/api/app/cover/{appId}"&gt; 同源加载自动携带会话 cookie，
     * dev（Vite 代理）与 prod（nginx 代理）都通。
     * GET /app/cover/{appId}
     *
     * @param appId   应用 id
     * @param request HttpServletRequest
     * @return PNG 图片流
     */
    @GetMapping("/cover/{appId}")
    public ResponseEntity<byte[]> getAppCover(@PathVariable Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null, ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 应用广场(priority>0)或已部署(公开上线)的应用封面公开可见,游客也能看;
        // 其余应用封面仅属主 / 管理员可见,避免未上线应用被任意探测
        boolean inSquare = app.getPriority() != null && app.getPriority() > 0;
        boolean isPublic = StrUtil.isNotBlank(app.getDeployKey());
        if (!inSquare && !isPublic) {
            LoginUserVO loginUser = userService.getLoginUser(request);
            boolean isOwner = app.getUserId() != null && app.getUserId().equals(loginUser.getId());
            boolean isAdmin = "admin".equals(loginUser.getUserRole());
            if (!isOwner && !isAdmin) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限查看该应用封面");
            }
        }
        byte[] coverBytes = coverService.getCoverBytes(appId);
        if (coverBytes == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该应用还没有封面");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        // 封面是开发者本人查看，允许浏览器缓存 10 分钟，减少重复截图后的重复请求
        headers.setCacheControl("max-age=600");
        return new ResponseEntity<>(coverBytes, headers, HttpStatus.OK);
    }

}
