package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("meeting_booking")
public class MeetingBooking {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会议室id */
    private Long roomId;

    /** 预约用户id */
    private Long userId;

    /** 会议开始时间 */
    private LocalDateTime startTime;

    /** 会议结束时间 */
    private LocalDateTime endTime;

    /**
     * 预约状态：0待审批，1已通过，2已驳回，3已取消
     * @see com.example.adminsystem.enums.BookingStatusEnum
     */
    @TableField("status")
    private Integer resStatus;

    /** 驳回原因，仅驳回状态有效 */
    private String rejectReason;

    /** 会议主题 */
    private String title;

    /** 申请理由 */
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除 0=正常 1=已删 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
