package com.urban.script.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息表实体
 *
 * <p>角色枚举（role 字段）：
 * <ul>
 *   <li>0 — 玩家（默认）</li>
 *   <li>1 — DM（剧本主持人）</li>
 *   <li>2 — 店长</li>
 *   <li>3 — 管理员</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Data
@TableName("user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（MySQL 自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名（唯一） */
    private String username;

    /** 密码（BCrypt 加密后存储） */
    private String password;

    /** 手机号（可选） */
    private String phone;

    /** 头像 URL（可选） */
    private String avatar;

    /** 角色：0=玩家 1=DM 2=店长 */
    private Integer role;

    /** 账号状态：0=禁用 1=正常 */
    private Integer status;

    /** 创建时间（INSERT 时自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（INSERT / UPDATE 时自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
