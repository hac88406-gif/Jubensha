package com.urban.script.common;

import java.net.InetAddress;

/**
 * 雪花算法 ID 生成器（单例）
 *
 * <p>Twitter Snowflake 算法标准实现：
 * <pre>
 *  0 | 41 位时间戳 | 10 位机器 ID | 12 位序列号
 *  符号位(0) | 毫秒级时间戳(相对于 epoch) | 5位 datacenterId + 5位 workerId | 同毫秒内递增
 * </pre>
 *
 * <p>本项目约定：10 位机器 ID 全部作为 workerId 使用（datacenterId 固定为 0）。
 * workerId 解析优先级（多实例部署防 ID 冲突）：
 * <ol>
 *   <li>Spring 配置 {@code snowflake.worker-id}（application.yml / Nacos），
 *       由 {@code SnowflakeAutoConfiguration} 在启动时调用
 *       {@link #overrideWorkerId(long)} 注入</li>
 *   <li>JVM 系统属性 {@code -Dsnowflake.worker-id=2}</li>
 *   <li>环境变量 {@code SNOWFLAKE_WORKER_ID}</li>
 *   <li>兜底：本机 IP hash 取模（不同机器大概率错开；同机多实例需显式配置）</li>
 * </ol>
 *
 * <p>时钟回拨处理：检测到负数时 sleep 1ms 重试，超过 5ms 抛出 {@link RuntimeException}。
 *
 * @author urban-script-reservation
 */
public class SnowflakeIdGenerator {

    // ========================================================================
    // 基础常量
    // ========================================================================

    /**
     * 纪元起点：2024-01-01 00:00:00 UTC（毫秒）
     */
    private static final long EPOCH = 1704067200000L;

    /** 机器 ID 占用位数（datacenterId 5 + workerId 5 = 10） */
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;

    /** 序列号占用位数 */
    private static final long SEQUENCE_BITS = 12L;

    /** 机器 ID 最大值（31） */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    /** 数据中心 ID 最大值（31） */
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    /** 序列号掩码：4095 */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    // ========================================================================
    // 位移量
    // ========================================================================

    /** 时间戳左移 22 位（10+12） */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    /** 数据中心 ID 左移 17 位（5+12） */
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 机器 ID 左移 12 位 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    // ========================================================================
    // 运行时状态
    // ========================================================================

    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * Spring / 外部显式注入的 workerId（由 SnowflakeAutoConfiguration 启动时写入），
     * 优先级最高。-1 表示未设置。
     * <p>volatile：Spring 启动线程写 / 业务线程读单例时需保证可见性。</p>
     */
    private static volatile long overriddenWorkerId = -1L;

    /**
     * 供 Spring 配置（application.yml / Nacos 的 snowflake.worker-id）注入 workerId。
     * <p>必须在单例首次使用（{@link #getInstance()}）之前调用才生效——
     * SnowflakeAutoConfiguration 的 @PostConstruct 早于任何业务请求，时序天然满足。</p>
     *
     * @param workerId 0~31
     */
    public static void overrideWorkerId(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "snowflake.worker-id must be between 0 and " + MAX_WORKER_ID + ", actual: " + workerId);
        }
        overriddenWorkerId = workerId;
    }

    /**
     * 包私有构造器，禁止外部直接 new。
     * workerId 解析优先级：Spring 注入 > JVM 系统属性 > 环境变量 > IP hash 兜底。
     */
    SnowflakeIdGenerator() {
        this.workerId = resolveWorkerId();
        this.datacenterId = 0L; // 本项目不区分数据中心，固定为 0
    }

    /**
     * 按优先级解析 workerId，保证多实例部署时不撞 ID。
     */
    private static long resolveWorkerId() {
        // 1. Spring 配置注入（最高优先级）
        if (overriddenWorkerId >= 0) {
            return overriddenWorkerId;
        }

        // 2. JVM 系统属性 -Dsnowflake.worker-id=2
        String sysVal = System.getProperty("snowflake.worker-id");
        Long parsed = parseLongSafely(sysVal);
        if (parsed != null) {
            return checkRange(parsed, "system property snowflake.worker-id");
        }

        // 3. 环境变量 SNOWFLAKE_WORKER_ID
        parsed = parseLongSafely(System.getenv("SNOWFLAKE_WORKER_ID"));
        if (parsed != null) {
            return checkRange(parsed, "env SNOWFLAKE_WORKER_ID");
        }

        // 4. 兜底：本机 IP hash 取模——不同机器大概率错开，避免所有实例都默认 1 撞 ID
        return ipHashWorkerId();
    }

    /** 解析 long，失败返回 null（空白也返回 null） */
    private static Long parseLongSafely(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException ignored) {
            return null;  // 解析失败走下一优先级
        }
    }

    /** 范围校验（0~31），非法直接抛异常让启动期暴露配置错误 */
    private static long checkRange(long wId, String source) {
        if (wId < 0 || wId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    source + " must be between 0 and " + MAX_WORKER_ID + ", actual: " + wId);
        }
        return wId;
    }

    /**
     * 本机 IP hash 兜底：取本机 IP 的 hashcode 模 32。
     * 拿不到 IP 时返回 1（与历史默认值一致，单实例场景无影响）。
     */
    private static long ipHashWorkerId() {
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();
            return Math.abs(ip.hashCode()) % (MAX_WORKER_ID + 1);
        } catch (Exception e) {
            return 1L;
        }
    }

    /**
     * 静态内部类实现的线程安全懒加载单例。
     */
    private static class Holder {
        private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator();
    }

    /**
     * 获取单例实例。
     *
     * @return 雪花 ID 生成器
     */
    public static SnowflakeIdGenerator getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 生成下一个全局唯一 ID（synchronized 保证线程安全）。
     *
     * @return 64 位唯一 ID
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        // 1. 时钟回拨检测
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > 5) {
                throw new RuntimeException(
                        "Clock moved backwards, refusing to generate id for " + offset + " ms");
            }
            // sleep 等待 1ms 后重试（最多循环 5 次）
            try {
                Thread.sleep(offset + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for clock to catch up", e);
            }
            timestamp = currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new RuntimeException("Clock moved backwards after retry, still negative");
            }
        }

        // 2. 同毫秒内递增序列号
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            // 序列号溢出（达到 4096），等到下一毫秒
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒，序列号重置（可以加随机数避免趋同，这里保持经典实现）
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 3. 按位拼接
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 等待直到下一个毫秒。
     */
    private long tilNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 获取当前时间戳（便于单元测试 mock）。
     */
    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
