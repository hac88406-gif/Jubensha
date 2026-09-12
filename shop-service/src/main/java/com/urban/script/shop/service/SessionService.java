package com.urban.script.shop.service;

import com.urban.script.shop.dto.SessionCreateReq;
import com.urban.script.shop.dto.SessionFeignRes;
import com.urban.script.shop.dto.SessionRes;

import java.util.List;

/**
 * 场次服务接口
 *
 * @author urban-script-reservation
 */
public interface SessionService {

    /**
     * 创建场次（含 DM 冲突检测 + 店长归属校验）
     *
     * @param req        创建请求
     * @param operatorId 操作人（店长）用户 ID；ADMIN 角色可任意操作
     * @param role       操作人角色（X-User-Role）
     */
    Long createSession(SessionCreateReq req, Long operatorId, String role);

    /**
     * 关闭场次。
     *
     * <p>关闭动作：
     * <ol>
     *   <li>校验场次存在 + 开场前 2 小时外 + 操作人属于该场次店铺</li>
     *   <li>UPDATE session_info SET status=0</li>
     *   <li>DELETE Redis session 库存 key</li>
     *   <li>发 RabbitMQ 消息通知 order-service 批量取消未支付订单</li>
     * </ol>
     *
     * @param sessionId  场次 ID
     * @param operatorId 操作人（店长）用户 ID；ADMIN 角色可任意操作
     * @param role       操作人角色（X-User-Role）
     */
    void closeSession(Long sessionId, Long operatorId, String role);

    /** 查询某剧本未来 7 天的有效场次 */
    List<SessionRes> listSessionsByScript(Long scriptId);

    /** 查询某店铺未来 7 天的有效场次 */
    List<SessionRes> listSessionsByShop(Long shopId);

    /** 单个场次详情（玩家端 + 管理端共用） */
    SessionRes getSessionDetail(Long sessionId);

    /** Feign 内部接口：供 order-service 获取场次核心信息 */
    SessionFeignRes getSessionForFeign(Long sessionId);
}
