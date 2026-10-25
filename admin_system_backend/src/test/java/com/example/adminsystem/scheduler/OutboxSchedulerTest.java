package com.example.adminsystem.scheduler;

import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.service.SysOutboxService;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

    @Mock
    private SysOutboxService outboxService;

    @InjectMocks
    private OutboxScheduler scheduler;

    @Test
    void mqAbsent_shouldKeepPendingWithoutTouchingDb() {
        // rocketMQTemplate 未注入 → 保持 status=0 待发送，不抢占、不累加重试
        assertDoesNotThrow(() -> scheduler.relayOutbox());

        verifyNoInteractions(outboxService);
    }

    @Test
    void mqPresent_noPending_shouldSkipSelect() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                scheduler, "rocketMQTemplate", org.mockito.Mockito.mock(RocketMQTemplate.class));
        when(outboxService.markProcessing(anyInt())).thenReturn(0);

        scheduler.relayOutbox();

        verify(outboxService).markProcessing(anyInt());
        verify(outboxService, never()).selectProcessing();
    }

    @Test
    void mqPresent_withPending_shouldSendToTopic() {
        RocketMQTemplate template = org.mockito.Mockito.mock(RocketMQTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler, "rocketMQTemplate", template);

        SysOutbox outbox = new SysOutbox();
        outbox.setId(9L);
        outbox.setMsgId("msg9");
        outbox.setTopic("booking-notify-submit");
        outbox.setTag(null);
        outbox.setPayload("{\"bookingId\":9}");

        when(outboxService.markProcessing(anyInt())).thenReturn(1);
        when(outboxService.selectProcessing()).thenReturn(List.of(outbox));

        scheduler.relayOutbox();

        verify(template).asyncSend(eq("booking-notify-submit"), eq("{\"bookingId\":9}"),
                any(SendCallback.class));
    }
}
