package com.cg.yangaicodemother.model.dto;

import com.cg.yangaicodemother.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用分页查询请求（用户）。
 *
 * <p>用户侧「我的应用列表」与「精选应用列表」共用此请求，仅支持按应用名称过滤，
 * 每页最多 20 个。</p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppQueryRequest extends PageRequest {

    private static final long serialVersionUID = 1L;

    /**
     * 应用名称（模糊匹配）
     */
    private String name;

}
