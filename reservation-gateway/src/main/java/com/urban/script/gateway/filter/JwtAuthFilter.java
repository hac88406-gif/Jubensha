package com.urban.script.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.script.common.JwtUtil;
import com.urban.script.common.R;
import com.urban.script.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权全局过滤器
 * <p>
 * 设计策略：
 *   <ol>
 *     <li><b>⓪ 安全前置</b>：无条件剥离入站请求中客户端自带的 {@code X-User-Id} /
 *         {@code X-User-Role}，保证身份头只可能由本过滤器注入。若不清除，
 *         下游 {@code RoleAspect} 无法分辨该头是「网关注入」还是「请求方伪造」，
 *         攻击者无需登录、自带 {@code X-User-Role: ROLE_SHOP_OWNER} 即可越权</li>
 *     <li>白名单路径（注册 / 登录 / agent 渠道 / Feign 内部接口）—— 直接放行，不检查 Header</li>
 *     <li>其他路径：
 *       <ul>
 *         <li><b>有</b> Authorization Bearer token —— 校验签名 + 过期时间，解析 userId / role，
 *             注入下游 Header {@code X-User-Id} / {@code X-User-Role}，然后转发</li>
 *         <li><b>无</b> Authorization Header —— <b>直接透传</b>，让下游 {@code @RequireRole} 切面
 *             决定返回什么（公开查询接口：下游没 @RequireRole → 正常返回；管理写操作：
 *             下游有 @RequireRole + 取不到 X-User-Id → RoleAspect 返回 401）</li>
 *       </ul>
 *     </li>
 *   </ol>
 * <p>
 * 这样玩家端公开查询接口（剧本列表、场次详情）无需在白名单里逐个列举，自动放行。
 * 而管理端写操作（创建场次、关闭场次）由下游切面统一拦截，职责更清晰。
 * </p>
 *
 * @author urban-script-reservation
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 白名单（完全跳过 JWT 处理，连 Header 都不看）
     * <p>
     * 包含：注册 / 登录 / agent 健康检查 / agent & 各微服务 Feign 内部接口前缀。
     * <p>
     * 🔒 安全红线：绝不把整条 {@code /api/agent/} 放进白名单，否则玩家端 AI 对话
     * 接口 /api/agent/chat 会被彻底放行（绕过 JWT + 下游 agent-gateway 又没有 RoleAspect），
     * 任何人不用登录就能调 AI 对话。只放行 /agent/health 和 /agent/internal/**。
     * </p>
     */
    /**
     * 网关注入给下游的身份头 —— 下游 `RoleAspect` 据此鉴权。
     * <p>
     * 🔒 安全红线：这两个头只允许由本过滤器在验签通过后写入；
     * 客户端自带的同名头必须在入口处剥离（见 {@link #filter} 的 ⓪ 步），
     * 否则可被伪造用于越权。
     */
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    private static final List<String> WHITE_LIST = List.of(
            "/api/user/register",
            "/api/user/login",
            "/api/agent/health",            // agent-gateway 健康检查（无状态，公开）
            "/api/agent/internal/",         // agent-gateway 内部 Feign 接口（有 X-Internal-Api-Key 兜底）
            "/api/session/internal/",       // shop-service SessionController 内部接口
            "/api/script/internal/",        // shop-service ScriptController 内部接口
            "/api/shop/internal/",          // shop-service 其他内部接口预留
            "/api/order/internal/",         // order-service → Feign 内部接口
            "/api/order/pay/notify",        // 模拟支付平台回调（服务端对服务端，靠 HMAC 验签，与真实微信/支付宝一致）
            "/api/recommend/internal/"      // recommend-service 内部同步接口（剧本/订单写 Neo4j）
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ========== ⓪ 安全前置：无条件剥离客户端可能伪造的身份头 ==========
        // 身份头（X-User-Id / X-User-Role）只允许由本过滤器在验签通过后注入，
        // 客户端自带的同名头必须清除 —— 否则下游 RoleAspect 无法分辨该头
        // 是「网关注入」还是「请求方伪造」，攻击者无需登录、只要自带
        // "X-User-Role: ROLE_SHOP_OWNER" 即可越权。
        // ⚠️ 必须在所有分支之前执行，包括白名单分支与无 token 的透传分支。
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USER_ROLE);
                })
                .build();

        // ========== ① 白名单：直接放行（身份头已剥离）==========
        if (isWhiteListed(path)) {
            log.debug("[JwtAuth] 白名单放行: {}", path);
            return chain.filter(exchange.mutate().request(sanitized).build());
        }

        // ========== ② 无 Authorization Header → 透传（让下游 @RequireRole 决定）==========
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            log.debug("[JwtAuth] 无 token, 透传给下游: {}", path);
            return chain.filter(exchange.mutate().request(sanitized).build());
        }

        // ========== ③ 有 token → 校验 + 注入 Header ==========
        String token = auth.substring(7);   // 去掉 "Bearer "
        if (!JwtUtil.validate(token)) {
            log.warn("[JwtAuth] token 校验失败, path={}", path);
            return writeUnauthorized(exchange, "token 已过期或签名无效");
        }

        Long userId = JwtUtil.getUserId(token);
        String role = JwtUtil.getRole(token);

        // 在已剥离伪造头的请求上注入，确保身份头有且只有一个来源（即本过滤器）
        ServerHttpRequest authenticated = sanitized.mutate()
                .header(HEADER_USER_ID, userId == null ? "" : String.valueOf(userId))
                .header(HEADER_USER_ROLE, role == null ? "" : role)
                .build();

        log.debug("[JwtAuth] 校验通过, userId={}, role={}, path={}", userId, role, path);
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    /** 判断路径是否在白名单 */
    private boolean isWhiteListed(String path) {
        for (String white : WHITE_LIST) {
            if (path.startsWith(white)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写 401 JSON 响应（WebFlux Gateway 专用写法）
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);   // HTTP 200，业务码 401 由前端判断
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        R<Void> body = R.fail(ResultCode.UNAUTHORIZED.getCode(), msg);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":401,\"message\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 过滤器执行顺序：数值越小越早执行。
     * -100 让它在 Sentinel / LoadBalancer 等默认过滤器之前跑，尽早失败。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
