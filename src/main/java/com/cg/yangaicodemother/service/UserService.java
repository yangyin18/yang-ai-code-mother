package com.cg.yangaicodemother.service;

import com.cg.yangaicodemother.model.dto.UserLoginRequest;
import com.cg.yangaicodemother.model.dto.UserQueryRequest;
import com.cg.yangaicodemother.model.dto.UserUpdateRequest;
import com.cg.yangaicodemother.model.entity.User;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户 服务层。
 *
 * @author 34488
 * @since 2026-08-06
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册。
     *
     * @param userAccount    账号
     * @param userPassword   密码
     * @param checkPassword  确认密码
     * @return 新用户 id
     */
    Long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录，登录态写入 session。
     *
     * @param loginRequest 登录请求
     * @param request      HttpServletRequest
     * @return 脱敏后的登录用户信息
     */
    LoginUserVO login(UserLoginRequest loginRequest, HttpServletRequest request);

    /**
     * 获取当前登录用户（未登录抛 NOT_LOGIN_ERROR）。
     *
     * @param request HttpServletRequest
     * @return 脱敏后的登录用户信息
     */
    LoginUserVO getLoginUser(HttpServletRequest request);

    /**
     * 用户退出登录，清除 session 中的登录态。
     *
     * @param request HttpServletRequest
     * @return 是否退出成功（未登录返回 false）
     */
    boolean logout(HttpServletRequest request);

    /**
     * 加密用户密码。
     *
     * @param userPassword 明文密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String userPassword);

    // ==================== 管理端：增删改查 ====================

    /**
     * 分页查询用户（管理员）。
     * 支持按 id / 账号（模糊）/ 昵称（模糊）/ 角色过滤，结果已脱敏。
     *
     * @param queryRequest 分页查询条件
     * @return 分页结果
     */
    Page<LoginUserVO> getUserPage(UserQueryRequest queryRequest);

    /**
     * 根据 id 查询用户（管理员），已脱敏。
     *
     * @param id 用户 id
     * @return 脱敏后的用户信息，不存在返回 null
     */
    LoginUserVO getUserById(Long id);

    /**
     * 更新用户（管理员）。只更新传入的非空字段。
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    boolean updateUser(UserUpdateRequest updateRequest);

    /**
     * 删除用户（管理员）。逻辑删除（isDelete=1），查询时自动过滤。
     *
     * @param id 用户 id
     * @return 是否成功
     */
    boolean deleteUser(Long id);

}
