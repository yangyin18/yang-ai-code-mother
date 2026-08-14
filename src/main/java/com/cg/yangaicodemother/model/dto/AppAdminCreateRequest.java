package com.cg.yangaicodemother.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 应用创建请求（管理员）。
 *
 * <p>在用户侧创建字段基础上多一个优先级：管理员新建应用卡片进广场时
 * 可直接指定 priority（&gt;0 即进入应用广场），归属当前登录管理员。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppAdminCreateRequest extends AppCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 优先级（数字越大越靠前，精选列表按此排序；&gt;0 进广场）
     */
    private Integer priority;

}
