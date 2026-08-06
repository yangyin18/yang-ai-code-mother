package com.cg.yangaicodemother.controller;

import com.cg.yangaicodemother.annotation.AuthCheck;
import com.cg.yangaicodemother.common.BaseResponse;
import com.cg.yangaicodemother.common.DeleteRequest;
import com.cg.yangaicodemother.common.ResultUtils;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.exception.ThrowUtils;
import com.cg.yangaicodemother.model.dto.UserLoginRequest;
import com.cg.yangaicodemother.model.dto.UserQueryRequest;
import com.cg.yangaicodemother.model.dto.UserRegisterRequest;
import com.cg.yangaicodemother.model.dto.UserUpdateRequest;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户注册。
     *
     * @param registerRequest 注册请求
     * @return 新用户 id
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest registerRequest) {
        ThrowUtils.throwIf(registerRequest == null, ErrorCode.PARAMS_ERROR);
        long userId = userService.userRegister(registerRequest.getUserAccount(),
                registerRequest.getUserPassword(), registerRequest.getCheckPassword());
        return ResultUtils.success(userId);
    }

    /**
     * 用户登录。
     *
     * @param loginRequest 登录请求
     * @param request      HttpServletRequest
     * @return 脱敏后的登录用户信息
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest loginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(loginRequest == null, ErrorCode.PARAMS_ERROR);
        LoginUserVO loginUserVO = userService.login(loginRequest, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户。
     *
     * @param request HttpServletRequest
     * @return 脱敏后的登录用户信息
     */
    @GetMapping("/get/login")
    @AuthCheck
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        LoginUserVO loginUserVO = userService.getLoginUser(request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 仅管理员可访问的示例接口。
     * 普通用户（user）访问会返回 40101 无权限。
     *
     * @param request HttpServletRequest
     * @return 当前登录用户信息
     */
    @GetMapping("/admin/check")
    @AuthCheck(role = "admin")
    public BaseResponse<LoginUserVO> adminCheck(HttpServletRequest request) {
        return ResultUtils.success(userService.getLoginUser(request));
    }

    /**
     * 用户退出登录。
     *
     * @param request HttpServletRequest
     * @return 是否退出成功（未登录返回 false）
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        boolean result = userService.logout(request);
        return ResultUtils.success(result);
    }

    // ==================== 管理端：用户增删改查 ====================

    /**
     * 分页查询用户列表（管理员）。
     * GET /user/list/page?pageNum=1&pageSize=10&userAccount=xx&userRole=admin
     *
     * @param queryRequest 分页 + 过滤条件
     * @return 脱敏后的用户分页数据
     */
    @GetMapping("/list/page")
    @AuthCheck(role = "admin")
    public BaseResponse<Page<LoginUserVO>> listUserByPage(UserQueryRequest queryRequest) {
        if (queryRequest == null) {
            queryRequest = new UserQueryRequest();
        }
        Page<LoginUserVO> userPage = userService.getUserPage(queryRequest);
        return ResultUtils.success(userPage);
    }

    /**
     * 根据 id 查询用户（管理员）。
     * GET /user/get?id=xxx
     *
     * @param id 用户 id
     * @return 脱敏后的用户信息
     */
    @GetMapping("/get")
    @AuthCheck(role = "admin")
    public BaseResponse<LoginUserVO> getUserById(Long id) {
        ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        LoginUserVO userVO = userService.getUserById(id);
        ThrowUtils.throwIf(userVO == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return ResultUtils.success(userVO);
    }

    /**
     * 更新用户（管理员）。只更新传入的非空字段。
     * POST /user/update，body 示例：{"id": 1, "userName": "新名字", "userRole": "admin"}
     *
     * @param updateRequest 更新请求
     * @return 是否成功
     */
    @PostMapping("/update")
    @AuthCheck(role = "admin")
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest updateRequest) {
        ThrowUtils.throwIf(updateRequest == null || updateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        boolean result = userService.updateUser(updateRequest);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
        return ResultUtils.success(true);
    }

    /**
     * 删除用户（管理员）。逻辑删除。
     * POST /user/delete，body 示例：{"id": 1}
     *
     * @param deleteRequest 删除请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    @AuthCheck(role = "admin")
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        boolean result = userService.deleteUser(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
        return ResultUtils.success(true);
    }

}
