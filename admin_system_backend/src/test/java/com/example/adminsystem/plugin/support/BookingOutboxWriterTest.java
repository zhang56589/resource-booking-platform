package com.example.adminsystem.plugin.support;

import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.mapper.SysUserMapper;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.service.SysOutboxService;
import com.example.adminsystem.util.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingOutboxWriterTest {

    @Mock
    private SysOutboxService outboxService;

    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private BookingOutboxWriter writer;

    private ScheduleContext submitContext() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setUserId(2L);
        ctx.setUsername("user1");
        ctx.setInstanceName("大会议室 A");
        return ctx;
    }

    private ScheduleBooking booking() {
        ScheduleBooking booking = new ScheduleBooking();
        booking.setId(7L);
        booking.setUserId(2L);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setEndTime(LocalDateTime.now().plusHours(2));
        return booking;
    }

    @Test
    void saveSubmit_shouldPersistSubmitTopic() {
        when(outboxService.save(any())).thenReturn(true);

        writer.saveSubmit(submitContext(), booking());

        ArgumentCaptor<SysOutbox> captor = ArgumentCaptor.forClass(SysOutbox.class);
        verify(outboxService).save(captor.capture());
        SysOutbox outbox = captor.getValue();
        assertEquals("booking-notify-submit", outbox.getTopic());
        assertTrue(outbox.getPayload().contains("\"bookingId\":7"));
        assertTrue(outbox.getPayload().contains("user1"));
        assertEquals(0, outbox.getStatus().intValue());
    }

    @Test
    void saveSubmit_persistFail_shouldThrowBusinessException() {
        when(outboxService.save(any())).thenThrow(new RuntimeException("db down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> writer.saveSubmit(submitContext(), booking()));

        assertEquals(500, ex.getCode().intValue());
    }

    @Test
    void saveApprove_shouldPersistApproveTopicWithApplicant() {
        SysUser user = new SysUser();
        user.setUsername("user1");
        when(sysUserMapper.selectById(2L)).thenReturn(user);
        when(outboxService.save(any())).thenReturn(true);

        ScheduleContext ctx = new ScheduleContext();
        ctx.setAdminId(1L);
        ctx.setRejectReason("时间冲突");
        writer.saveApprove(ctx, booking(), "REJECT");

        ArgumentCaptor<SysOutbox> captor = ArgumentCaptor.forClass(SysOutbox.class);
        verify(outboxService).save(captor.capture());
        SysOutbox outbox = captor.getValue();
        assertEquals("booking-notify-approve", outbox.getTopic());
        assertTrue(outbox.getPayload().contains("user1"));
        assertTrue(outbox.getPayload().contains("\"result\":\"REJECT\""));
        assertTrue(outbox.getPayload().contains("时间冲突"));
    }
}
