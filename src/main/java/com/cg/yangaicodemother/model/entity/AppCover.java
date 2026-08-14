package com.cg.yangaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用封面图 实体类。
 *
 * <p>封面截图（对话页 /chat/:appId 的 PNG）持久化到 DB，与应用 1:1；
 * app.cover 只存相对 URL /api/app/cover/{appId}，图片字节在本表。</p>
 *
 * @author 34488
 * @since 2026-08-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_cover")
public class AppCover implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 应用 id（1:1 对应 app.id，不做雪花自增）
     */
    @Id(keyType = KeyType.None)
    @Column("app_id")
    private Long appId;

    /**
     * 封面截图 PNG 字节（longblob）
     */
    @Column("image")
    private byte[] image;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

}
