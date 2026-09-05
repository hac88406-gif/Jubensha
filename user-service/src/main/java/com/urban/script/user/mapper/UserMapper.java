package com.urban.script.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.urban.script.user.entity.UserInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper
 *
 * @author urban-script-reservation
 */
@Mapper
public interface UserMapper extends BaseMapper<UserInfo> {

    /**
     * 根据登录用户名查询（username 有唯一索引，最多一条）
     *
     * @param username 登录用户名
     * @return UserInfo（找不到返回 null）
     */
    @Select("SELECT * FROM user_info WHERE username = #{username} LIMIT 1")
    UserInfo selectByUsername(@Param("username") String username);
}
