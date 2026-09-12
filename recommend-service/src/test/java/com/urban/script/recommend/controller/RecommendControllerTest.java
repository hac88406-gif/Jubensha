package com.urban.script.recommend.controller;

import com.urban.script.common.R;
import com.urban.script.recommend.service.GraphSyncService;
import com.urban.script.recommend.service.RecommendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * RecommendController 单元测试
 * <p>
 * 覆盖「前端为你推荐 / 猜你喜欢」对接的公开接口路由：
 *   GET /api/recommend                  —— 无 X-User-Id 回退热门 / 有则个性化
 *   GET /api/recommend/hot?type=        —— 热门榜
 *   GET /api/recommend/similar/{id}     —— 相似剧本
 *   GET /api/recommend/stats            —— 图谱统计
 * 纯 Mockito 构造 Controller（不走 Spring MVC 容器，验证服务编排与参数透传）。
 */
class RecommendControllerTest {

    private RecommendService recommendService;
    private GraphSyncService graphSyncService;
    private RecommendController controller;

    @BeforeEach
    void setUp() {
        recommendService = mock(RecommendService.class);
        graphSyncService = mock(GraphSyncService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        // @RequiredArgsConstructor：new 时按字段声明顺序注入（recommendService, graphSyncService, redisTemplate）
        controller = new RecommendController(recommendService, graphSyncService, redisTemplate);
    }

    @Test
    @DisplayName("个性化推荐：无 X-User-Id 时回退热门榜")
    void recommend_withoutUserId_fallsBackToHot() {
        Map<String, Object> hot = new HashMap<>();
        hot.put("scriptId", 150L);
        when(recommendService.hot(isNull(), eq(10))).thenReturn(List.of(hot));

        R<List<Map<String, Object>>> resp = controller.recommend(null);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        // 未登录必须走 hot 而非个性化 recommend
        verify(recommendService).hot(isNull(), eq(10));
        verify(recommendService, never()).recommend(any(), anyInt());
    }

    @Test
    @DisplayName("个性化推荐：带 X-User-Id 时走图谱个性化召回")
    void recommend_withUserId_callsRecommend() {
        Map<String, Object> rec = new HashMap<>();
        rec.put("scriptId", 1L);
        // 网关注入 X-User-Id 后，下游 Long 参数即为用户 id
        when(recommendService.recommend(371L, 10)).thenReturn(List.of(rec));

        R<List<Map<String, Object>>> resp = controller.recommend(371L);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().get(0).get("scriptId")).isEqualTo(1L);
        verify(recommendService).recommend(371L, 10);
        verify(recommendService, never()).hot(any(), anyInt());
    }

    @Test
    @DisplayName("热门榜：不带 type 时透传空类型（服务端返回全类型热门）")
    void hot_withoutType_passesNull() {
        when(recommendService.hot(null, 20)).thenReturn(List.of());

        R<List<Map<String, Object>>> resp = controller.hot(null);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isEmpty();
        verify(recommendService).hot(null, 20);
    }

    @Test
    @DisplayName("热门榜：带 type 时透传到服务层过滤")
    void hot_withType_passesType() {
        when(recommendService.hot("硬核", 20)).thenReturn(List.of());

        R<List<Map<String, Object>>> resp = controller.hot("硬核");

        assertThat(resp.getCode()).isEqualTo(200);
        verify(recommendService).hot("硬核", 20);
    }

    @Test
    @DisplayName("相似剧本：透传 scriptId 并限制 6 条（详情页猜你喜欢）")
    void similar_passesScriptId() {
        Map<String, Object> rec = new HashMap<>();
        rec.put("scriptId", 23L);
        when(recommendService.similar(150L, 6)).thenReturn(List.of(rec));

        R<List<Map<String, Object>>> resp = controller.similar(150L);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        verify(recommendService).similar(150L, 6);
    }

    @Test
    @DisplayName("图谱统计：返回图谱节点快照")
    void stats_returnsGraphSnapshot() {
        Map<String, Object> stats = Map.of("scripts", 5, "users", 2, "tags", 10);
        when(graphSyncService.stats()).thenReturn(stats);

        R<Map<String, Object>> resp = controller.stats();

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).containsEntry("scripts", 5);
    }
}