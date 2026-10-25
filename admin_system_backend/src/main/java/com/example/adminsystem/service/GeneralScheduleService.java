package com.example.adminsystem.service;

import com.example.adminsystem.dto.ScheduleBookDTO;

/**
 * 通用调度门面：按资源类型/实例组装 ScheduleContext，校验与持久化全部交给内核插件。
 */
public interface GeneralScheduleService {

    void book(Long userId, ScheduleBookDTO dto);

    void cancel(Long bookingId, Long userId);
}
