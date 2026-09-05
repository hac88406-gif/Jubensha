package com.urban.script.shop.service;

import com.urban.script.shop.dto.ShopCreateReq;
import com.urban.script.shop.dto.ShopRes;

import java.util.List;

/**
 * 门店服务接口
 *
 * @author urban-script-reservation
 */
public interface ShopService {

    /** 创建门店 —— 返回新门店 id */
    Long createShop(ShopCreateReq req);

    /** 门店列表（玩家端默认只查 status=1） */
    List<ShopRes> listShops(Integer status);

    /** 单个门店详情 */
    ShopRes getShop(Long id);

    /** 更新门店（店长管理端） */
    void updateShop(Long id, ShopCreateReq req);

    /** 删除门店（逻辑删除：status → 0） */
    void deleteShop(Long id);
}
