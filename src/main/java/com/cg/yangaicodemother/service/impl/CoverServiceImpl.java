package com.cg.yangaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.mapper.AppCoverMapper;
import com.cg.yangaicodemother.mapper.AppMapper;
import com.cg.yangaicodemother.mapper.UserMapper;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.entity.AppCover;
import com.cg.yangaicodemother.model.entity.User;
import com.cg.yangaicodemother.service.CoverService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 应用封面服务实现：调用 screenshot/screenshot.js（puppeteer-core + 已装 Chrome）
 * 截取对话页 /chat/:appId，PNG 字节持久化到 DB（app_cover 表），并写回 app.cover
 * 为相对地址 /api/app/cover/{appId}（不暴露服务端绝对路径，见「禁止绝对路径泄漏」约定）。
 * 磁盘 {code.cover.root}/{appId}.png 仅作截图脚本的临时暂存，校验入库后即删除。
 *
 * <p>触发点在生成 / 部署成功后异步调用，截图与主流程解耦：失败只记日志、超时强制结束，
 * 绝不影响文字回复与代码生成。
 */
@Slf4j
@Service
public class CoverServiceImpl implements CoverService {

    /** 截图子进程最长等待（秒）：headless Chrome 冷启动 + 页面渲染通常在几十秒内 */
    private static final int SCREENSHOT_TIMEOUT_SEC = 120;

    /** 合法封面 PNG 的最小字节数：空白页 / 纯错误页截图远小于此，视为失败 */
    private static final long MIN_PNG_BYTES = 3072;

    /** 封面 PNG 存储根目录（code.cover.root） */
    private final String coverRoot;

    /** 截图用的前端入口（code.cover.frontend-url，dev 为 5173，prod 为 nginx 域名） */
    private final String frontendUrl;

    /** Chrome 可执行文件路径（留空自动探测），透传给截图脚本 */
    private final String chromePath;

    /** 页面渲染完成后的静置时长（ms），等右栏应用预览真正画出来 */
    private final long settleMs;

    private final AppMapper appMapper;

    private final UserMapper userMapper;

    /** 封面 PNG 字节持久化表（app_cover） */
    private final AppCoverMapper appCoverMapper;

