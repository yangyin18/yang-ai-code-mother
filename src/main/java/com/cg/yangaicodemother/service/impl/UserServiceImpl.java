package com.cg.yangaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.cg.yangaicodemother.constant.UserConstant;
import com.cg.yangaicodemother.exception.BusinessException;
import com.cg.yangaicodemother.exception.ErrorCode;
import com.cg.yangaicodemother.mapper.UserMapper;
import com.cg.yangaicodemother.model.dto.UserLoginRequest;
import com.cg.yangaicodemother.model.enums.UserRoleEnum;
import com.cg.yangaicodemother.model.dto.UserQueryRequest;
import com.cg.yangaicodemother.model.dto.UserUpdateRequest;
import com.cg.yangaicodemother.model.entity.User;
import com.cg.yangaicodemother.model.vo.LoginUserVO;
import com.cg.yangaicodemother.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.util.LambdaGetter;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户 服务层实现。
 *
 * @author 34488
 * @since 2026-08-06
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 允许排序的字段白名单：前端传 sortField 时只允许映射到这里的实体字段，
     * 防止把任意字符串拼进 ORDER BY 造成 SQL 注入。
     */
    private static final Map<String, LambdaGetter<User>> SORT_FIELD_MAP = Map.of(
            "id", User::getId,
            "userAccount", User::getUserAccount,
            "userName", User::getUserName,
            "userRole", User::getUserRole,
            "createTime", User::getCreateTime,
            "updateTime", User::getUpdateTime
    );

    @Override
    public Long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验参数
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        // 2. 查询用户是否已存在（注意 user 表列名是驼峰 userAccount，不是 user_account）
        long count = this.mapper.selectCountByQuery(QueryWrapper.create().where(User::getUserAccount).eq(userAccount));
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }

        // 3. 加密密码
        String encryptPassword = getEncryptPassword(userPassword);
        // 4. 创建用户，插入数据库
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "注册失败，数据库异常");
        }
        // 返回新增用户 id
        return user.getId();
    }

    @Override
    public LoginUserVO login(UserLoginRequest loginRequest, HttpServletRequest request) {
        String userAccount = loginRequest.getUserAccount();
        String userPassword = loginRequest.getUserPassword();
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号和密码不能为空");
        }

        User user = this.mapper.selectOneByQuery(QueryWrapper.create().where(User::getUserAccount).eq(userAccount));
        // 账号不存在（含逻辑删除）或密码错误，统一提示，避免暴露账号是否存在
        if (user == null || !user.getUserPassword().equals(getEncryptPassword(userPassword))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }

        // 登录态写入 session（CORS 已允许携带 Cookie）
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);
        return getLoginUserVO(user);
    }

    @Override
    public LoginUserVO getLoginUser(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 每次从数据库刷新，避免会话里的数据过期（如账号被禁用/注销）
        User user = this.mapper.selectOneById(currentUser.getId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户不存在或已注销");
        }
        return getLoginUserVO(user);
    }

    @Override
    public boolean logout(HttpServletRequest request) {
        if (request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE) == null) {
            return false;
        }
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        final String SALT = "yang";
        return DigestUtils.md5DigestAsHex((userPassword + SALT).getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 管理端：增删改查 ====================

    @Override
    public Page<LoginUserVO> getUserPage(UserQueryRequest queryRequest) {
        QueryWrapper queryWrapper = QueryWrapper.create();
        // 条件过滤：传了才拼，没传不拼
        if (queryRequest.getId() != null) {
            queryWrapper.where(User::getId).eq(queryRequest.getId());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserAccount())) {
            queryWrapper.where(User::getUserAccount).like(queryRequest.getUserAccount());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserName())) {
            queryWrapper.where(User::getUserName).like(queryRequest.getUserName());
        }
        if (StrUtil.isNotBlank(queryRequest.getUserRole())) {
            queryWrapper.where(User::getUserRole).eq(queryRequest.getUserRole());
        }
        // 排序：白名单映射，未知字段回退按创建时间倒序
        // 注意：Map.of 的不可变 Map 在 get(null) 时会抛 NPE，必须先判空
        LambdaGetter<User> sortColumn = StrUtil.isNotBlank(queryRequest.getSortField())
                ? SORT_FIELD_MAP.get(queryRequest.getSortField())
                : null;
        if (sortColumn != null) {
            boolean asc = "ascend".equalsIgnoreCase(queryRequest.getSortOrder());
            queryWrapper.orderBy(sortColumn, asc);
        } else {
            queryWrapper.orderBy(User::getCreateTime, false);
        }
        // 分页查询（逻辑删除的记录自动过滤）
        Page<User> userPage = this.page(new Page<>(queryRequest.getPageNum(), queryRequest.getPageSize()), queryWrapper);
        // 脱敏后返回，避免密码等敏感字段泄露给前端
        List<LoginUserVO> voList = userPage.getRecords().stream()
                .map(this::getLoginUserVO)
                .collect(Collectors.toList());
        Page<LoginUserVO> voPage = new Page<>(userPage.getPageNumber(), userPage.getPageSize(), userPage.getTotalRow());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public LoginUserVO getUserById(Long id) {
        User user = this.mapper.selectOneById(id);
        if (user == null) {
            return null;
        }
        return getLoginUserVO(user);
    }

    @Override
    public boolean updateUser(UserUpdateRequest updateRequest) {
        User user = this.mapper.selectOneById(updateRequest.getId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        // 只更新传入的非空字段
        if (StrUtil.isNotBlank(updateRequest.getUserAccount())) {
            user.setUserAccount(updateRequest.getUserAccount());
        }
        if (StrUtil.isNotBlank(updateRequest.getUserName())) {
            user.setUserName(updateRequest.getUserName());
        }
        if (updateRequest.getUserAvatar() != null) {
            user.setUserAvatar(updateRequest.getUserAvatar());
        }
        if (updateRequest.getUserProfile() != null) {
            user.setUserProfile(updateRequest.getUserProfile());
        }
        if (StrUtil.isNotBlank(updateRequest.getUserRole())) {
            // 校验角色取值，防止写入脏数据
            if (UserRoleEnum.getEnumByValue(updateRequest.getUserRole()) == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法角色");
            }
            user.setUserRole(updateRequest.getUserRole());
        }
        return this.updateById(user);
    }

    @Override
    public boolean deleteUser(Long id) {
        // mybatis-flex 逻辑删除：isDelete 标注了 @Column(isLogicDelete=true)，删后查询自动过滤
        return this.removeById(id);
    }

    @Override
    public Map<Long, String> getUserAccountMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // 批量 in 查询：返回 id → 账号，供管理端列表回填拥有者账号（避免 N+1）
        return this.mapper.selectListByQuery(
                        QueryWrapper.create().where(User::getId).in(userIds))
                .stream()
                .filter(u -> u.getId() != null && StrUtil.isNotBlank(u.getUserAccount()))
                .collect(Collectors.toMap(User::getId, User::getUserAccount, (a, b) -> a));
    }

    /**
     * 脱敏：返回给前端的用户信息不包含密码等敏感字段。
     */
    private LoginUserVO getLoginUserVO(User user) {
        LoginUserVO loginUserVO = new LoginUserVO();
        loginUserVO.setId(user.getId());
        loginUserVO.setUserAccount(user.getUserAccount());
        loginUserVO.setUserName(user.getUserName());
        loginUserVO.setUserAvatar(user.getUserAvatar());
        loginUserVO.setUserProfile(user.getUserProfile());
        loginUserVO.setUserRole(user.getUserRole());
        loginUserVO.setCreateTime(user.getCreateTime());
        loginUserVO.setUpdateTime(user.getUpdateTime());
        return loginUserVO;
    }

}
