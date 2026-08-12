package com.cg.yangaicodemother.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 网站部署服务：把已生成的应用代码发布到 nginx 站点根目录，由 nginx 对外提供访问。
 *
 * <p>源目录是 {@code CodeSaver} 的输出（{@code {source-root}/{codeGenType}_{appId}/}），
 * 部署时把文件拷贝到 {@code {web-root}/{deployKey}/}，nginx 默认 conf（{@code root html}）即可
 * 以 {@code {base-url}/{deployKey}/} 直接访问。本类只负责文件发布与地址拼装，不碰数据库，
 * 便于单元测试；deployKey / deployedTime 的 DB 写回由 {@code AppServiceImpl#deployApp} 完成。
 */
@Slf4j
@Service
public class DeployService {

    /** 生成代码输出根目录（须与 CodeSaver.FILE_SAVE_ROOT_DIR 保持一致） */
    private final String sourceRoot;

    /** nginx 站点根目录，部署文件落到 {webRoot}/{deployKey}/ */
    private final String webRoot;

    /** 部署站点公网访问前缀，访问地址 = {baseUrl}/{deployKey}/ */
    private final String baseUrl;

    /** 生成新 key 时与已存在目录去重的最大重试次数 */
    private static final int MAX_KEY_RETRY = 20;

    /** npm 安装依赖超时（秒）：首次部署需全量下载依赖，放宽到 5 分钟 */
    private static final int NPM_INSTALL_TIMEOUT_SEC = 300;

    /** npm 构建超时（秒）：vite 构建小项目通常几秒内完成 */
    private static final int NPM_BUILD_TIMEOUT_SEC = 180;

    /** Vue 构建互斥锁：同一应用并发部署会重复写 node_modules / dist，逐个串行更稳妥 */
    private final ConcurrentHashMap<Long, Object> vueBuildLocks = new ConcurrentHashMap<>();

    public DeployService(
            @Value("${code.deploy.source-root:${user.dir}/tmp/code_output}") String sourceRoot,
            @Value("${code.deploy.web-root}") String webRoot,
            @Value("${code.deploy.base-url}") String baseUrl) {
        this.sourceRoot = sourceRoot;
        this.webRoot = webRoot;
        this.baseUrl = baseUrl;
    }

    /**
     * 发布应用代码到 nginx 站点根目录。
     *
     * <p>快速开发（html / multi_file）：源码即产物，直接把源目录内容拷到站点；
     * 深度开发（vue）：源码是工程（package.json / src/），须先 {@code npm install && npm run build}
     * 产出 dist 后再把 dist 内容拷到站点（vite 配置了 base:'./'，相对路径可在子目录下访问）。
     *
     * @param app 应用实体（需有 codeGenType、id；deployKey 非空则复用，否则新生成）
     * @return 部署结果（含 deployKey 与访问地址）
     */
    public DeployResult deploy(App app) {
        return deploy(app, null);
    }

    /**
     * 发布应用代码到 nginx 站点根目录。
     *
     * <p>快速开发（html / multi_file）：源码即产物，直接把源目录内容拷到站点；
     * 深度开发（vue）：源码是工程（package.json / src/），须先 {@code npm install && npm run build}
     * 产出 dist 后再把 dist 内容拷到站点（vite 配置了 base:'./'，相对路径可在子目录下访问）。
     *
     * @param app      应用实体（需有 codeGenType、id；deployKey 非空则复用，否则新生成）
     * @param progress 部署进度回调（阶段说明与 npm 输出逐行回调），可为 null（纯静默部署）
     * @return 部署结果（含 deployKey 与访问地址）
     */
    public DeployResult deploy(App app, Consumer<String> progress) {
        File sourceDir = resolveSourceDir(app);
        String deployKey = resolveDeployKey(app);
        File targetDir = FileUtil.file(webRoot, deployKey);

        FileUtil.mkdir(webRoot);
        if (FileUtil.exist(targetDir)) {
            FileUtil.del(targetDir);
        }
        if (CodeGenTypeEnum.VUE_PROJECT.getValue().equals(app.getCodeGenType())) {
            // Vue 项目：构建 + 发布 dist；同一应用串行，避免并发写坏 node_modules/dist
            synchronized (vueBuildLocks.computeIfAbsent(app.getId(), k -> new Object())) {
                File distDir = buildVueProject(sourceDir, progress);
                if (progress != null) {
                    progress.accept("发布到 nginx …");
                }
                FileUtil.copyContent(distDir, targetDir, true);
            }
        } else {
            if (progress != null) {
                progress.accept("发布到 nginx …");
            }
            // 先删后拷：覆盖发布，清除上一次生成留下的残留文件。
            // 注意用 copyContent（把源目录内容拷进目标目录），而非 copy（会把源目录本身拷成子目录）。
            FileUtil.copyContent(sourceDir, targetDir, true);
        }
        if (progress != null) {
            progress.accept("部署完成 ✓");
        }
        log.info("应用 {} 已部署，deployKey={}，目录={}", app.getId(), deployKey, targetDir.getAbsolutePath());

        // 发布后探测：base-url 指向本机（localhost/127.0.0.1）时，验证站点真的可访问。
        // 之前 nginx 未运行时部署接口仍返回 URL，用户点开却是连接失败——这里主动探测，
        // 探测失败抛清晰异常提示先启动 nginx，而不是默默返回一个打不开的地址。
        String deployUrl = baseUrl + "/" + deployKey + "/";
        if (isLocalDeployHost(baseUrl) && !probeSiteReachable(deployUrl)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "站点已发布但暂时无法访问（" + deployUrl + "）：请确认 nginx 已启动。"
                            + "本机 nginx 启动方式：在 nginx 安装目录执行 start nginx.exe；"
                            + "或检查 code.deploy.web-root / base-url 是否与 nginx.conf 一致。");
        }

        return new DeployResult(app.getId(), deployKey, deployUrl, LocalDateTime.now());
    }

    /** base-url 是否指向本机——只有本机部署才值得在发布后探测可访问性（远端 nginx 无法从后端探测） */
    private static boolean isLocalDeployHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null
                    && (host.equalsIgnoreCase("localhost")
                    || "127.0.0.1".equals(host) || "::1".equals(host));
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测站点首页是否可访问：短超时 + 少量重试，nginx 未启动 / 端口未监听时返回 false */
    private static boolean probeSiteReachable(String url) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    return true;
                }
            } catch (IOException e) {
                // 连接失败（nginx 未启动 / 端口未监听）：短暂重试后再判定不可达
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /**
     * 构建 Vue 项目：先修一次缺失的模块引用（模型生成的工程偶尔引用不存在的组件），
     * 再装依赖（有 package-lock.json 说明上次装过则跳过）并执行 {@code npm run build}。
     *
     * @param sourceDir 生成的 Vue 工程根目录
     * @return dist 产物目录
     */
    private File buildVueProject(File sourceDir, Consumer<String> progress) {
        long start = System.currentTimeMillis();
        try {
            if (progress != null) {
                progress.accept("修复缺失的模块引用 …");
            }
            // 幂等黄金兜底：确保 package.json / vite.config.js / index.html 等关键文件在，
            // 规避历史生成（scaffold 开关关闭 / 早期版本）产物缺文件导致构建失败。
            VueProjectScaffolder.scaffold(sourceDir);
            VueImportRepairer.repair(sourceDir);
            // 部署专用 vite 配置：合并原配置并强制 base:'./' + 带编译器的完整版 Vue。
            // 若不注入，Vite 默认 runtime-only 构建无法编译模型常用的内联 template 字符串组件，
            // 部署后页面空白（已实测确认根因）。
            writeDeployViteConfig(sourceDir);
            // 只在没有上次安装痕迹时全量安装，避免每次部署都重新下载依赖
            if (!FileUtil.exist(FileUtil.file(sourceDir, "package-lock.json"))) {
                if (progress != null) {
                    progress.accept("安装 npm 依赖中（首次部署可能需几分钟）…");
                }
                runNpm(sourceDir, NPM_INSTALL_TIMEOUT_SEC, progress, "install");
            } else if (progress != null) {
                progress.accept("检测到已安装依赖，跳过 npm install");
            }
            if (progress != null) {
                progress.accept("npm run build 构建中 …");
            }
            if (usesViteBuildScript(sourceDir)) {
                // 走 vite build 的构建脚本才注入 --config；非 vite 构建脚本按原样构建
                runNpm(sourceDir, NPM_BUILD_TIMEOUT_SEC, progress,
                        "run", "build", "--", "--config", "vite.deploy.config.js");
            } else {
                runNpm(sourceDir, NPM_BUILD_TIMEOUT_SEC, progress, "run", "build");
            }
        } finally {
            // 部署专用配置只在构建期间需要：构建结束即删除，避免混入文件清单/下载 ZIP
            File deployCfg = FileUtil.file(sourceDir, "vite.deploy.config.js");
            if (FileUtil.exist(deployCfg)) {
                FileUtil.del(deployCfg);
            }
            log.info("Vue 项目构建耗时 {}ms，目录：{}", System.currentTimeMillis() - start, sourceDir.getAbsolutePath());
        }
        File dist = FileUtil.file(sourceDir, "dist");
        if (!FileUtil.isDirectory(dist)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 项目构建失败：未生成 dist 产物目录");
        }
        return dist;
    }

    /** 在指定目录执行 npm 子命令，超时 / 非零退出码均抛业务异常并附上输出尾部 */
    private void runNpm(File workDir, int timeoutSeconds, Consumer<String> progress, String... args) {
        Process process;
        try {
            // Windows 下 npm 是 npm.cmd 脚本，须经 cmd 启动；chcp 65001 让子进程输出 UTF-8，
            // 避免中文 Windows 下 node/npm/vite 的 GBK 输出被按 UTF-8 读取导致进度乱码。
            List<String> cmd = new java.util.ArrayList<>();
            if (isWindows()) {
                cmd.add("cmd.exe");
                cmd.add("/c");
                cmd.add("chcp 65001>nul & npm");
            } else {
                cmd.add("npm");
            }
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "无法启动 npm：" + e.getMessage());
        }
        // 单独线程逐行读取输出：既避免管道写满时子进程阻塞，又把每行实时转成进度回调
        // （npm install 的进度条用 \r 刷新、readLine 按 \n 切行，落盘信息等换行后的行可被捕捉到）
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = process.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (progress != null && StrUtil.isNotBlank(line)) {
                        String trimmed = line.trim();
                        if (trimmed.length() > 150) {
                            trimmed = trimmed.substring(0, 150) + "…";
                        }
                        progress.accept(trimmed);
                    }
                }
            } catch (IOException e) {
                // 读取中断：保留已读到的输出用于报错详情
            }
            return sb.toString();
        });
        String cmdLabel = "npm " + String.join(" ", args);
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.OPERATION_ERROR, cmdLabel + " 超时（>" + timeoutSeconds + "s）");
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                List<String> tail = Arrays.stream(output.split("\n"))
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toList());
                int from = Math.max(0, tail.size() - 8);
                String detail = String.join("\n", tail.subList(from, tail.size()));
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        cmdLabel + " 失败（exit=" + process.exitValue() + "）：\n" + detail);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, cmdLabel + " 执行被中断");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, cmdLabel + " 读取输出失败");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static List<String> concat(List<String> head, String... tail) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>(head);
        list.addAll(Arrays.asList(tail));
        return list;
    }

    /** 部署专用 vite 配置：在项目原配置之上合并 base:'./' 与带编译器的完整版 Vue */
    private void writeDeployViteConfig(File sourceDir) {
        File cfg = FileUtil.file(sourceDir, "vite.deploy.config.js");
        String content = """
                // 本文件由后端部署流程自动生成：在项目原 vite 配置之上注入部署构建所需设置（勿手动修改）。
                // 1) base:'./' —— 让构建产物的资源引用为相对路径，部署到 {base-url}/{deployKey}/ 子目录才能访问；
                //    模型自写的 vite.config.js 往往漏掉它，导致子目录部署后资源 404。
                // 2) vue 别名指向带编译器的完整构建（vue/dist/vue.esm-bundler.js）—— Vite 默认按
                //    runtime-only 解析 vue，无法编译模型/脚手架常用的内联 template 字符串组件，
                //    路由组件渲染为空 → 部署后页面空白。注入完整构建后内联模板即可正常渲染。
                import { defineConfig, mergeConfig } from 'vite'
                import orig from './vite.config.js'
                const base = typeof orig === 'function' ? orig({ mode: 'production', command: 'build' }) : orig
                export default defineConfig(mergeConfig(base, {
                  base: './',
                  resolve: {
                    alias: { vue: 'vue/dist/vue.esm-bundler.js' },
                  },
                }))
                """;
        FileUtil.writeUtf8String(content, cfg);
    }

    /** 构建脚本是否走 vite build —— 只有 vite 才支持 --config 注入部署配置 */
    private boolean usesViteBuildScript(File sourceDir) {
        File pkg = FileUtil.file(sourceDir, "package.json");
        if (!FileUtil.exist(pkg)) {
            return false;
        }
        try {
            JsonNode tree = new ObjectMapper().readTree(FileUtil.readUtf8String(pkg));
            JsonNode build = tree.path("scripts").path("build");
            return build.isTextual() && build.asText().contains("vite");
        } catch (Exception e) {
            return false;
        }
    }

    /** 定位生成代码所在目录；未生成代码时给出清晰提示 */
    private File resolveSourceDir(App app) {
        if (app.getId() == null || StrUtil.isBlank(app.getCodeGenType())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未生成代码，请先生成");
        }
        String dir = sourceRoot + "/" + app.getCodeGenType() + "_" + app.getId();
        if (!FileUtil.isDirectory(dir)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未生成代码，请先生成");
        }
        return FileUtil.file(dir);
    }

    /** 已部署则复用 key（访问地址稳定）；否则生成不与已存在站点目录冲突的随机 key */
    private String resolveDeployKey(App app) {
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            return app.getDeployKey();
        }
        for (int i = 0; i < MAX_KEY_RETRY; i++) {
            String key = RandomUtil.randomString(8);
            if (!FileUtil.exist(FileUtil.file(webRoot, key))) {
                return key;
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成部署标识失败，请重试");
    }
}
