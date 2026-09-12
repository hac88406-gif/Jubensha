-- MySQL dump 10.13  Distrib 8.0.35, for Win64 (x86_64)
--
-- Host: localhost    Database: urban_script_reservation
-- ------------------------------------------------------
-- Server version	8.0.35

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `order_info`
--

DROP TABLE IF EXISTS `order_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no` varchar(64) NOT NULL COMMENT '订单号(业务唯一)',
  `user_id` bigint NOT NULL COMMENT '玩家user_id',
  `shop_id` bigint NOT NULL COMMENT '店铺ID',
  `script_id` bigint NOT NULL COMMENT '剧本ID',
  `session_id` bigint NOT NULL COMMENT '场次ID（关联session_info）',
  `player_cnt` int NOT NULL COMMENT '参与人数',
  `play_time` datetime NOT NULL COMMENT '开局时间',
  `amount` decimal(10,2) DEFAULT '0.00' COMMENT '订单金额',
  `pay_method` tinyint NOT NULL DEFAULT '0' COMMENT '支付方式：0-未支付 1-微信 2-支付宝 3-线下',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态: 0-待支付 1-已支付 2-已取消 3-已完成',
  `cancel_reason` varchar(20) DEFAULT NULL COMMENT '取消原因：USER_CANCEL/TIMEOUT/SESSION_CLOSED',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user` (`user_id`),
  KEY `idx_shop_script` (`shop_id`,`script_id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `order_info`
--

LOCK TABLES `order_info` WRITE;
/*!40000 ALTER TABLE `order_info` DISABLE KEYS */;
/*!40000 ALTER TABLE `order_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `script_info`
--

DROP TABLE IF EXISTS `script_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `script_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '剧本ID',
  `shop_id` bigint NOT NULL COMMENT '所属店铺',
  `name` varchar(100) NOT NULL COMMENT '剧本名称',
  `author` varchar(50) DEFAULT NULL COMMENT '作者',
  `script_type` varchar(20) DEFAULT NULL COMMENT '类型: 硬核/情感/欢乐/机制',
  `player_min` int DEFAULT '4' COMMENT '最少人数',
  `player_max` int DEFAULT '8' COMMENT '最多人数',
  `duration` int DEFAULT '120' COMMENT '时长(分钟)',
  `price` decimal(10,2) DEFAULT '0.00' COMMENT '单价',
  `stock` int DEFAULT '0' COMMENT '可预约场次库存',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '上架状态: 0-下架 1-上架',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop` (`shop_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='剧本信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `script_info`
--

LOCK TABLES `script_info` WRITE;
/*!40000 ALTER TABLE `script_info` DISABLE KEYS */;
INSERT INTO `script_info` VALUES (1,1,'测试剧本','test','机制',4,8,120,88.00,0,1,'2026-09-06 15:39:18','2026-09-06 15:39:18');
/*!40000 ALTER TABLE `script_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `session_info`
--

DROP TABLE IF EXISTS `session_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '场次ID',
  `script_id` bigint NOT NULL COMMENT '剧本ID（关联script_info）',
  `shop_id` bigint NOT NULL COMMENT '店铺ID（关联shop_info）',
  `dm_id` bigint DEFAULT NULL COMMENT 'DM用户ID（关联user_info，可空=未分配DM）',
  `session_date` date NOT NULL COMMENT '场次日期（YYYY-MM-DD）',
  `start_time` time NOT NULL COMMENT '开始时间（HH:mm:ss）',
  `end_time` time NOT NULL COMMENT '结束时间（HH:mm:ss）',
  `capacity` int NOT NULL DEFAULT '6' COMMENT '总名额',
  `booked` int NOT NULL DEFAULT '0' COMMENT '已预约人数（MySQL持久化，和Redis余位最终一致）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-关闭 1-开放',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_script_date_time` (`script_id`,`session_date`,`start_time`),
  KEY `idx_dm_time` (`dm_id`,`session_date`),
  KEY `idx_shop_date` (`shop_id`,`session_date`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `session_info`
--

LOCK TABLES `session_info` WRITE;
/*!40000 ALTER TABLE `session_info` DISABLE KEYS */;
INSERT INTO `session_info` VALUES (1,1,1,NULL,'2026-09-07','14:00:00','16:00:00',6,0,1,'2026-09-06 15:50:39','2026-09-06 15:50:39');
/*!40000 ALTER TABLE `session_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `shop_info`
--

DROP TABLE IF EXISTS `shop_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shop_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '店铺ID',
  `name` varchar(100) NOT NULL COMMENT '店铺名称',
  `address` varchar(255) DEFAULT NULL COMMENT '店铺地址',
  `phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `owner_id` bigint DEFAULT NULL COMMENT '店长user_id',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 0-关闭 1-营业中',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='店铺信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `shop_info`
--

LOCK TABLES `shop_info` WRITE;
/*!40000 ALTER TABLE `shop_info` DISABLE KEYS */;
INSERT INTO `shop_info` VALUES (1,'测试剧本店','测试地址','13800000000',1,1,'2026-09-06 15:36:44','2026-09-06 15:36:44');
/*!40000 ALTER TABLE `shop_info` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_info`
--

DROP TABLE IF EXISTS `user_info`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(100) NOT NULL COMMENT 'BCrypt加密密码',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `role` tinyint NOT NULL DEFAULT '0' COMMENT '角色: 0-玩家 1-DM 2-店长 3-管理员',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态: 0-禁用 1-正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信息表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_info`
--

LOCK TABLES `user_info` WRITE;
/*!40000 ALTER TABLE `user_info` DISABLE KEYS */;
INSERT INTO `user_info` VALUES (1,'admin','$2a$10$NTC/G/eU5idrj8wpIvD1nu2y8Y0.Q6vM/GnAG3P0mXE8lJ8lzFYuu','13800000000',NULL,3,1,'2026-09-02 09:52:29','2026-09-06 15:32:57'),(2,'dm001','$2a$10$NTC/G/eU5idrj8wpIvD1nu2y8Y0.Q6vM/GnAG3P0mXE8lJ8lzFYuu','13800000001',NULL,1,1,'2026-09-02 09:52:29','2026-09-06 15:32:57'),(3,'testuser','$2a$10$noK8QnPeMHl.mm8fJk9xbuqpu9fOZ3OthfsQ2m0v2LRA6cv2Wo7Du',NULL,NULL,0,1,'2026-09-06 15:15:28','2026-09-06 15:15:28');
/*!40000 ALTER TABLE `user_info` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-06 16:30:35
