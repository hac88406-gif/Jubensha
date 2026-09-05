package com.urban.script.user.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 *
 * <ul>
 *   <li>@MapperScan: 扫描所有 Mapper 接口所在包</li>
 *   <li>PaginationInnerInterceptor: 分页插件（MySQL 方言）</li>
 *   <li>MetaObjectHandler: 自动填充 createTime / updateTime</li>
 * </ul>
 *
 * @author urban-script-reservation
 */
@Configuration
@MapperScan("com.urban.script.user.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 全局拦截器 —— MySQL 分页
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 公共字段自动填充处理器
     * <p>
     * 所有实体里标注了 {@code @TableField(fill = FieldFill.INSERT)} 的字段会在 INSERT 时
     * 自动填值；{@code FieldFill.INSERT_UPDATE} 在 INSERT 和 UPDATE 时都填。
     * </p>
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
