package com.cg.yangaicodemother.service;

import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 应用 服务层。
 *
 * @author 34488
 * @since 2026-08-08
 */
public interface AppService extends IService<App> {

    // ==================== 用户端：我的应用 ====================

    /**
     * 创建应用。initPrompt 必填，应用归属当前登录用户。
     *
     * @param createRequest 创建请求
     * @param request       HttpServletRequest
     * @return 新应用 id
     */
    Long createApp(AppCreateRequest createRequest, HttpServletRequest request);

    /**
     * 修改自己的应用（用户）。目前仅支持修改应用名称，且只能操作自己的应用。
     *
     * @param updateRequest 更新请求
     * @param request       HttpServletRequest
     * @return 是否成功
     */
    boolean updateApp(AppUpdateRequest updateRequest, HttpServletRequest request);

    /**
     * 删除自己的应用（用户）。逻辑删除，且只能操作自己的应用。
     *
     * @param id      应用 id
     * @param request HttpServletRequest
     * @return 是否成功
     */
    boolean deleteApp(Long id, HttpServletRequest request);

    /**
     * 根据 id 查看应用详情（用户）。可查看任意未删除应用（含精选应用）。
     *
     * @param id 应用 id
     * @return 应用信息，不存在返回 null
     */
    AppVO getAppById(Long id);

    /**
     * 分页查询自己的应用列表（用户）。支持按名称模糊查询，每页最多 20 个。
     *
     * @param queryRequest 分页查询条件
     * @param request      HttpServletRequest
     * @return 分页结果
     */
    Page<AppVO> getMyAppPage(AppQueryRequest queryRequest, HttpServletRequest request);

    /**
     * 分页查询精选的应用列表（用户）。
     * 精选 = 已部署（deployKey 非空）的应用，按优先级降序、创建时间降序。
     * 支持按名称模糊查询，每页最多 20 个。
     *
     * @param queryRequest 分页查询条件
     * @return 分页结果
     */
    Page<AppVO> getFeaturedAppPage(AppQueryRequest queryRequest);

    // ==================== 管理端：应用管理 ====================

    /**
     * 根据 id 删除任意应用（管理员）。逻辑删除。
     *
     * @param id 应用 id
     * @return 是否成功
     */
    boolean adminDeleteApp(Long id);

    /**
     * 根据 id 更新任意应用（管理员）。支持更新应用名称、应用封面、优先级，
     * 只更新传入的非空字段。
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    boolean adminUpdateApp(AppAdminUpdateRequest updateRequest);

    /**
     * 分页查询应用列表（管理员）。支持按除时间外的任意字段过滤，每页数量不限。
     *
     * @param queryRequest 分页查询条件
     * @return 分页结果
     */
    Page<AppVO> adminGetAppPage(AppAdminQueryRequest queryRequest);

    /**
     * 根据 id 查看应用详情（管理员）。
     *
     * @param id 应用 id
     * @return 应用信息，不存在返回 null
     */
    AppVO adminGetAppById(Long id);

}
