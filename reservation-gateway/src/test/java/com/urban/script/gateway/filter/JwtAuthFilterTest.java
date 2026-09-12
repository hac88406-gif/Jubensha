package com.urban.script.gateway.filter;

import com.urban.script.common.JwtUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthFilter 安全测试（纯单元测试，不依赖 Nacos / MySQL / Redis 等中间件）
 *
 * <p>核心断言：客户端自带的身份头（{@code X-User-Id} / {@code X-User-Role}）
 * <b>必须被网关剥离</b>。
 *
 * <p>漏洞背景：下游 {@code RoleAspect} 无条件信任这两个头，无法分辨其来源。
 * 若网关不清理入站同名头，攻击者无需登录、只要自带
 * {@code X-User-Role: ROLE_SHOP_OWNER} 即可越权（冒充店主）。
 */
class JwtAuthFilterTest {

    private final JwtAuthFilter filter = new JwtAuthFilter();

    /**
     * JwtUtil 的密钥已改为「配置注入、缺省为空」，因此测试中需先注入一个测试密钥，
     * 否则 {@code getKey()} 会 fail-fast 抛异常。
     */
    @BeforeAll
    static void initSecret() {
        JwtUtil.setSecret("unit-test-secret-at-least-32-bytes-long!!");
    }

    /** 执行过滤器，返回实际转发给下游的 exchange（未转发则返回 null） */
    private ServerWebExchange captureForwarded(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return forwarded.get();
    }

    @Test
    @DisplayName("① 无 token 时，客户端伪造的身份头必须被剥离（防越权）")
    void shouldStripForgedHeadersWhenNoToken() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/shop/shop")
                .header("X-User-Id", "1")
                .header("X-User-Role", "ROLE_SHOP_OWNER")
                .build();

        ServerWebExchange forwarded = captureForwarded(MockServerWebExchange.from(request));

        assertThat(forwarded).as("请求应被透传到下游").isNotNull();
        HttpHeaders headers = forwarded.getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).as("伪造的 X-User-Id 必须被剥离").isNull();
        assertThat(headers.getFirst("X-User-Role")).as("伪造的 X-User-Role 必须被剥离").isNull();
    }

    @Test
    @DisplayName("② 白名单路径同样必须剥离伪造身份头")
    void shouldStripForgedHeadersOnWhiteListPath() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/user/login")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_SHOP_OWNER")
                .build();

        ServerWebExchange forwarded = captureForwarded(MockServerWebExchange.from(request));

        assertThat(forwarded).as("白名单请求应被放行").isNotNull();
        HttpHeaders headers = forwarded.getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isNull();
        assertThat(headers.getFirst("X-User-Role")).isNull();
    }

    @Test
    @DisplayName("③ 携带合法 token 时，以 token 为准注入身份（伪造值被覆盖）")
    void shouldInjectRealIdentityOverridingForged() {
        String token = JwtUtil.generateToken(42L, "ROLE_PLAYER");

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/order/user/list")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "1")                   // 伪造：冒充他人
                .header("X-User-Role", "ROLE_SHOP_OWNER")   // 伪造：冒充店主
                .build();

        ServerWebExchange forwarded = captureForwarded(MockServerWebExchange.from(request));

        assertThat(forwarded).isNotNull();
        HttpHeaders headers = forwarded.getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).as("身份应以 token 为准").isEqualTo("42");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo("ROLE_PLAYER");
    }

    @Test
    @DisplayName("④ token 非法时返回 401，且不转发到下游")
    void shouldRejectInvalidToken() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/order/user/list")
                .header("Authorization", "Bearer invalid.token.value")
                .build();

        ServerWebExchange forwarded = captureForwarded(MockServerWebExchange.from(request));

        assertThat(forwarded).as("非法 token 不应被转发到下游").isNull();
    }
}
