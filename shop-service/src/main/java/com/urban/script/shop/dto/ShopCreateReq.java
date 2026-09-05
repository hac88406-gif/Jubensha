package com.urban.script.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建门店请求 DTO
 *
 * @author urban-script-reservation
 */
@Data
public class ShopCreateReq {

    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 100, message = "店铺名称最长 100")
    private String name;

    @Size(max = 255, message = "地址最长 255")
    private String address;

    @Size(max = 20, message = "电话最长 20")
    private String phone;

    /** 店长 user_id（可选，通常由系统根据 token 注入） */
    private Long ownerId;
}
