package com.example.adminsystem.plugin.impl;

import com.example.adminsystem.entity.ResourceInstance;
import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.mapper.ResourceInstanceMapper;
import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.mapper.ScheduleBookingItemMapper;
import com.example.adminsystem.mapper.ScheduleBookingMapper;
import com.example.adminsystem.plugin.ScheduleActionEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.support.BookingOutboxWriter;
import com.example.adminsystem.util.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeExclusivePluginTest {

    @Mock
    private ResourceTypeMapper resourceTypeMapper;

    @Mock
    private ResourceInstanceMapper resourceInstanceMapper;

    @Mock
    private ScheduleBookingMapper scheduleBookingMapper;

    @Mock
    private ScheduleBookingItemMapper scheduleBookingItemMapper;

    @Mock
    private BookingOutboxWriter bookingOutboxWriter;

    @InjectMocks
    private TimeExclusivePlugin plugin;

    private ScheduleContext bookContext() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.BOOK);
        ctx.setTypeCode(TimeExclusivePlugin.TYPE_MEETING_ROOM);
        ctx.setResourceTypeId(1L);
        ctx.setResourceInstanceId(9L);
        ctx.setInstanceCode("ROOM_3");
        ctx.setInstanceName("大会议室 A");
        ctx.setRoomId(3L);
        ctx.setUserId(2L);
        ctx.setStartTime(LocalDateTime.now().plusHours(1));
        ctx.setEndTime(LocalDateTime.now().plusHours(2));
        ctx.setTitle("周会");
        return ctx;
    }

    private ResourceInstance enabledInstance() {
        ResourceInstance instance = new ResourceInstance();
        instance.setId(9L);
        instance.setTypeId(1L);
        instance.setInstanceCode("ROOM_3");
        instance.setInstanceName("大会议室 A");
        instance.setStatus(1);
        return instance;
    }

    @Test
    void buildLockKey_shouldFollowAction() {
        ScheduleContext ctx = bookContext();
        assertEquals("schedule:room:9", plugin.buildLockKey(ctx));

        ctx.setAction(ScheduleActionEnum.APPROVE);
        ctx.setBookingId(5L);
        assertEquals("schedule:approve:5", plugin.buildLockKey(ctx));

        ctx.setAction(ScheduleActionEnum.CANCEL);
        assertNull(plugin.buildLockKey(ctx));
    }

    @Test
    void validateBook_pastTime_shouldThrow() {
        ScheduleContext ctx = bookContext();
        ctx.setStartTime(LocalDateTime.now().minusHours(1));

        BusinessException ex = assertThrows(BusinessException.class, () -> plugin.validate(ctx));

        assertEquals(400, ex.getCode().intValue());
    }

    @Test
    void validateBook_disabledInstance_shouldThrow() {
        ScheduleContext ctx = bookContext();
        ResourceInstance instance = enabledInstance();
        instance.setStatus(0);
        when(resourceInstanceMapper.selectById(9L)).thenReturn(instance);

        BusinessException ex = assertThrows(BusinessException.class, () -> plugin.validate(ctx));

        assertEquals(400, ex.getCode().intValue());
    }

    @Test
    void validateBook_conflict_shouldThrow() {
        ScheduleContext ctx = bookContext();
        when(resourceInstanceMapper.selectById(9L)).thenReturn(enabledInstance());
        when(scheduleBookingMapper.countConflict(eq(9L), any(), any(), isNull())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> plugin.validate(ctx));

        assertEquals("该会议室此时间段已被预约，时间冲突", ex.getMessage());
    }

    @Test
    void onBooked_book_shouldCreateBookingItemAndOutbox() {
        ScheduleContext ctx = bookContext();
        when(scheduleBookingMapper.insert(any())).thenAnswer(invocation -> {
            ScheduleBooking booking = invocation.getArgument(0);
            booking.setId(100L);
            return 1;
        });
        when(scheduleBookingItemMapper.insert(any())).thenReturn(1);

        plugin.onBooked(ctx);

        verify(scheduleBookingMapper).insert(any(ScheduleBooking.class));
        verify(scheduleBookingItemMapper).insert(any());
        verify(bookingOutboxWriter).saveSubmit(eq(ctx), any(ScheduleBooking.class));
        assertEquals(100L, ctx.getBookingId());
    }

    @Test
    void onRejected_shouldUpdateStatusAndWriteOutbox() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.APPROVE);
        ctx.setBookingId(5L);
        ctx.setTargetStatus(BookingStatusEnum.REJECT.getCode());
        ctx.setRejectReason("时间冲突");

        ScheduleBooking booking = new ScheduleBooking();
        booking.setId(5L);
        booking.setUserId(2L);
        booking.setResStatus(BookingStatusEnum.PENDING.getCode());
        booking.setExtJson("{}");
        when(scheduleBookingMapper.selectByIdForUpdate(5L)).thenReturn(booking);
        when(scheduleBookingMapper.updateById(any())).thenReturn(1);

        plugin.onRejected(ctx);

        assertEquals(BookingStatusEnum.REJECT.getCode(), booking.getResStatus());
        verify(bookingOutboxWriter).saveApprove(eq(ctx), eq(booking), eq("REJECT"));
    }
}
