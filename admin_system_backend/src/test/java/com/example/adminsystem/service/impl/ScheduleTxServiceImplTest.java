package com.example.adminsystem.service.impl;

import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleRulePlugin;
import com.example.adminsystem.util.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ScheduleTxServiceImplTest {

    @Mock
    private ScheduleRulePlugin plugin;

    private final ScheduleTxServiceImpl service = new ScheduleTxServiceImpl();

    @Test
    void book_shouldValidateThenOnBooked() {
        ScheduleContext ctx = new ScheduleContext();

        service.book(ctx, plugin);

        InOrder order = inOrder(plugin);
        order.verify(plugin).validate(ctx);
        order.verify(plugin).onBooked(ctx);
    }

    @Test
    void approve_pass_shouldValidateThenOnBooked() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setTargetStatus(BookingStatusEnum.PASS.getCode());

        service.approve(ctx, plugin);

        InOrder order = inOrder(plugin);
        order.verify(plugin).validate(ctx);
        order.verify(plugin).onBooked(ctx);
    }

    @Test
    void approve_reject_shouldSkipHooks() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setTargetStatus(BookingStatusEnum.REJECT.getCode());

        service.approve(ctx, plugin);

        verifyNoInteractions(plugin);
    }

    @Test
    void approve_invalidStatus_shouldThrow() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setTargetStatus(99);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.approve(ctx, plugin));

        assertEquals(400, ex.getCode().intValue());
        verifyNoInteractions(plugin);
    }

    @Test
    void cancel_shouldCallOnCancelled() {
        ScheduleContext ctx = new ScheduleContext();

        service.cancel(ctx, plugin);

        verify(plugin).onCancelled(ctx);
        verify(plugin, never()).validate(ctx);
    }
}
