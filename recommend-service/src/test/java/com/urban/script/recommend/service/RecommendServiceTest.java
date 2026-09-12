package com.urban.script.recommend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RecommendService 单元测试
 * <p>
 * 覆盖「前端为你推荐 / 猜你喜欢」对接的核心逻辑：
 *   1) 推荐缓存命中 → 直接返回，不查 Neo4j
 *   2) 无缓存 → 图谱内容召回 + 协同过滤合并排序
 *   3) 召回不足 → 热门兜底补齐
 *   4) Neo4j 异常 → 降级热门，不抛错
 *   5) 热门 / 相似 查询及类型绑定
 * 全部使用 Mockito mock（不启动 Spring 上下文，不连真实 Neo4j/Redis）。
 */
class RecommendServiceTest {

    private Neo4jClient neo4jClient;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RecommendService service;

    /** 统一 mock 的 Neo4j 查询链头（query → bind → to），对参数绑定做 verify 用 */
    private Neo4jClient.UnboundRunnableSpec runnable;
    /** bind(...) 返回的 OngoingBindSpec：命名参数绑定 to(name) 的 verify 目标 */
    private Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> ongoingBind;
    /** 每个用例可独立替换 fetch() 的返回 */
    private Neo4jClient.RecordFetchSpec<Map<String, Object>> fetchable;

    private static final long USER_ID = 371L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        neo4jClient = mock(Neo4jClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 统一 mock Neo4jClient 查询链：query(...) → bind(...) → to(...) → fetch() → all()/one()
        // 说明：bind(T) 返回 OngoingBindSpec，参数绑定名在 to(String) 上完成
        runnable = mock(Neo4jClient.UnboundRunnableSpec.class);
        ongoingBind = mock(Neo4jClient.OngoingBindSpec.class);
        Neo4jClient.RecordFetchSpec<Map<String, Object>> deepFetchable = mock(Neo4jClient.RecordFetchSpec.class);
        when(neo4jClient.query(anyString())).thenReturn(runnable);
        doReturn(ongoingBind).when(runnable).bind(any());
        doReturn(runnable).when(ongoingBind).to(anyString());
        when(runnable.fetch()).thenReturn(deepFetchable);

        service = new RecommendService(neo4jClient, redisTemplate);

        fetchable = deepFetchable;
    }

    /** 构造一条图查询返回记录 */
    private Map<String, Object> record(long scriptId, String name, String type, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("scriptId", scriptId);
        m.put("name", name);
        m.put("image", "https://img.example.com/" + scriptId + ".jpg");
        m.put("mark", 9.5);
        m.put("scriptType", type);
        m.put("score", score);
        return m;
    }

