package com.urban.script.shop.controller;

import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.shop.dto.SessionCreateReq;
import com.urban.script.shop.dto.SessionFeignRes;
import com.urban.script.shop.dto.SessionRes;
import com.urban.script.shop.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场次 Controller
 *
 * <p>路由设计：
 * <ul>
 *   <li>玩家端公开：查剧本/店铺的未来 7 天场次、场次详情</li>
 *   <li>店长管理端：创建场次、关闭场次（@RequireRole("ROLE_SHOP_OWNER")）</li>
 *   <li>内部接口：/internal/session/{id} —— 给 order-service Feign 调用，Gateway 白名单放行</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Tag(name = "场次管理", description = "场次 CRUD + DM 冲突检测 + 关闭场次发 MQ")
@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    // ===================== 玩家端（公开查询） =====================

    @Operation(summary = "剧本未来 7 天场次（玩家端）")
    @GetMapping("/list/script/{scriptId}")
    public R<List<SessionRes>> listByScript(@PathVariable Long scriptId) {
        return R.ok(sessionService.listSessionsByScript(scriptId));
    }

    @Operation(summary = "店铺未来 7 天场次（玩家端）")
    @GetMapping("/list/shop/{shopId}")
    public R<List<SessionRes>> listByShop(@PathVariable Long shopId) {
        return R.ok(sessionService.listSessionsByShop(shopId));
    }

    @Operation(summary = "场次详情（玩家端）")
    @GetMapping("/{id}")
    public R<SessionRes> get(@PathVariable Long id) {
        return R.ok(sessionService.getSessionDetail(id));
    }

    // ===================== 店长管理端 =====================

    @Operation(summary = "创建场次（店长）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PostMapping
    public R<Long> create(@Valid @RequestBody SessionCreateReq req,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        // 传入操作人身份做店长归属校验（防越权操作他人店铺）
        return R.ok("创建成功", sessionService.createSession(req, userId, role));
    }

    @Operation(summary = "关闭场次（店长，开场前 2 小时外）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id,
                         @RequestHeader(value = "X-User-Id", required = false) Long operatorId,
                         @RequestHeader(value = "X-User-Role", required = false) String role) {
        sessionService.closeSession(id, operatorId, role);
        return R.ok();
    }

    // ===================== 内部 Feign 接口（order-service 专用） =====================
    // Gateway 白名单放行此路径，不走鉴权

    @Operation(summary = "场次详情（内部 Feign，供 order-service 调用）", hidden = true)
    @GetMapping("/internal/{id}")
    public R<SessionFeignRes> getForFeign(@PathVariable Long id,
                                          @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        return R.ok(sessionService.getSessionForFeign(id));
    }
}
