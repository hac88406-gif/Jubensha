package com.urban.script.shop.controller;

import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.shop.dto.ScriptCreateReq;
import com.urban.script.shop.dto.ScriptQueryReq;
import com.urban.script.shop.dto.ScriptRes;
import com.urban.script.shop.service.ScriptService;
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
 * 剧本 Controller
 *
 * <p>路由设计：
 * <ul>
 *   <li>玩家端 —— 查上架剧本（status=1），支持 type / playerCnt / shopId 筛选</li>
 *   <li>店长管理端 —— CRUD，带 {@code @RequireRole("ROLE_SHOP_OWNER")}</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Tag(name = "剧本管理", description = "玩家端筛选 + 店长管理端 CRUD")
@RestController
@RequestMapping("/script")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;

    // ===================== 玩家端（公开查询） =====================

    @Operation(summary = "剧本列表（玩家端，固定 status=1）")
    @GetMapping("/list")
    public R<List<ScriptRes>> list(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer playerCnt,
            @RequestParam(required = false) String nameKeyword) {
        ScriptQueryReq q = new ScriptQueryReq();
        q.setShopId(shopId);
        q.setType(type);
        q.setPlayerCnt(playerCnt);
        q.setNameKeyword(nameKeyword);
        q.setStatus(1); // 玩家端只能看上架的
        return R.ok(scriptService.listScripts(q));
    }

    @Operation(summary = "剧本详情（玩家端）")
    @GetMapping("/{id}")
    public R<ScriptRes> get(@PathVariable Long id) {
        return R.ok(scriptService.getScript(id));
    }

    // ===================== 店长管理端 =====================

    @Operation(summary = "剧本列表（管理端，可查全部状态）")
    @RequireRole("ROLE_SHOP_OWNER")
    @GetMapping("/manage/list")
    public R<List<ScriptRes>> manageList(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer playerCnt,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String nameKeyword,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        ScriptQueryReq q = new ScriptQueryReq();
        q.setShopId(shopId);
        q.setType(type);
        q.setPlayerCnt(playerCnt);
        q.setStatus(status); // 管理端传什么就查什么
        q.setNameKeyword(nameKeyword);
        return R.ok(scriptService.listScripts(q));
    }

    @Operation(summary = "创建剧本（店长）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PostMapping("/create")
    public R<Long> create(@Valid @RequestBody ScriptCreateReq req,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok("创建成功", scriptService.createScript(req));
    }

    @Operation(summary = "更新剧本（店长）")
    @RequireRole("ROLE_SHOP_OWNER")
    @PutMapping("/{id}/update")
    public R<Void> update(@PathVariable Long id,
                          @Valid @RequestBody ScriptCreateReq req,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        scriptService.updateScript(id, req);
        return R.ok();
    }

    @Operation(summary = "下架剧本（店长，逻辑删除 status→0）")
    @RequireRole("ROLE_SHOP_OWNER")
    @DeleteMapping("/{id}/delete")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-User-Id", required = false) Long userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        scriptService.deleteScript(id);
        return R.ok();
    }

    // ===================== 内部 Feign 接口（agent-gateway 专用） =====================
    // 鉴权由 InternalApiKeyFilter 统一拦截 /internal/** 路径，校验 X-Internal-Api-Key

    /**
     * 内部剧本列表（agent-gateway 代理 Python Agent 调用）
     * <p>只返回上架剧本（status=1），不做权限校验（Filter 已兜底）。
     * <p>shopId 与玩家端公开接口 /script/list 对齐：支持 AI 回答"XX 店有什么本"。
     */
    @Operation(summary = "内部：剧本列表（agent-gateway / agent 用）", hidden = true)
    @GetMapping("/internal/list")
    public R<List<ScriptRes>> internalList(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer playerCnt,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        ScriptQueryReq q = new ScriptQueryReq();
        q.setShopId(shopId);
        q.setType(type);
        q.setPlayerCnt(playerCnt);
        q.setStatus(1);  // 内部接口也只查上架的
        return R.ok(scriptService.listScripts(q));
    }

    /**
     * 内部剧本详情（agent-gateway 代理 Python Agent 调用）
     * <p>与玩家端 /script/{id} 同逻辑：返回完整富化字段（background / tags / mark /
     * maleNum / femaleNum / unknownNum / characters），供 AI 陪练的剧情问答与角色介绍工具使用。
     * <p>列表接口（internalList）剥离了 characters 避免 payload 过大，详情按需取。
     */
    @Operation(summary = "内部：剧本详情（agent-gateway / agent 用）", hidden = true)
    @GetMapping("/internal/{id}")
    public R<ScriptRes> internalGet(@PathVariable Long id,
                                    @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        return R.ok(scriptService.getScript(id));
    }
}
