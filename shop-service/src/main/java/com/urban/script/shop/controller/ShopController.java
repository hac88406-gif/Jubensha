package com.urban.script.shop.controller;

import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.shop.dto.ShopCreateReq;
import com.urban.script.shop.dto.ShopRes;
import com.urban.script.shop.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店 Controller
 *
 * <p>路由设计：
 * <ul>
 *   <li>玩家端 —— 无 @RequireRole，所有人可查营业中的门店</li>
 *   <li>店长管理端 —— 带 {@code @RequireRole("ROLE_SHOP_OWNER")}，必须带正确 Header</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Tag(name = "门店管理", description = "玩家端查询 + 店长管理端 CRUD")
@RestController
@RequestMapping("/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    // ===================== 玩家端（公开查询） =====================

    @Operation(summary = "门店列表（玩家端，只返回营业中的）")
    @GetMapping("/list")
    public R<List<ShopRes>> list() {
        return R.ok(shopService.listShops(1));
    }

    @Operation(summary = "门店详情（玩家端）")
    @GetMapping("/{id}")
    public R<ShopRes> get(@PathVariable Long id) {
        return R.ok(shopService.getShop(id));
    }

    // ===================== 店长管理端（必须 ROLE_SHOP_OWNER） =====================

    /** 创建门店 —— 店长 / 管理员可用 */
    @Operation(summary = "创建门店（店长）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PostMapping("/create")
    public R<Long> create(@Valid @RequestBody ShopCreateReq req,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 如果前端没传 ownerId，用当前登录的 user_id 兜底
        if (req.getOwnerId() == null) {
            req.setOwnerId(userId);
        }
        return R.ok("创建成功", shopService.createShop(req));
    }

    /** 管理端查询全部门店（含已关闭的） */
    @Operation(summary = "门店列表（管理端，可查全部状态）")
    @RequireRole("ROLE_SHOP_OWNER")
    @GetMapping("/manage/list")
    public R<List<ShopRes>> manageList(@RequestParam(required = false) Integer status,
                                       @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                       @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok(shopService.listShops(status));
    }

    @Operation(summary = "更新门店（店长）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PutMapping("/{id}/update")
    public R<Void> update(@PathVariable Long id,
                          @Valid @RequestBody ShopCreateReq req,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        shopService.updateShop(id, req);
        return R.ok();
    }

    @Operation(summary = "关闭门店（店长，逻辑删除 status→0）")
    @RequireRole("ROLE_SHOP_OWNER")
    @DeleteMapping("/{id}/delete")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        shopService.deleteShop(id);
        return R.ok();
    }
}
