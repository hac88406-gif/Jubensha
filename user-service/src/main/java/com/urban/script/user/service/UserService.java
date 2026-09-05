package com.urban.script.user.service;

import com.urban.script.user.dto.LoginReq;
import com.urban.script.user.dto.LoginRes;
import com.urban.script.user.dto.RegisterReq;
import com.urban.script.user.dto.UserProfileRes;

/**
 * 用户服务接口
 *
 * @author urban-script-reservation
 */
public interface UserService {

    /**
     * 用户注册
     *
     * @param req 注册参数（username / password / phone）
     * @return 新注册用户的 ID
     */
    Long register(RegisterReq req);

    /**
     * 用户登录
     *
     * @param req 登录参数（username / password）
     * @return 登录结果（token / userId / role）
     */
    LoginRes login(LoginReq req);

    /**
     * 获取用户个人信息（脱敏）
     *
     * @param userId 用户 ID
     * @return 脱敏后的个人信息
     */
    UserProfileRes getProfile(Long userId);
}
