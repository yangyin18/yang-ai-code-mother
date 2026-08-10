package com.cg.yangaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.common.PageRequest;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.mapper.AppMapper;
import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.enums.CodeGenTypeEnum;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
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

import java.util.List;
import java.util.Map;
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
    public Page<AppVO> getMyAppPage(AppQueryRequest queryRequest, HttpServletRequest request) {
        LoginUserVO loginUser = userService.getLoginUser(request);
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 只查自己的应用
        queryWrapper.where(App::getUserId).eq(loginUser.getId());
        if (queryRequest != null && StrUtil.isNotBlank(queryRequest.getName())) {
            queryWrapper.where(App::getAppName).like(queryRequest.getName());
        }
        queryWrapper.orderBy(App::getCreateTime, false);
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
        LoginUserVO loginUser = userService.getLoginUser(request);
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = getOwnedApp(appId, loginUser.getId());
        // 发布文件到 nginx 站点根目录，并拼出访问地址
        DeployResult result = deployService.deploy(app);
        // 写回部署标识与部署时间。部署后不自动进入广场，需管理员在「应用管理」设置优先级
        app.setDeployKey(result.deployKey());
        app.setDeployedTime(result.deployedTime());
        this.updateById(app);
        return result;
    }

    @Override
    public DeployResult redeployApp(Long appId) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不能为空");
        }
        App app = this.mapper.selectOneById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 复用原 deployKey（访问地址稳定），把最新生成的代码覆盖到 nginx 站点
        DeployResult result = deployService.deploy(app);
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
