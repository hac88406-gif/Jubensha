package com.urban.script.shop.dto;

import com.urban.script.shop.entity.ShopInfo;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门店响应 DTO（脱敏 / 不含敏感字段）
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class ShopRes {

    private Long id;
    private String name;
    private String address;
    private String phone;
    private Long ownerId;
    private Integer status;
    private LocalDateTime createTime;

    /** 从实体转 DTO */
    public static ShopRes fromEntity(ShopInfo entity) {
        if (entity == null) return null;
        return ShopRes.builder()
                .id(entity.getId())
                .name(entity.getName())
                .address(entity.getAddress())
                .phone(entity.getPhone())
                .ownerId(entity.getOwnerId())
                .status(entity.getStatus())
                .createTime(entity.getCreateTime())
                .build();
    }
}
