package com.urban.script.shop.dto;

import com.urban.script.shop.entity.SessionInfo;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 场次详情响应 DTO
 *
 * @author urban-script-reservation
 */
@Data
@Builder
public class SessionRes {

    private Long id;
    private Long scriptId;
    private Long shopId;
    private Long dmId;
    private LocalDate sessionDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer capacity;
    private Integer booked;
    /** 余位数（capacity - booked） */
    private Integer remaining;
    private Integer status;
    private LocalDateTime createTime;

    public static SessionRes fromEntity(SessionInfo e) {
        int booked = e.getBooked() == null ? 0 : e.getBooked();
        return SessionRes.builder()
                .id(e.getId())
                .scriptId(e.getScriptId())
                .shopId(e.getShopId())
                .dmId(e.getDmId())
                .sessionDate(e.getSessionDate())
                .startTime(e.getStartTime())
                .endTime(e.getEndTime())
                .capacity(e.getCapacity())
                .booked(booked)
                .remaining(e.getCapacity() - booked)
                .status(e.getStatus())
                .createTime(e.getCreateTime())
                .build();
    }
}
