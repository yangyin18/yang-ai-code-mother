package com.cg.yangaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.common.PageRequest;
import com.cg.yangaicodemother.core.editor.HtmlStyleEditor;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.mapper.AppMapper;
import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppEditStyleRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.cg.yangaicodemother.model.vo.AppCodeVO;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.model.vo.ProjectFileVO;
import com.cg.yangaicodemother.service.AppService;
import com.cg.yangaicodemother.service.ChatHistoryService;
import com.cg.yangaicodemother.service.DeployService;
import com.cg.yangaicodemother.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.LambdaGetter;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author 34488
 * @since 2026-08-08
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    /**
     * 用户侧列表每页最多 20 个（需求限制），超过则截断。
     */
    private static final int USER_PAGE_SIZE_LIMIT = 20;

    /**
     * 允许排序的字段白名单：管理员列表前端传 sortField 时只允许映射到这里的实体字段，
     * 防止把任意字符串拼进 ORDER BY 造成 SQL 注入。
     */
    private static final Map<String, LambdaGetter<App>> SORT_FIELD_MAP = Map.of(
            "id", App::getId,
            "appName", App::getAppName,
            "codeGenType", App::getCodeGenType,
            "deployKey", App::getDeployKey,
            "priority", App::getPriority,
            "userId", App::getUserId,
            "createTime", App::getCreateTime,
            "updateTime", App::getUpdateTime
    );

    @Resource
    private UserService userService;

    @Resource
    private DeployService deployService;

    /** 删除应用时级联删除该应用的对话历史，避免数据冗余 */
    @Resource
    private ChatHistoryService chatHistoryService;

    /** 部署站点公网前缀，用于给 AppVO 拼已部署应用的访问地址 */
    @Value("${code.deploy.base-url:}")
    private String deployBaseUrl;

    /** 生成代码保存根目录（code.deploy.source-root，默认 {user.dir}/tmp/code_output），查看代码时据此定位 {bizType}_{appId} 子目录 */
    @Value("${code.deploy.source-root:}")
    private String codeSourceRoot;

    // ==================== 用户端：我的应用 ====================

    @Override
    public Long createApp(AppCreateRequest createRequest, HttpServletRequest request) {
        // 1. 校验参数：initPrompt 必填
        if (createRequest == null || StrUtil.isBlank(createRequest.getInitPrompt())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "initPrompt 不能为空");
        }
        // 校验代码生成类型取值，防止写入脏数据
        if (StrUtil.isNotBlank(createRequest.getCodeGenType())
                && CodeGenTypeEnum.getEnumByValue(createRequest.getCodeGenType()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的代码生成类型");
        }

        // 2. 组装实体，归属当前登录用户
        LoginUserVO loginUser = userService.getLoginUser(request);
        App app = new App();
        app.setAppName(StrUtil.blankToDefault(createRequest.getAppName(), "未命名应用"));
        app.setCover(createRequest.getCover());
        app.setInitPrompt(createRequest.getInitPrompt());
        app.setCodeGenType(createRequest.getCodeGenType());
        app.setPriority(0);
        app.setUserId(loginUser.getId());
        // 显式初始化时间戳：不依赖 DB 默认值，保证「我的应用」按 updateTime 排序时新应用也有值
        LocalDateTime now = LocalDateTime.now();
        app.setCreateTime(now);
        app.setUpdateTime(now);

        // 3. 保存
        boolean saveResult = this.save(app);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "创建失败，数据库异常");
        }
        return app.getId();
    }

    @Override
    public boolean updateApp(AppUpdateRequest updateRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (updateRequest == null || updateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        if (StrUtil.isBlank(updateRequest.getAppName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        }
        App app = getOwnedApp(updateRequest.getId(), loginUser.getId());
        // 用户侧目前只支持修改应用名称
        app.setAppName(updateRequest.getAppName());
        return this.updateById(app);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteApp(Long id, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        getOwnedApp(id, loginUser.getId());
        // mybatis-flex 逻辑删除：isDelete 标注了 @Column(isLogicDelete=true)，删后查询自动过滤
        boolean result = this.removeById(id);
        // 关联删除该应用的所有对话历史，避免数据冗余（与删除应用同一事务，失败一并回滚）
        chatHistoryService.removeByAppId(id);
        return result;
    }

    @Override
    public AppVO getAppById(Long id) {
        App app = this.mapper.selectOneById(id);
        if (app == null) {
            return null;
        }
        return toVO(app);
    }

    @Override
    public AppCodeVO getAppCode(Long id, HttpServletRequest request) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = this.mapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 权限：本人 / 管理员 / 已部署（public 上线）可查看；其他人不允许看未上线应用的代码
        checkCodeViewPermission(app, userService.getLoginUser(request), "查看");

        // 按 {codeGenType}_{appId} 定位代码目录（默认 html），递归读取全部文件
        String dirPath = resolveCodeDir(app);
        Map<String, String> contents = readProjectFiles(dirPath);
        return buildAppCodeVO(app, contents);
    }

    @Override
    public AppCodeVO editAppCodeText(Long appId, String oldText, String newText, HttpServletRequest request) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        if (StrUtil.isBlank(oldText)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "原文字不能为空");
        }
        if (StrUtil.isBlank(newText)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新文字不能为空");
        }
        // 只允许本人 / 管理员修改；已部署公开用户只能查看、不能改他人代码
        App app = this.mapper.selectOneById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        checkCodeEditPermission(app, userService.getLoginUser(request));

        String dirPath = resolveCodeDir(app);
        Map<String, String> contents = readProjectFiles(dirPath);
        if (contents.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "该应用还没有生成过代码");
        }
        boolean changed = false;
        for (Map.Entry<String, String> e : contents.entrySet()) {
            String content = e.getValue();
            if (content != null && content.contains(oldText)) {
                // 只做文字级全局替换：其余内容原样保留（不调 AI 的小幅度修改）
                String updated = content.replace(oldText, newText);
                FileUtil.writeString(updated, new File(dirPath, e.getKey()), StandardCharsets.UTF_8);
                changed = true;
            }
        }
        if (!changed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "没有在代码中找到要修改的文字，可能该元素包含图标/子元素或已是最新，请刷新页面重试");
        }
        // 小改动也算一次更新：刷新应用更新时间，让「我的应用」按最近活跃置顶
        touchAppUpdateTime(app.getId());
        // 返回替换后的最新代码，前端据此刷新预览与代码文件列表
        return buildAppCodeVO(app, readProjectFiles(dirPath));
    }

    @Override
    public AppCodeVO editAppCodeStyle(AppEditStyleRequest editStyleRequest, HttpServletRequest request) {
        if (editStyleRequest == null || editStyleRequest.getAppId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        if (StrUtil.isBlank(editStyleRequest.getTag())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "目标元素标签不能为空");
        }
        Map<String, String> styleProps = editStyleRequest.getStyle();
        if (styleProps == null || styleProps.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "要修改的样式属性不能为空");
        }
        // 只允许本人 / 管理员修改；已部署公开用户只能查看、不能改他人代码
        App app = this.mapper.selectOneById(editStyleRequest.getAppId());
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        checkCodeEditPermission(app, userService.getLoginUser(request));

        String dirPath = resolveCodeDir(app);
        Map<String, String> contents = readProjectFiles(dirPath);
        String html = contents.get("index.html");
        if (StrUtil.isBlank(html)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                    "该应用还没有可编辑样式的页面代码");
        }
        String updated = HtmlStyleEditor.applyStyle(html, editStyleRequest.getTag(),
                editStyleRequest.getId(), editStyleRequest.getText(),
                editStyleRequest.getClassName(), styleProps);
        if (updated == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "没有在代码中找到该元素，可能页面已变化，请刷新页面后重新选中");
        }
        FileUtil.writeString(updated, new File(dirPath, "index.html"), StandardCharsets.UTF_8);
        // 小改动也算一次更新：刷新应用更新时间，让「我的应用」按最近活跃置顶
        touchAppUpdateTime(app.getId());
        return buildAppCodeVO(app, readProjectFiles(dirPath));
    }

    /** 把磁盘代码内容组装成对外 AppCodeVO（html/css/js + 文件名列表 + 文件树），getAppCode / editAppCodeText 复用 */
    private AppCodeVO buildAppCodeVO(App app, Map<String, String> contents) {
        AppCodeVO vo = new AppCodeVO();
        vo.setId(app.getId());
        vo.setAppName(app.getAppName());
        vo.setDeployUrl(StrUtil.isNotBlank(app.getDeployKey()) && StrUtil.isNotBlank(deployBaseUrl)
                ? deployBaseUrl + "/" + app.getDeployKey() + "/" : null);
        vo.setCodeGenType(app.getCodeGenType());
        vo.setFileNames(new ArrayList<>(contents.keySet()));
        vo.setHtmlCode(contents.get("index.html"));
        vo.setCssCode(contents.get("style.css"));
        vo.setJsCode(contents.get("script.js"));
        vo.setFiles(contents.entrySet().stream()
                .map(e -> {
                    ProjectFileVO file = new ProjectFileVO();
                    file.setPath(e.getKey());
                    file.setContent(e.getValue());
                    return file;
                })
                .collect(Collectors.toList()));
        return vo;
    }

    /** 校验修改代码权限：仅应用本人 / 管理员可改（比查看更严，已部署公开用户不可改） */
    private void checkCodeEditPermission(App app, LoginUserVO loginUser) {
        boolean isOwner = app.getUserId() != null && app.getUserId().equals(loginUser.getId());
        boolean isAdmin = "admin".equals(loginUser.getUserRole());
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限修改该应用代码");
        }
    }

    /** 应用有活动（对话 / 改文字等）时刷新更新时间，让「我的应用」按最近活跃置顶。
     *  MyBatis-Flex 的 update(entity) 默认只更新非空字段，这里只带 id + updateTime，
     *  不会覆盖应用的其它列；逻辑删除（isDelete）自动拼进 WHERE，已删除应用不受影响。 */
    private void touchAppUpdateTime(Long appId) {
        App touch = new App();
        touch.setId(appId);
        touch.setUpdateTime(LocalDateTime.now());
        this.mapper.update(touch);
    }

    @Override
    public String downloadAppCode(Long id, HttpServletRequest request) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = this.mapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        checkCodeViewPermission(app, userService.getLoginUser(request), "下载");
        return resolveCodeDir(app);
    }

    @Override
    public Page<AppVO> getMyAppPage(AppQueryRequest queryRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 只查自己的应用
        queryWrapper.where(App::getUserId).eq(loginUser.getId());
        if (queryRequest != null && StrUtil.isNotBlank(queryRequest.getName())) {
            queryWrapper.where(App::getAppName).like(queryRequest.getName());
        }
        // 最近活跃优先：按更新时间倒序（对话/部署/编辑都会刷新 updateTime），
        // 常聊的应用置顶，而不是一直按创建时间排死序。
        // updateTime 是秒级精度，同秒创建/更新的应用会并列，补 id 倒序做稳定排序（新 id = 后插入）
        queryWrapper.orderBy(App::getUpdateTime, false).orderBy(App::getId, false);
        return toVOPage(pageByRequest(queryRequest, USER_PAGE_SIZE_LIMIT, queryWrapper));
    }

    @Override
    public Page<AppVO> getFeaturedAppPage(AppQueryRequest queryRequest) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 精选 = 管理员手动置顶（priority > 0）的应用，按优先级降序、创建时间降序。
        // 用户部署应用不再自动进入广场，需管理员在「应用管理」设置优先级。
        queryWrapper.where(App::getPriority).gt(0);
        if (queryRequest != null && StrUtil.isNotBlank(queryRequest.getName())) {
            queryWrapper.where(App::getAppName).like(queryRequest.getName());
        }
        queryWrapper.orderBy(App::getPriority, false).orderBy(App::getCreateTime, false);
        return toVOPage(pageByRequest(queryRequest, USER_PAGE_SIZE_LIMIT, queryWrapper));
    }

    @Override
    public DeployResult deployApp(Long appId, HttpServletRequest request) {
        return deployAppStream(appId, request, null);
    }

    @Override
    public DeployResult deployAppStream(Long appId, HttpServletRequest request, Consumer<String> progress) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = getOwnedApp(appId, loginUser.getId());
        // 发布文件到 nginx 站点根目录，并拼出访问地址；进度回调实时转发部署阶段与 npm 输出
        DeployResult result = deployService.deploy(app, progress);
        // 写回部署标识与部署时间。部署后不自动进入广场，需管理员在「应用管理」设置优先级
        app.setDeployKey(result.deployKey());
        app.setDeployedTime(result.deployedTime());
        this.updateById(app);
        return result;
    }

    @Override
    public DeployResult redeployApp(Long appId) {
        return redeployAppStream(appId, null);
    }

    @Override
    public DeployResult redeployAppStream(Long appId, Consumer<String> progress) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = this.mapper.selectOneById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 复用原 deployKey（访问地址稳定），把最新生成的代码覆盖到 nginx 站点
        DeployResult result = deployService.deploy(app, progress);
        app.setDeployKey(result.deployKey());
        app.setDeployedTime(result.deployedTime());
        this.updateById(app);
        return result;
    }

    // ==================== 管理端：应用管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adminDeleteApp(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        boolean result = this.removeById(id);
        // 关联删除该应用的所有对话历史，避免数据冗余（与删除应用同一事务，失败一并回滚）
        chatHistoryService.removeByAppId(id);
        return result;
    }

    @Override
    public boolean adminUpdateApp(AppAdminUpdateRequest updateRequest) {
        if (updateRequest == null || updateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = this.mapper.selectOneById(updateRequest.getId());
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 只更新传入的非空字段
        if (StrUtil.isNotBlank(updateRequest.getAppName())) {
            app.setAppName(updateRequest.getAppName());
        }
        if (updateRequest.getCover() != null) {
            app.setCover(updateRequest.getCover());
        }
        if (updateRequest.getPriority() != null) {
            app.setPriority(updateRequest.getPriority());
        }
        return this.updateById(app);
    }

    @Override
    public Page<AppVO> adminGetAppPage(AppAdminQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new AppAdminQueryRequest();
        }
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 条件过滤：除时间外任意字段，传了才拼，没传不拼
        if (queryRequest.getId() != null) {
            queryWrapper.where(App::getId).eq(queryRequest.getId());
        }
        if (StrUtil.isNotBlank(queryRequest.getAppName())) {
            queryWrapper.where(App::getAppName).like(queryRequest.getAppName());
        }
        if (StrUtil.isNotBlank(queryRequest.getCodeGenType())) {
            queryWrapper.where(App::getCodeGenType).eq(queryRequest.getCodeGenType());
        }
        if (StrUtil.isNotBlank(queryRequest.getDeployKey())) {
            queryWrapper.where(App::getDeployKey).like(queryRequest.getDeployKey());
        }
        if (queryRequest.getPriority() != null) {
            queryWrapper.where(App::getPriority).eq(queryRequest.getPriority());
        }
        if (queryRequest.getUserId() != null) {
            queryWrapper.where(App::getUserId).eq(queryRequest.getUserId());
        }
        // 排序：白名单映射，未知字段回退按创建时间倒序
        // 注意：Map.of 的不可变 Map 在 get(null) 时会抛 NPE，必须先判空
        LambdaGetter<App> sortColumn = StrUtil.isNotBlank(queryRequest.getSortField())
                ? SORT_FIELD_MAP.get(queryRequest.getSortField())
                : null;
        if (sortColumn != null) {
            boolean asc = "ascend".equalsIgnoreCase(queryRequest.getSortOrder());
            queryWrapper.orderBy(sortColumn, asc);
        } else {
            queryWrapper.orderBy(App::getCreateTime, false);
        }
        // 管理员分页：每页数量不限，直接用请求里的 pageSize
        return toVOPage(pageByRequest(queryRequest, Integer.MAX_VALUE, queryWrapper));
    }

    @Override
    public AppVO adminGetAppById(Long id) {
        App app = this.mapper.selectOneById(id);
        if (app == null) {
            return null;
        }
        return toVO(app);
    }

    // ==================== 私有工具 ====================

    /**
     * 查询应用并强制「归属当前用户」，否则抛异常。
     * 供用户端修改 / 删除自己应用时校验所有权。
     */
    private App getOwnedApp(Long id, Long userId) {
        App app = this.mapper.selectOneById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!app.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
        }
        return app;
    }

    /**
     * 校验查看/下载代码权限：本人 / 管理员 / 已部署（public 上线）可查看。
     *
     * @param app      应用实体
     * @param loginUser 当前登录用户
     * @param action   动作描述（"查看" / "下载"），用于错误提示
     */
    private void checkCodeViewPermission(App app, LoginUserVO loginUser, String action) {
        boolean isOwner = app.getUserId() != null && app.getUserId().equals(loginUser.getId());
        boolean isAdmin = "admin".equals(loginUser.getUserRole());
        boolean isPublic = StrUtil.isNotBlank(app.getDeployKey());
        if (!isOwner && !isAdmin && !isPublic) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限" + action + "该应用代码");
        }
    }

    /**
     * 定位应用代码保存目录：{root}/{codeGenType}_{appId}（默认 html）。
     */
    private String resolveCodeDir(App app) {
        String bizType = StrUtil.blankToDefault(app.getCodeGenType(), CodeGenTypeEnum.HTML.getValue());
        return StrUtil.blankToDefault(codeSourceRoot, System.getProperty("user.dir") + "/tmp/code_output")
                + "/" + bizType + "_" + app.getId();
    }

    /**
     * 递归读取代码目录下所有文件，key=项目内相对路径（/ 分隔），value=文件内容。
     * 目录不存在时返回空 Map。内容从磁盘读取，不消耗 AI token。
     *
     * <p>跳过 {@code node_modules}/dist/.git 等构建产物目录：Vue 工程部署后 node_modules
     * 可能有数百个文件，不跳过会把「查看代码」/项目文件树淹没成依赖清单（Bug A）。
     */
    private static final Set<String> PROJECT_SKIP_DIRS = Set.of("node_modules", "dist", ".git");

    private Map<String, String> readProjectFiles(String dirPath) {
        Map<String, String> contents = new LinkedHashMap<>();
        File dir = new File(dirPath);
        if (!FileUtil.isDirectory(dir)) {
            return contents;
        }
        collectProjectFiles(dir, dirPath, contents);
        return contents;
    }

    /** 递归收集源码文件（跳过构建产物目录），保持相对路径稳定 */
    private void collectProjectFiles(File dir, String rootPath, Map<String, String> contents) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (PROJECT_SKIP_DIRS.contains(f.getName())) {
                    continue;
                }
                collectProjectFiles(f, rootPath, contents);
            } else if (f.isFile()) {
                String relPath = FileUtil.subPath(rootPath, f).replace('\\', '/');
                contents.put(relPath, FileUtil.readString(f, StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * 分页执行查询，pageSize 超过 {@code sizeLimit} 时截断到 {@code sizeLimit}。
     * 管理员列表传 {@link Integer#MAX_VALUE} 即不限制。
     */
    private Page<App> pageByRequest(PageRequest request,
                                    int sizeLimit, QueryWrapper queryWrapper) {
        int pageNum = request == null ? 1 : request.getPageNum();
        int pageSize = request == null ? 10 : request.getPageSize();
        if (pageSize <= 0) {
            pageSize = 10;
        }
        pageSize = Math.min(pageSize, sizeLimit);
        return this.page(new Page<>(pageNum, pageSize), queryWrapper);
    }

    /** 实体分页结果转成对外 VO 分页结果 */
    private Page<AppVO> toVOPage(Page<App> appPage) {
        List<AppVO> voList = appPage.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        Page<AppVO> voPage = new Page<>(appPage.getPageNumber(), appPage.getPageSize(), appPage.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    private AppVO toVO(App app) {
        AppVO appVO = new AppVO();
        appVO.setId(app.getId());
        appVO.setAppName(app.getAppName());
        appVO.setCover(app.getCover());
        appVO.setInitPrompt(app.getInitPrompt());
        appVO.setCodeGenType(app.getCodeGenType());
        appVO.setDeployKey(app.getDeployKey());
        appVO.setDeployedTime(app.getDeployedTime());
        // 已部署应用拼出 nginx 访问地址，供前端跳转
        appVO.setDeployUrl(StrUtil.isNotBlank(app.getDeployKey()) && StrUtil.isNotBlank(deployBaseUrl)
                ? deployBaseUrl + "/" + app.getDeployKey() + "/" : null);
        appVO.setPriority(app.getPriority());
        appVO.setUserId(app.getUserId());
        appVO.setCreateTime(app.getCreateTime());
        appVO.setUpdateTime(app.getUpdateTime());
        return appVO;
    }

}
