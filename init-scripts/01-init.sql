-- ============================================================
-- urban_script_reservation  初始化 DDL
-- 执行方式:
--   mysql -uroot -p < init-scripts/01-init.sql
--   或 Navicat / DBeaver 里直接执行
-- ============================================================

CREATE DATABASE IF NOT EXISTS urban_script_reservation
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE urban_script_reservation;

-- 用户信息表
CREATE TABLE IF NOT EXISTS user_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL COMMENT '用户名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    role        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色: 0-玩家 1-DM 2-店长 3-管理员',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用 1-正常',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

-- 店铺/剧本店表
CREATE TABLE IF NOT EXISTS shop_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '店铺ID',
    name        VARCHAR(100) NOT NULL COMMENT '店铺名称',
    address     VARCHAR(255) DEFAULT NULL COMMENT '店铺地址',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    owner_id    BIGINT       DEFAULT NULL COMMENT '店长user_id',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-关闭 1-营业中',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺信息表';

-- 剧本表
CREATE TABLE IF NOT EXISTS script_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '剧本ID',
    shop_id     BIGINT       NOT NULL COMMENT '所属店铺',
    name        VARCHAR(100) NOT NULL COMMENT '剧本名称',
    author      VARCHAR(50)  DEFAULT NULL COMMENT '作者',
    script_type VARCHAR(20)  DEFAULT NULL COMMENT '类型: 硬核/情感/欢乐/机制',
    player_min  INT          DEFAULT 4 COMMENT '最少人数',
    player_max  INT          DEFAULT 8 COMMENT '最多人数',
    duration    INT          DEFAULT 120 COMMENT '时长(分钟)',
    price       DECIMAL(10,2) DEFAULT 0.00 COMMENT '单价',
    stock       INT          DEFAULT 0 COMMENT '可预约场次库存',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '上架状态: 0-下架 1-上架',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧本信息表';

-- 订单表
CREATE TABLE IF NOT EXISTS order_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    order_no    VARCHAR(64)  NOT NULL COMMENT '订单号(业务唯一)',
    user_id     BIGINT       NOT NULL COMMENT '玩家user_id',
    shop_id     BIGINT       NOT NULL COMMENT '店铺ID',
    script_id   BIGINT       NOT NULL COMMENT '剧本ID',
    player_cnt  INT          NOT NULL COMMENT '参与人数',
    play_time   DATETIME     NOT NULL COMMENT '开局时间',
    amount      DECIMAL(10,2) DEFAULT 0.00 COMMENT '订单金额',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待支付 1-已支付 2-已取消 3-已完成',
    pay_time    DATETIME     DEFAULT NULL COMMENT '支付时间',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id),
    KEY idx_shop_script (shop_id, script_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单信息表';

-- ============================================================
-- 初始化种子数据 (可选, 注释掉也行)
-- 默认密码 123456 对应的 BCrypt: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- INSERT IGNORE：依赖 uk_username 唯一键，已存在的账号直接跳过，
-- 保证整个脚本可重复执行（否则第二次跑会 ERROR 1062 中断，卡住后面所有建表语句）
-- ============================================================
INSERT IGNORE INTO user_info (username, password, role, phone) VALUES
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 3, '13800000000'),
('dm001', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 1, '13800000001');

-- ============================================================
-- 场次信息表（剧本杀开演的具体场次）
-- 核心：抢位的载体，Redis+Lua 原子扣减 capacity vs booked
-- ============================================================
CREATE TABLE IF NOT EXISTS session_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '场次ID',
    script_id   BIGINT       NOT NULL COMMENT '剧本ID（关联script_info）',
    shop_id     BIGINT       NOT NULL COMMENT '店铺ID（关联shop_info）',
    dm_id       BIGINT       DEFAULT NULL COMMENT 'DM用户ID（关联user_info，可空=未分配DM）',
    session_date DATE        NOT NULL COMMENT '场次日期（YYYY-MM-DD）',
    start_time  TIME         NOT NULL COMMENT '开始时间（HH:mm:ss）',
    end_time    TIME         NOT NULL COMMENT '结束时间（HH:mm:ss）',
    capacity    INT          NOT NULL DEFAULT 6 COMMENT '总名额',
    booked      INT          NOT NULL DEFAULT 0 COMMENT '已预约人数（MySQL持久化，和Redis余位最终一致）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-关闭 1-开放',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_script_date_time (script_id, session_date, start_time),
    KEY idx_dm_time (dm_id, session_date),
    KEY idx_shop_date (shop_id, session_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场次信息表';

-- ============================================================
-- 订单表补 session_id 外键 + 取消原因字段
-- ============================================================
-- 注意：ALTER TABLE 用 IF 条件，避免重复执行报错
-- order_info.session_id（关联场次）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = 'urban_script_reservation'
                     AND TABLE_NAME = 'order_info'
                     AND COLUMN_NAME = 'session_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE order_info ADD COLUMN session_id BIGINT NOT NULL COMMENT ''场次ID（关联session_info）'' AFTER script_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- order_info.cancel_reason（取消原因枚举）
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = 'urban_script_reservation'
                     AND TABLE_NAME = 'order_info'
                     AND COLUMN_NAME = 'cancel_reason');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE order_info ADD COLUMN cancel_reason VARCHAR(20) DEFAULT NULL COMMENT ''取消原因：USER_CANCEL/TIMEOUT/SESSION_CLOSED'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- order_info.idx_session 索引
SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                   WHERE TABLE_SCHEMA = 'urban_script_reservation'
                     AND TABLE_NAME = 'order_info'
                     AND INDEX_NAME = 'idx_session');
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE order_info ADD KEY idx_session (session_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 订单表补 pay_method 支付方式列
-- Bug 修复：OrderInfo 实体声明了 payMethod 字段且 buildOrder 显式 setPayMethod(0)，
-- 但初始 CREATE TABLE 缺少该列，导致 MyBatis-Plus INSERT 时拼入未知列直接 SQL 异常，
-- 整个预约下单主链路阻断（P0）。
-- ============================================================
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = 'urban_script_reservation'
                     AND TABLE_NAME = 'order_info'
                     AND COLUMN_NAME = 'pay_method');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE order_info ADD COLUMN pay_method TINYINT NOT NULL DEFAULT 0 COMMENT ''支付方式：0-未支付 1-微信 2-支付宝 3-线下'' AFTER amount',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 支付流水表 payment_transaction
-- 支付闭环：prepay 生成流水(status=0) → notify 回调验签后置为成功(status=1)
-- payment_no 为业务幂等键（雪花生成），回调重复投递靠唯一键 + 状态机原子更新兜底
-- ============================================================
CREATE TABLE IF NOT EXISTS payment_transaction (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    payment_no   VARCHAR(64)   NOT NULL COMMENT '支付流水号（业务唯一，幂等键）',
    order_no     VARCHAR(64)   NOT NULL COMMENT '关联订单号',
    user_id      BIGINT        NOT NULL COMMENT '付款用户ID',
    amount       DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '支付金额',
    pay_method   TINYINT       NOT NULL DEFAULT 1 COMMENT '支付方式：1-微信 2-支付宝 3-线下',
    status       TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待支付 1-支付成功 2-支付失败',
    channel      VARCHAR(32)   DEFAULT 'SIM' COMMENT '支付渠道（演示用 SIM=模拟渠道）',
    pay_time     DATETIME      DEFAULT NULL COMMENT '支付成功时间',
    notify_time  DATETIME      DEFAULT NULL COMMENT '回调到达时间',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_no (payment_no),
    KEY idx_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水表';