    // ------------------------------------------------------------------
    // 1) 缓存命中
    // ------------------------------------------------------------------
    @Test
    @DisplayName("缓存命中时直接返回缓存结果，不查询 Neo4j")
    void recommend_hitCache_returnsCached() {
        String cached = "[{\"scriptId\":150,\"name\":\"来信\",\"image\":\"https://x.jpg\",\"mark\":9.5,\"scriptType\":\"情感\"}]";
        when(valueOps.get("rec:" + USER_ID)).thenReturn(cached);

        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("scriptId")).isEqualTo(150L);
        assertThat(result.get(0).get("name")).isEqualTo("来信");
        // 缓存命中不得触发任何查询
        verify(neo4jClient, never()).query(anyString());
        // 已命中也无需回写缓存
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("缓存内容解析：mark 为 null 时被跳过，scriptId 归一为 Long")
    void recommend_parseCached_handlesNullMark() {
        String cached = "[{\"scriptId\":5,\"name\":\"Smoke Script\",\"image\":null,\"mark\":null,\"scriptType\":\"hardcore\"}]";
        when(valueOps.get("rec:" + USER_ID)).thenReturn(cached);

        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("scriptId")).isEqualTo(5L);
        assertThat(item).doesNotContainKey("mark");
    }

    // ------------------------------------------------------------------
    // 2) 无缓存：内容召回 + 协同过滤合并排序
    // ------------------------------------------------------------------
    @Test
    @DisplayName("无缓存时走图谱召回，按加权分数排序并写回缓存")
    void recommend_noCache_recallsAndSortThenWritesCache() {
        // 三级 all() 调用顺序：content(#1) / cf(#2) / hot 兜底(#3)
        // 12 条内容召回 ≥ limit=10，不触发热门兜底，验证走 query 两次即可
        List<Map<String, Object>> content = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            content.add(record(i, "剧本" + i, "硬核", 12 - i));
        }
        when(valueOps.get("rec:" + USER_ID)).thenReturn(null);
        when(fetchable.all()).thenReturn(content, Collections.emptyList(), Collections.emptyList());

        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        assertThat(result).hasSize(10);
        // 内容召回按 score 降序：score = count * 0.6
        assertThat(result.get(0).get("scriptId")).isEqualTo(1L);
        // 合并结果写缓存
        verify(valueOps).set(eq("rec:" + USER_ID), anyString(), eq(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("内容与协同召回不足时，用热门补齐到 limit")
    void recommend_insufficientRecall_backfilledByHot() {
        // content 1 条 + cf 0 条 → result=1 < limit=10 → 触发热门补齐
        List<Map<String, Object>> content = List.of(record(1, "唯一召回", "欢乐", 5));
        List<Map<String, Object>> hot = new ArrayList<>();
        for (int i = 100; i < 110; i++) {
            hot.add(record(i, "热门" + i, "机制", 10));
        }
        when(valueOps.get("rec:" + USER_ID)).thenReturn(null);
        when(fetchable.all())
                .thenReturn(content)          // #1 content
                .thenReturn(Collections.emptyList()) // #2 cf
                .thenReturn(hot);             // #3 hot 兜底
        when(fetchable.one()).thenReturn(Optional.of(Map.of("ids", Collections.emptyList())));

        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        assertThat(result).hasSize(10);
        // 内容召回的剧本在前，热门补齐在后（1 + 9 条热门）
        assertThat(result.get(0).get("scriptId")).isEqualTo(1L);
        assertThat(result.get(1).get("scriptId")).isEqualTo(100L);
        // 确认共 4 次图查询：content / cf / 已玩剧本集合 / hot 兜底
        verify(neo4jClient, times(4)).query(anyString());
    }

    @Test
    @DisplayName("已玩过的剧本在热门补齐时被排除（避免重复推荐）")
    void recommend_backfill_excludesPlayedScripts() {
        List<Map<String, Object>> content = List.of(record(1, "唯一召回", "欢乐", 5));
        List<Map<String, Object>> hot = List.of(
                record(100, "已玩过", "机制", 10),   // 用户已玩 → 应跳过
                record(101, "未玩过", "机制", 9));   // 未玩 → 应补入
        when(valueOps.get("rec:" + USER_ID)).thenReturn(null);
        when(fetchable.all())
                .thenReturn(content)
                .thenReturn(Collections.emptyList())
                .thenReturn(hot);
        // 用户已玩剧本 id 集合 = {100}
        when(fetchable.one()).thenReturn(Optional.of(Map.of("ids", List.of(100L))));

        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        List<Object> ids = result.stream().map(r -> r.get("scriptId")).toList();
        assertThat(ids).contains(101L).doesNotContain(100L);
    }

    // ------------------------------------------------------------------
    // 3) Neo4j 异常 → 降级热门
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Neo4j 不可用时不抛错，降级返回热门榜")
    void recommend_neo4jDown_fallsBackToHot() {
        // 第 1 次 query（content）抛异常触发降级；
        // 之后（catch 内的 hot 路径）正常返回 deep stub，保证降级结果不 NPE
        AtomicInteger qCalls = new AtomicInteger();
        when(neo4jClient.query(anyString())).thenAnswer(inv -> {
            if (qCalls.incrementAndGet() <= 1) {
                throw new RuntimeException("Neo4j 连接超时");
            }
            return mock(Neo4jClient.UnboundRunnableSpec.class, RETURNS_DEEP_STUBS);
        });
        when(valueOps.get("rec:" + USER_ID)).thenReturn(null);
        // 降级路径 hot(null, 10)：deep stub 下 all() 默认返回空列表 → 不会 NPE
        List<Map<String, Object>> result = service.recommend(USER_ID, 10);

        // 降级成功：返回（空）热门，无异常
        assertThat(result).isNotNull();
    }

    // ------------------------------------------------------------------
    // 4) 热门榜单
    // ------------------------------------------------------------------
    @Test
    @DisplayName("热门榜单不带类型：返回全部热门并按 score 降序")
    void hot_withoutType_returnsAllSorted() {
        List<Map<String, Object>> hot = List.of(
                record(1, "甲", "硬核", 8),
                record(2, "乙", "情感", 3));
        when(fetchable.all()).thenReturn(hot);

        List<Map<String, Object>> result = service.hot(null, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("scriptId")).isEqualTo(1L);
        // 无类型时不应绑定 type 参数（bind 单参 + to 命名）
        verify(runnable).bind(20);
        verify(ongoingBind).to("lim");
        verify(ongoingBind, never()).to("type");
    }

    @Test
    @DisplayName("热门榜单带类型：绑定 type 参数过滤")
    void hot_withType_bindsType() {
        when(fetchable.all()).thenReturn(List.of(record(1, "甲", "硬核", 8)));

        service.hot("硬核", 20);

        // bind(type) → to("type")，bind(limit) → to("lim")
        verify(runnable).bind("硬核");
        verify(ongoingBind).to("type");
        verify(runnable).bind(20);
        verify(ongoingBind).to("lim");
    }

    // ------------------------------------------------------------------
    // 5) 相似剧本（详情页「猜你喜欢」）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("相似剧本按同标签/同作者召回，绑定 scriptId 与 limit")
    void similar_returnsRelatedScripts() {
        when(fetchable.all()).thenReturn(List.of(
                record(23, "非正常女子图鉴", "情感", 2),
                record(156, "爱暮未停", "情感", 1)));

        List<Map<String, Object>> result = service.similar(150L, 6);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("scriptId")).isEqualTo(23L);
        verify(runnable).bind(150L);
        verify(ongoingBind).to("sid");
        verify(runnable).bind(6);
        verify(ongoingBind).to("lim");
    }

    @Test
    @DisplayName("相似剧本无关联时返回空列表")
    void similar_noRelation_returnsEmpty() {
        // 默认 all() 返回空（deep mock 未 stub 时为空集合）
        Neo4jClient.UnboundRunnableSpec empty = mock(Neo4jClient.UnboundRunnableSpec.class, RETURNS_DEEP_STUBS);
        when(neo4jClient.query(anyString())).thenReturn(empty);

        List<Map<String, Object>> result = service.similar(5L, 6);

        assertThat(result).isEmpty();
    }
}