package com.cg.yangaicodemother.service;

import com.cg.yangaicodemother.model.dto.AppAdminQueryRequest;
import com.cg.yangaicodemother.model.dto.AppAdminUpdateRequest;
import com.cg.yangaicodemother.model.dto.AppCreateRequest;
import com.cg.yangaicodemother.model.dto.AppEditStyleRequest;
import com.cg.yangaicodemother.model.dto.AppQueryRequest;
import com.cg.yangaicodemother.model.dto.AppUpdateRequest;
import com.cg.yangaicodemother.model.entity.App;
import com.cg.yangaicodemother.model.vo.AppCodeVO;
import com.cg.yangaicodemother.model.vo.AppVO;
import com.cg.yangaicodemother.model.vo.DeployResult;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.function.Consumer;

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
     * 查看应用已生成的代码文件（供「查看代码」弹窗）。
     * 权限：应用本人 / 管理员 / 已部署（public 上线的应用，代码随站点公开）可查看。
     *
     * @param id      应用 id
     * @param request HttpServletRequest
     * @return 代码内容（无代码目录时 fileNames 为空）
     */
    AppCodeVO getAppCode(Long id, HttpServletRequest request);

    /**
     * 直接修改应用代码里的文字（可视化编辑「选中元素 → 改文字 → 保存」，不调 AI）。
     *
     * <p>在已生成的代码文件里把 {@code oldText} 全局替换为 {@code newText} 并写回，
     * 其余代码原样保留（小幅度修改）。权限：仅应用本人 / 管理员可改（已部署公开用户不能改他人代码）。
     *
     * @param appId   应用 id
     * @param oldText 原文字（须在代码文件中出现，否则报错）
     * @param newText 新文字
     * @param request HttpServletRequest
     * @return 更新后的代码（含 html/css/js 与文件名列表，前端刷新预览用）
     */
    AppCodeVO editAppCodeText(Long appId, String oldText, String newText, HttpServletRequest request);

    /**
     * 直接修改应用代码里目标元素的样式（可视化编辑「选中元素 → 改颜色/内边距/外边距 → 保存」，不调 AI）。
     *
     * <p>在已生成的 index.html 里定位目标元素开标签（锚定优先级：id &gt; 元素文本 &gt; class 首段），
     * 把 {@code style} 属性合并进该元素 style 属性（同名覆盖、其余内联样式保留），其余代码原样不动。
     * 权限：仅应用本人 / 管理员可改（已部署公开用户不能改他人代码）。
     *
     * @param editStyleRequest 应用 id + 元素定位信息 + 要改的样式属性
     * @param request          HttpServletRequest
     * @return 更新后的代码（含 html/css/js 与文件名列表，前端刷新预览用）
     */
    AppCodeVO editAppCodeStyle(AppEditStyleRequest editStyleRequest, HttpServletRequest request);

    /**
     * 定位应用已生成代码的保存目录（供下载 ZIP 包）。权限与 {@link #getAppCode} 一致：
     * 本人 / 管理员 / 已部署应用可下载。目录可能不存在（还没生成过代码），由调用方判空。
     *
     * @param id      应用 id
     * @param request HttpServletRequest
     * @return 保存目录绝对路径
     */
    String downloadAppCode(Long id, HttpServletRequest request);

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
     * 精选 = 管理员手动置顶（priority &gt; 0）的应用，按优先级降序、创建时间降序；
     * 用户部署应用不再自动进入广场，需管理员在「应用管理」设置优先级。
     * 支持按名称模糊查询，每页最多 20 个。
     *
     * @param queryRequest 分页查询条件
     * @return 分页结果
     */
    Page<AppVO> getFeaturedAppPage(AppQueryRequest queryRequest);

    /**
     * 部署自己的应用（用户）。把已生成的代码发布到 nginx，写回 deployKey 与部署时间。
     * 部署后不自动进入广场，需管理员在「应用管理」设置优先级。
     *
     * @param appId   应用 id
     * @param request HttpServletRequest
     * @return 部署结果（含 deployKey 与访问地址）
     */
    DeployResult deployApp(Long appId, HttpServletRequest request);

    /**
     * 部署自己的应用（用户），支持实时进度回调（供 SSE 流式部署展示）。
     * 与 {@link #deployApp} 逻辑完全一致，仅多一个进度回调：
     * 每个部署阶段（修复引用 / 安装依赖 / 构建 / 发布 / 完成）以及 npm 输出的每一行都会回调。
     *
     * @param appId    应用 id
     * @param request  HttpServletRequest
     * @param progress 部署进度回调，可为 null
     * @return 部署结果（含 deployKey 与访问地址）
     */
    DeployResult deployAppStream(Long appId, HttpServletRequest request, Consumer<String> progress);

    /**
     * 重新部署已部署的应用（服务端内部使用，不做登录与归属校验）。
     * 复用原 deployKey（访问地址稳定），把最新生成的代码覆盖到 nginx 站点。
     * 供「对话即改代码」在聊天自动更新应用后调用；调用前需确认应用已部署。
     *
     * @param appId 应用 id
     * @return 部署结果（含 deployKey 与访问地址）
     */
    DeployResult redeployApp(Long appId);

    /**
     * 重新部署已部署的应用，支持实时进度回调（供「对话即改代码」自动更新时流式反馈部署进度）。
     * 与 {@link #redeployApp} 逻辑一致，仅多一个进度回调：每个部署阶段与 npm 输出逐行回调。
     *
     * @param appId    应用 id
     * @param progress 部署进度回调，可为 null
     * @return 部署结果（含 deployKey 与访问地址）
     */
    DeployResult redeployAppStream(Long appId, Consumer<String> progress);

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
