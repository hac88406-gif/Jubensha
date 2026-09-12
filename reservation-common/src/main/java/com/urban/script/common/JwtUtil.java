package com.urban.script.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * <p>
 * 使用 jjwt <b>0.12.x</b> 版本（注意：与 0.11.x API 不兼容）。
 * <br>关键 API 变化：
 * <ul>
 *   <li>生成：{@code Jwts.builder().signWith(key).compact()}   —— 旧版是 {@code setSigningKey(key).signWith(SignatureAlgorithm.HS256)}
 *   <li>解析：{@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()} —— 旧版是 {@code setSigningKey(key).parseClaimsJws(token).getBody()}
 *   <li>签名：必须是 {@link SecretKey}，推荐 {@link Keys#hmacShaKeyFor(byte[])}
 * </ul>
 * </p>
 * <p>
 * 配置来源（由 {@link com.urban.script.common.config.JwtAutoConfiguration} 从
 * Nacos / 环境变量加载后，通过 {@link #setSecret(String)} / {@link #setExpireMillis(long)} 注入）：
 *   - 密钥：必须外部注入，源码不设默认值（避免仓库公开即泄漏签发能力）
 *   - 过期时间：2 小时
 * </p>
 */
public final class JwtUtil {

    /**
     * JWT 密钥 —— 刻意不设默认值，必须由配置注入。
     * <p>
     * 取值优先级：Nacos urban-shared-config 的 {@code jwt.secret} &gt;
     * 环境变量 {@code JWT_SECRET} &gt; 各服务 application.yml 的 {@code jwt.secret} 默认值，
     * 由 {@link com.urban.script.common.config.JwtAutoConfiguration} 启动时调用
     * {@link #setSecret(String)} 写入。
     * <p>
     * ⚠️ 这里刻意不留硬编码密钥：旧版本把固定密钥写在源码里，仓库一旦公开，
     * 等于把「签发合法 token 的能力」一并公开 —— 任何人用同一密钥即可伪造 token
     * 冒充任意用户甚至管理员。改为配置注入后，密钥不再随代码泄漏。
     * <p>
     * ⚠️ 必须是 volatile：Nacos 配置刷新线程（JwtAutoConfiguration.onApplicationEvent）
     * 调用 {@link #setSecret(String)} 写入，而 Tomcat 请求线程通过 {@link #getKey()} 读取。
     * 没有 volatile 时 Java 内存模型不保证跨线程可见——Nacos 热刷新密钥后，
     * Gateway 解析线程可能长时间读到旧 SECRET，出现"登录成功但所有接口 401
     * （token 签名无效）"的诡异现象，极难复现定位。
     */
    private static volatile String SECRET = "";

    /**
     * 默认过期时间：2 小时
     * <p>volatile 理由同 {@link #SECRET}：Nacos 刷新线程写 / 请求线程读，需保证可见性。</p>
     */
    private static volatile long EXPIRE_MILLIS = 2 * 60 * 60 * 1000L;

    // ====== 配置器（方便测试或多环境切换） ======
    public static void setSecret(String secret) {
        SECRET = secret;
    }
    public static void setExpireMillis(long expireMillis) {
        EXPIRE_MILLIS = expireMillis;
    }

    // ====== 私有：生成 SecretKey ======
    private static SecretKey getKey() {
        String secret = SECRET;
        if (secret == null || secret.isBlank()) {
            // fail-fast：宁可启动即报错，也不要拿空密钥签发"人人都能伪造"的 token
            throw new IllegalStateException(
                    "JWT 密钥未配置：请设置环境变量 JWT_SECRET（长度 >= 32 字节），"
                            + "或在 Nacos urban-shared-config 中配置 jwt.secret");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT
     *
     * @param userId 用户ID（放入 subject，方便 Gateway 提取）
     * @param role   角色（放入自定义 claim，例如 "USER" / "ADMIN" / "AGENT"）
     * @return JWT 字符串（不带 "Bearer " 前缀）
     */
    public static String generateToken(Long userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);

        Date now = new Date();
        Date expire = new Date(now.getTime() + EXPIRE_MILLIS);

        return Jwts.builder()
                .claims(claims)           // 自定义 claims（role 等）
                .subject(String.valueOf(userId))   // subject 存 userId
                .issuedAt(now)            // 签发时间
                .expiration(expire)       // 过期时间
                .signWith(getKey())       // 签名（0.12.x 自动根据 key 长度选算法）
                .compact();
    }

    /**
     * 解析 JWT（返回完整 Claims）
     * <p>
     * 0.12.x 必须先用 {@code verifyWith(key).build()} 构建 parser，
     * 再用 {@code parseSignedClaims(token)} 拿到 Jws 对象，最后 {@code .getPayload()}。
     * </p>
     *
     * @param token JWT 字符串（不带 "Bearer " 前缀）
     * @return Claims（含 subject / role / iat / exp）
     * @throws io.jsonwebtoken.JwtException token 非法、过期或签名不匹配时抛出
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())      // 指定验签密钥
                .build()                   // 构建 Parser
                .parseSignedClaims(token)  // 解析带签名的 JWS（0.12.x 新增）
                .getPayload();             // 取 payload（Claims）
    }

    /**
     * 从 JWT 中提取 userId（subject 字段）
     *
     * @param token JWT 字符串
     * @return userId（Long）；若 token 非法则返回 null
     */
    public static Long getUserId(String token) {
        try {
            Claims claims = parseToken(token);
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            // token 无效 / 过期 / 签名错都走这里
            return null;
        }
    }

    /**
     * 从 JWT 中提取 role（自定义 claim）
     */
    public static String getRole(String token) {
        try {
            Claims claims = parseToken(token);
            Object role = claims.get("role");
            return role == null ? null : role.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验 token 是否有效（签名正确 & 未过期）
     */
    public static boolean validate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            parseToken(token);   // 能正常解析 = 有效
            return true;
        } catch (SignatureException se) {
            return false;        // 签名错误
        } catch (Exception e) {
            return false;        // 过期 / 格式错
        }
    }

    /** 工具类不允许实例化 */
    private JwtUtil() {}
}
