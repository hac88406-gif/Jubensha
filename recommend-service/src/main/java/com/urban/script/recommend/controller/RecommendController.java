package com.urban.script.recommend.controller;

import com.urban.script.common.R;
import com.urban.script.recommend.dto.ScriptSyncReq;
import com.urban.script.recommend.dto.OrderSyncReq;
import com.urban.script.recommend.service.GraphSyncService;
import com.urban.script.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 推荐 Controller
 * <p>
 * 公开接口：个性化推荐 / 热门 / 相似剧本
 * 内部接口（X-Internal-Api-Key）：剧本同步 / 订单同步
 */
@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final GraphSyncService graphSyncService;
    private final StringRedisTemplate redisTemplate;

    /** 个性化推荐（登录用户） */
    @GetMapping
    public R<List<Map<String, Object>>> recommend(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            // 未登录 → 热门
            return R.ok(recommendService.hot(null, 10));
        }
        return R.ok(recommendService.recommend(userId, 10));
    }

    /** 热门推荐（可按类型） */
    @GetMapping("/hot")
    public R<List<Map<String, Object>>> hot(@RequestParam(required = false) String type) {
        return R.ok(recommendService.hot(type, 20));
    }

    /** 相似剧本（详情页"猜你喜欢"） */
    @GetMapping("/similar/{scriptId}")
    public R<List<Map<String, Object>>> similar(@PathVariable Long scriptId) {
        return R.ok(recommendService.similar(scriptId, 6));
    }

    /** 图谱统计（调试用） */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(graphSyncService.stats());
    }

    /**
     * 推荐解释图（P2 可视化）：用户玩过的剧本 → 推荐剧本 的关联链及原因
     * <p>未登录（无 X-User-Id）时返回空图，前端提示登录后查看。
     */
    @GetMapping("/graph/explain")
    public R<Map<String, Object>> graphExplain(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {
        if (userId == null) {
            return R.ok(Map.of("played", List.of(), "recs", List.of(), "links", List.of()));
        }
        return R.ok(recommendService.graphExplain(userId, limit));
    }

    /** 图谱快照（P2 可视化）：限量的剧本节点 + 相关边 */
    @GetMapping("/graph/snapshot")
    public R<Map<String, Object>> graphSnapshot(
            @RequestParam(required = false, defaultValue = "30") Integer limit) {
        return R.ok(graphSyncService.graphSnapshot(limit));
    }

    // ===================== 内部同步接口 =====================

    /**
     * 智能选本（内部，Cypher 过滤）—— AI 陪练"帮我挑本"工具用
     * <p>由 agent-gateway 代理，Python Agent 通过内部接口调用。
     */
    @GetMapping("/internal/filter")
    public R<List<Map<String, Object>>> internalFilter(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer playerCnt,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        return R.ok(graphSyncService.smartFilter(type, tag, playerCnt, limit));
    }

    /** 剧本同步（shop-service 爬虫/创建剧本后调用） */
    @PostMapping("/internal/sync/script")
    public R<Void> syncScript(@RequestBody ScriptSyncReq req) {
        graphSyncService.syncScript(
                req.getScriptId(), req.getName(), req.getScriptType(),
                req.getPlayerMin(), req.getPlayerMax(),
                req.getPrice(), req.getMark(), req.getImage(),
                req.getAuthor(), req.getTags(), req.getCharacters());
        return R.ok("同步成功", null);
    }

    /** 订单同步（order-service 支付成功后调用） */
    @PostMapping("/internal/sync/order")
    public R<Void> syncOrder(@RequestBody OrderSyncReq req) {
        graphSyncService.syncOrder(req.getUserId(), req.getUsername(), req.getScriptId());
        // 失效该用户推荐缓存
        redisTemplate.delete("rec:" + req.getUserId());
        return R.ok("同步成功", null);
    }

    /** 重建跨本同名角色关系（人物联动，幂等） */
    @PostMapping("/internal/rebuild/same-name")
    public R<Void> rebuildSameName() {
        graphSyncService.buildSameNameEntities();
        return R.ok("同步成功", null);
    }
}
