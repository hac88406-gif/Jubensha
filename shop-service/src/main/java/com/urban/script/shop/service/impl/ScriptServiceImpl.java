package com.urban.script.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
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

    /** Spring Boot 自动装配的 Jackson ObjectMapper，负责 characters JSON 的序列化/反序列化 */
    private final ObjectMapper objectMapper;

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
        // 富化字段
        s.setImage(req.getImage());
        s.setBackground(req.getBackground());
        s.setTags(req.getTags());
        s.setMark(req.getMark());
        s.setMarkCnt(req.getMarkCnt());
        s.setMaleNum(req.getMaleNum());
        s.setFemaleNum(req.getFemaleNum());
        s.setUnknownNum(req.getUnknownNum());
        // 角色列表 JSON 序列化入库
        s.setCharacters(writeCharacters(req.getCharacters()));
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
        // 列表接口不返回 characters（fromEntity 不填充该字段，保持 null，控制 payload 体积）
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
        ScriptRes res = ScriptRes.fromEntity(s);
        // 详情接口才解析并返回角色列表
        res.setCharacters(parseCharacters(s.getCharacters()));
        return res;
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
        // 富化字段（仅当请求传了才更新，避免覆盖已有值）
        if (req.getImage() != null) s.setImage(req.getImage());
        if (req.getBackground() != null) s.setBackground(req.getBackground());
        if (req.getTags() != null) s.setTags(req.getTags());
        if (req.getMark() != null) s.setMark(req.getMark());
        if (req.getMarkCnt() != null) s.setMarkCnt(req.getMarkCnt());
        if (req.getMaleNum() != null) s.setMaleNum(req.getMaleNum());
        if (req.getFemaleNum() != null) s.setFemaleNum(req.getFemaleNum());
        if (req.getUnknownNum() != null) s.setUnknownNum(req.getUnknownNum());
        // 角色列表（仅当请求显式传入时才更新；传 null 保留原值，传空数组则清空）
        if (req.getCharacters() != null) s.setCharacters(writeCharacters(req.getCharacters()));
        scriptMapper.updateById(s);
        log.info("[updateScript] id={}", id);
    }

    /**
     * characters 结构化列表 → JSON 字符串（入库格式）
     * 序列化失败时返回 null，避免因脏数据中断业务流程
     */
    private String writeCharacters(List<Map<String, Object>> chars) {
        if (chars == null || chars.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(chars);
        } catch (Exception e) {
            log.warn("[script] characters 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * characters JSON 字符串 → 结构化列表（详情接口出参）
     * 空值或解析失败一律返回 null，由前端兜底隐藏
     */
    private List<Map<String, Object>> parseCharacters(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            List<Map<String, Object>> list =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return list == null || list.isEmpty() ? null : list;
        } catch (Exception e) {
            log.warn("[script] characters 解析失败: {}", e.getMessage());
            return null;
        }
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
