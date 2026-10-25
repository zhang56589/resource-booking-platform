package com.example.adminsystem.plugin;

import com.example.adminsystem.service.ScheduleTxService;
import com.example.adminsystem.util.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleManagerTest {

    @Mock
    private PluginRegistry pluginRegistry;

    @Mock
    private ScheduleTxService scheduleTxService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private ScheduleRulePlugin plugin;

    private ScheduleManager newManager() {
        return new ScheduleManager(pluginRegistry, scheduleTxService, redissonClient);
    }

    private ScheduleContext meetingContext() {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setTypeCode("MEETING_ROOM");
        return ctx;
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void book_withLock_shouldRunTxInsideLockAndUnlock() throws Exception {
        ScheduleContext ctx = meetingContext();
        when(pluginRegistry.resolveByTypeCode("MEETING_ROOM")).thenReturn(plugin);
        when(plugin.buildLockKey(ctx)).thenReturn("schedule:room:1");
        when(redissonClient.getLock("schedule:room:1")).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        newManager().book(ctx);

        verify(scheduleTxService).book(ctx, plugin);
        verify(lock).unlock();
    }

    @Test
    void book_lockFail_shouldThrowAndSkipTx() throws Exception {
        ScheduleContext ctx = meetingContext();
        when(pluginRegistry.resolveByTypeCode("MEETING_ROOM")).thenReturn(plugin);
        when(plugin.buildLockKey(ctx)).thenReturn("schedule:room:1");
        when(redissonClient.getLock("schedule:room:1")).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> newManager().book(ctx));

        assertEquals(400, ex.getCode().intValue());
        verifyNoInteractions(scheduleTxService);
        verify(lock, never()).unlock();
    }

    @Test
    void book_blankLockKey_shouldRunTxWithoutLock() {
        ScheduleContext ctx = meetingContext();
        when(pluginRegistry.resolveByTypeCode("MEETING_ROOM")).thenReturn(plugin);
        when(plugin.buildLockKey(ctx)).thenReturn(" ");

        newManager().book(ctx);

        verify(scheduleTxService).book(ctx, plugin);
        verifyNoInteractions(redissonClient);
    }

    @Test
    void book_interrupted_shouldRestoreFlagAndThrow() throws Exception {
        ScheduleContext ctx = meetingContext();
        when(pluginRegistry.resolveByTypeCode("MEETING_ROOM")).thenReturn(plugin);
        when(plugin.buildLockKey(ctx)).thenReturn("schedule:room:1");
        when(redissonClient.getLock("schedule:room:1")).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        BusinessException ex = assertThrows(BusinessException.class, () -> newManager().book(ctx));

        assertEquals(400, ex.getCode().intValue());
        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(scheduleTxService);
    }

    @Test
    void book_txThrows_shouldStillUnlock() throws Exception {
        ScheduleContext ctx = meetingContext();
        when(pluginRegistry.resolveByTypeCode("MEETING_ROOM")).thenReturn(plugin);
        when(plugin.buildLockKey(ctx)).thenReturn("schedule:room:1");
        when(redissonClient.getLock("schedule:room:1")).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new BusinessException(400, "boom")).when(scheduleTxService).book(ctx, plugin);

        assertThrows(BusinessException.class, () -> newManager().book(ctx));

        verify(lock).unlock();
    }
}
