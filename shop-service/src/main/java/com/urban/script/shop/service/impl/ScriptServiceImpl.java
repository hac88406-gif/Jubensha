package com.urban.script.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.urban.script.common.BusinessException;
import com.urban.script.common.ResultCode;
import com.urban.script.shop.dto.ScriptCreateReq;
import com.urban.script.shop.dto.ScriptQueryReq;
import com.urban.script.shop.dto.ScriptRes;
import com.urban.script.shop.entity.ScriptInfo;
import com.urban.script.shop.mapper.ScriptMapper;
import com.urban.script.shop.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 剧本服务实现
 *
 * @author urban-script-reservation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptServiceImpl implements ScriptService {

    private final ScriptMapper scriptMapper;

    @Override
    public Long createScript(ScriptCreateReq req) {
        ScriptInfo s = new ScriptInfo();
        s.setShopId(req.getShopId());
        s.setName(req.getName());
        s.setAuthor(req.getAuthor());
        s.setScriptType(req.getScriptType());
        s.setPlayerMin(req.getPlayerMin());
        s.setPlayerMax(req.getPlayerMax());
        s.setDuration(req.getDuration());
        s.setPrice(req.getPrice());
        s.setStock(req.getStock());
        s.setStatus(1);
        scriptMapper.insert(s);
        log.info("[createScript] id={}, name={}", s.getId(), s.getName());
        return s.getId();
    }

    @Override
    public List<ScriptRes> listScripts(ScriptQueryReq req) {
        QueryWrapper<ScriptInfo> w = new QueryWrapper<>();

        // status: 玩家端调用方会强制设为 1；管理端传 null 则不过滤
        if (req.getStatus() != null) {
            w.eq("status", req.getStatus());
        }
        // shopId
        if (req.getShopId() != null) {
            w.eq("shop_id", req.getShopId());
        }
        // script_type
        if (StringUtils.hasText(req.getType())) {
            w.eq("script_type", req.getType());
        }
        // playerCnt: player_min ≤ playerCnt ≤ player_max
        if (req.getPlayerCnt() != null) {
            w.le("player_min", req.getPlayerCnt());
            w.ge("player_max", req.getPlayerCnt());
        }
        // 名称模糊匹配
        if (StringUtils.hasText(req.getNameKeyword())) {
            w.like("name", req.getNameKeyword());
        }

        w.orderByDesc("create_time");
        return scriptMapper.selectList(w).stream()
                .map(ScriptRes::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ScriptRes getScript(Long id) {
        ScriptInfo s = scriptMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "剧本不存在");
        }
        return ScriptRes.fromEntity(s);
    }

    @Override
    public void updateScript(Long id, ScriptCreateReq req) {
        ScriptInfo s = scriptMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "剧本不存在");
        }
        s.setName(req.getName());
        s.setAuthor(req.getAuthor());
        s.setScriptType(req.getScriptType());
        s.setPlayerMin(req.getPlayerMin());
        s.setPlayerMax(req.getPlayerMax());
        s.setDuration(req.getDuration());
        s.setPrice(req.getPrice());
        s.setStock(req.getStock());
        if (req.getShopId() != null) {
            s.setShopId(req.getShopId());
        }
        scriptMapper.updateById(s);
        log.info("[updateScript] id={}", id);
    }

    @Override
    public void deleteScript(Long id) {
        ScriptInfo s = scriptMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "剧本不存在");
        }
        ScriptInfo update = new ScriptInfo();
        update.setId(id);
        update.setStatus(0);
        scriptMapper.updateById(update);
        log.info("[deleteScript] id={}, name={} offline", id, s.getName());
    }
}
