package com.urban.script.shop.service;

import com.urban.script.shop.dto.ScriptCreateReq;
import com.urban.script.shop.dto.ScriptQueryReq;
import com.urban.script.shop.dto.ScriptRes;

import java.util.List;

/**
 * 剧本服务接口
 *
 * @author urban-script-reservation
 */
public interface ScriptService {

    /** 创建剧本 */
    Long createScript(ScriptCreateReq req);

    /** 条件查询剧本（玩家端固定 status=1；管理端可以传 null 查全部） */
    List<ScriptRes> listScripts(ScriptQueryReq req);

    /** 单个剧本详情 */
    ScriptRes getScript(Long id);

    /** 更新剧本 */
    void updateScript(Long id, ScriptCreateReq req);

    /** 删除剧本（逻辑删除：status → 0） */
    void deleteScript(Long id);
}