    /** 在途截图任务去重：同一 appId 已有任务则跳过，避免频繁部署时重复开 Chrome */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 专用单线程守护线程：截图子进程串行执行，避免并发开多个 Chrome 抢占资源 */
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cover-screenshot");
        t.setDaemon(true);
        return t;
    });

    public CoverServiceImpl(
            @Value("${code.cover.root:${user.dir}/tmp/covers}") String coverRoot,
            @Value("${code.cover.frontend-url}") String frontendUrl,
            @Value("${code.cover.chrome-path:}") String chromePath,
            @Value("${code.cover.settle-ms:3500}") long settleMs,
            AppMapper appMapper,
            UserMapper userMapper,
            AppCoverMapper appCoverMapper) {
        this.coverRoot = coverRoot;
        this.frontendUrl = frontendUrl;
        this.chromePath = chromePath;
        this.settleMs = settleMs;
        this.appMapper = appMapper;
        this.userMapper = userMapper;
        this.appCoverMapper = appCoverMapper;
    }

    @Override
    public void refreshCoverAsync(Long appId, HttpServletRequest request) {
        if (appId == null || request == null) {
            return;
        }
        // 同步读取会话 cookie：截图线程只依赖这个值，不持有 request
        String jsessionId = extractCookie(request, "JSESSIONID");
        if (StrUtil.isBlank(jsessionId)) {
            log.warn("封面截图跳过：请求无 JSESSIONID，appId={}", appId);
            return;
        }
        if (!inFlight.add(appId)) {
            // 已有截图任务在跑，幂等跳过（下次触发仍会刷新）
            return;
        }
        coverExecutor.execute(() -> {
            try {
                takeScreenshot(appId, jsessionId);
            } finally {
                inFlight.remove(appId);
            }
        });
    }

    @Override
    public byte[] getCoverBytes(Long appId) {
        if (appId == null) {
            return null;
        }
        AppCover cover = appCoverMapper.selectOneById(appId);
        return cover == null ? null : cover.getImage();
    }

    @Override
    public void deleteCover(Long appId) {
        if (appId == null) {
            return;
        }
        appCoverMapper.deleteById(appId);
    }

    /** upsert 封面字节到 app_cover：存在则更新 image+updateTime，否则插入（appId 1:1） */
    private void saveCoverBytes(Long appId, byte[] image) {
        LocalDateTime now = LocalDateTime.now();
        AppCover existing = appCoverMapper.selectOneById(appId);
        if (existing != null) {
            existing.setImage(image);
            existing.setUpdateTime(now);
            appCoverMapper.update(existing);
        } else {
            AppCover cover = new AppCover();
            cover.setAppId(appId);
            cover.setImage(image);
            cover.setUpdateTime(now);
            appCoverMapper.insert(cover);
        }
    }

    /** 真正执行截图：启动 node 脚本、校验产物、写回封面地址 */
    private void takeScreenshot(Long appId, String jsessionId) {
        File dir = new File(coverRoot);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("封面目录创建失败：{}", coverRoot);
            return;
        }
        File out = new File(dir, appId + ".png");
        try {
            App app = appMapper.selectOneById(appId);
            if (app == null) {
                return;
            }
            User owner = app.getUserId() != null ? userMapper.selectOneById(app.getUserId()) : null;
            String url = frontendUrl + "/chat/" + appId;
            List<String> cmd = buildCommand(url, "JSESSIONID=" + jsessionId, out.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 用户 JSON 含双引号，经命令行参数传会被 Windows ProcessBuilder 破坏引号导致 JSON 解析失败，
            // 改走环境变量（环境块不经命令行转义），截图脚本从 YANG_AI_COVER_USER 读取。
            pb.environment().put("YANG_AI_COVER_USER", buildUserJson(owner, app));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 单独线程排空子进程输出，避免管道写满阻塞 node 进程
            CompletableFuture<String> drain =
                    CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));

            boolean finished = process.waitFor(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("封面截图超时（>{}s），appId={}", SCREENSHOT_TIMEOUT_SEC, appId);
                return;
            }
            try {
                drain.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 输出读取收尾失败不影响结果判定，忽略
            }
            if (process.exitValue() != 0) {
                String outText = "";
                try {
                    outText = drain.get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 读不到输出也不影响失败判定
                }
                log.warn("封面截图脚本退出码非 0，appId={}，脚本输出：{}", appId,
                        StrUtil.isBlank(outText) ? "(无输出)" : outText.trim());
                return;
            }
            if (!out.exists() || out.length() < MIN_PNG_BYTES) {
                log.warn("封面截图过小或缺失：appId={}, size={} bytes",
                        appId, out.exists() ? out.length() : 0);
                return;
            }
            // 读字节 → 持久化到 DB（app_cover 表）→ 删除磁盘临时文件；
            // DB 写入失败只记日志、不设封面，绝不影响主流程
            byte[] pngBytes = FileUtil.readBytes(out);
            try {
                saveCoverBytes(appId, pngBytes);
            } catch (Exception e) {
                log.warn("封面写入 DB 失败（不设封面），appId={}", appId, e);
                return;
            }
            FileUtil.del(out);
            // 写回相对封面地址（/api 前缀，前端 img 同源加载自动带会话 cookie），不带绝对路径
            app.setCover("/api/app/cover/" + appId);
            appMapper.update(app);
            log.info("封面已更新并持久化到DB：appId={}, size={} bytes", appId, pngBytes.length);
        } catch (Exception e) {
            log.warn("封面截图失败（不影响主流程），appId={}", appId, e);
        }
    }

    /** 组装 node 脚本启动命令；node 是独立可执行文件，Windows 上无需 cmd 包装。
     *  userJson 不放在命令行参数里（含双引号会被 Windows ProcessBuilder 破坏），走环境变量。 */
    private List<String> buildCommand(String url, String cookie, String outPath) {
        String script = System.getProperty("user.dir")
                + File.separator + "screenshot" + File.separator + "screenshot.js";
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(script);
        cmd.add(url);
        cmd.add(cookie);
        cmd.add(outPath);
        cmd.add(String.valueOf(settleMs));
        if (StrUtil.isNotBlank(chromePath)) {
            cmd.add(chromePath);
        }
        return cmd;
    }

    /** 前端 user store 需要的属主用户信息 JSON（与 src/stores/user.ts 的 UserInfo 一致） */
    private String buildUserJson(User owner, App app) {
        String id = app.getUserId() == null ? "" : String.valueOf(app.getUserId());
        String username = owner != null && StrUtil.isNotBlank(owner.getUserAccount())
                ? owner.getUserAccount() : "user";
        String role = owner != null && StrUtil.isNotBlank(owner.getUserRole())
                ? owner.getUserRole() : "user";
        return "{\"id\":\"" + id + "\",\"username\":\"" + username + "\",\"userRole\":\"" + role + "\"}";
    }

    /** 从请求 cookie 中取指定 cookie 的值 */
    private String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 排空子进程标准输出（合流后），供日志 / 后续判断使用 */
    private String readAll(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            // 读取中断：保留已读内容
        }
        return sb.toString();
    }
}
