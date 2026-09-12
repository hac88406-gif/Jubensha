package com.urban.script.order.controller;

import com.urban.script.common.R;
import com.urban.script.common.annotation.RequireRole;
import com.urban.script.order.dto.PayNotifyReq;
import com.urban.script.order.dto.PrepayReq;
import com.urban.script.order.dto.PrepayRes;
import com.urban.script.order.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付 Controller —— 模拟支付闭环
 *
 * <p>路由：
 * <ul>
 *   <li>玩家端：{@code POST /order/pay/prepay} 发起支付（@RequireRole 鉴权）</li>
 *   <li>回调：{@code POST /order/pay/notify} 模拟支付平台异步回调
 *       （网关白名单放行，服务端靠 HMAC 签名验真，行为对齐真实微信/支付宝回调）</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Tag(name = "支付", description = "模拟支付：发起支付 / 支付回调")
@RestController
@RequestMapping("/order/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "发起支付（玩家）")
    @RequireRole("ROLE_PLAYER")
    @PostMapping("/prepay")
    public R<PrepayRes> prepay(@Valid @RequestBody PrepayReq req,
                               @RequestHeader(value = "X-User-Id", required = false) Long userId,
                               @RequestHeader(value = "X-User-Role", required = false) String role) {
        return R.ok("发起支付成功", paymentService.prepay(userId, req));
    }

    /**
     * 模拟支付平台回调。
     * <p>注意：此接口不校验登录态（网关白名单放行 /api/order/pay/notify），
     * 与真实微信/支付宝回调一致 —— 服务器对服务器调用，靠签名 + 幂等保证安全。
     */
    @Operation(summary = "模拟支付平台回调（notify）", description = "演示用，行为对齐真实支付回调")
    @PostMapping("/notify")
    public R<Void> notify(@Valid @RequestBody PayNotifyReq req) {
        paymentService.notify(req);
        return R.ok("支付成功", null);
    }
}