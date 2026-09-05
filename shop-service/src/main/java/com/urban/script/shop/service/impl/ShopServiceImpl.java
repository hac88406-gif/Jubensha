package com.urban.script.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.urban.script.common.BusinessException;
import com.urban.script.common.ResultCode;
import com.urban.script.shop.dto.ShopCreateReq;
import com.urban.script.shop.dto.ShopRes;
import com.urban.script.shop.entity.ShopInfo;
import com.urban.script.shop.mapper.ShopMapper;
import com.urban.script.shop.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店服务实现
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;

    @Override
    public Long createShop(ShopCreateReq req) {
        ShopInfo shop = new ShopInfo();
        shop.setName(req.getName());
        shop.setAddress(req.getAddress());
        shop.setPhone(req.getPhone());
        shop.setOwnerId(req.getOwnerId());
        shop.setStatus(1);
        shopMapper.insert(shop);
        log.info("[createShop] id={}, name={}", shop.getId(), shop.getName());
        return shop.getId();
    }

    @Override
    public List<ShopRes> listShops(Integer status) {
        QueryWrapper<ShopInfo> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        } else {
            wrapper.eq("status", 1); // 默认只查营业中
        }
        wrapper.orderByDesc("create_time");
        return shopMapper.selectList(wrapper).stream()
                .map(ShopRes::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ShopRes getShop(Long id) {
        ShopInfo shop = shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "门店不存在");
        }
        return ShopRes.fromEntity(shop);
    }

    @Override
    public void updateShop(Long id, ShopCreateReq req) {
        ShopInfo shop = shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "门店不存在");
        }
        shop.setName(req.getName());
        shop.setAddress(req.getAddress());
        shop.setPhone(req.getPhone());
        if (req.getOwnerId() != null) {
            shop.setOwnerId(req.getOwnerId());
        }
        shopMapper.updateById(shop);
        log.info("[updateShop] id={}", id);
    }

    @Override
    public void deleteShop(Long id) {
        ShopInfo shop = shopMapper.selectById(id);
        if (shop == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "门店不存在");
        }
        // 逻辑删除：status → 0
        ShopInfo update = new ShopInfo();
        update.setId(id);
        update.setStatus(0);
        shopMapper.updateById(update);
        log.info("[deleteShop] id={}, name={} closed", id, shop.getName());
    }
}
