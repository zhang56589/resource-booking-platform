package com.example.adminsystem.plugin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 调度上下文：插件与调度管理器之间的唯一数据载体。
 * 只放扁平字段，不依赖具体资源实体，避免内核层与某类资源耦合。
 */
@Data
public class ScheduleContext {

    /** 资源类型编码，插件路由依据 */
    private String typeCode;

    /** 调度动作：BOOK / APPROVE / CANCEL */
    private ScheduleActionEnum action;

    private Long resourceTypeId;

    private Long resourceInstanceId;

    /** 资源实例编码，时段/明细类插件常用 */
    private String instanceCode;

    /** 资源实例名称，用于通知与查询 */
    private String instanceName;

    /** 会议室场景下对应的 meeting_room.id，其他资源类型可为空 */
    private Long roomId;

    private Long userId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 数量：时段类固定 1，配额类为申请量 */
    private Integer quantity = 1;

    /** 预约单 ID：提交时为空，审批/取消时必填 */
    private Long bookingId;

    /** 会议主题 / 申请理由 */
    private String title;

    private String reason;

    /** 提交人登录名，用于通知 payload */
    private String username;

    /** 审批人 ID */
    private Long adminId;

    /** 审批目标状态：1 通过 / 2 驳回 */
    private Integer targetStatus;

    private String rejectReason;

    /** 来源：LEGACY / API */
    private String source;

    /** 插件扩展参数，避免为未知资源类型频繁改内核接口 */
    private Map<String, Object> extParams = new HashMap<>();
}
