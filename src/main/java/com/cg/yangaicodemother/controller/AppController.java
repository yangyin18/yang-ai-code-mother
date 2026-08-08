package com.cg.yangaicodemother.controller;

import com.cg.yangaicodemother.annotation.AuthCheck;
import com.cg.yangaicodemother.common.BaseResponse;
import com.cg.yangaicodemother.common.DeleteRequest;
import com.cg.yangaicodemother.common.ResultUtils;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.exception.ThrowUtils;
import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.cg.yangaicodemother.service.AppService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用接口。
 */
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

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
     * 分页查询精选的应用列表（用户）。精选 = 已部署的应用，按优先级降序。
     * 每页最多 20 个，支持按名称模糊查询。
     * GET /app/featured/list/page?pageNum=1&pageSize=20&name=待办
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 精选应用分页数据
     */
    @GetMapping("/featured/list/page")
    @AuthCheck
    public BaseResponse<Page<AppVO>> listFeaturedAppByPage(AppQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new AppQueryRequest();
        }
        Page<AppVO> appPage = appService.getFeaturedAppPage(queryRequest);
        return ResultUtils.success(appPage);
    }

    // ==================== 管理端：应用管理 ====================

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
     * 根据 id 更新任意应用（管理员）。支持更新应用名称、应用封面、优先级。
     * POST /app/admin/update，body 示例：{"id": 1, "appName": "新名", "cover": "url", "priority": 5}
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

}
