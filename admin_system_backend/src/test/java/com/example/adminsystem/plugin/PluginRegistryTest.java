package com.example.adminsystem.plugin;

import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.util.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginRegistryTest {

    @Mock
    private ObjectProvider<ScheduleRulePlugin> provider;

    @Mock
    private ScheduleRulePlugin plugin;

    @Mock
    private ResourceTypeMapper resourceTypeMapper;

    private PluginRegistry registry(ScheduleRulePlugin... plugins) {
        when(provider.orderedStream()).thenReturn(Arrays.stream(plugins));
        return new PluginRegistry(provider, resourceTypeMapper);
    }

    @Test
    void resolve_supportedType_returnsPlugin() {
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");
        when(plugin.supports("MEETING_ROOM")).thenReturn(true);

        assertSame(plugin, registry(plugin).resolveByTypeCode("MEETING_ROOM"));
    }

    @Test
    void resolve_unknownType_throws400() {
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");
        when(plugin.supports("WORKSTATION")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry(plugin).resolveByTypeCode("WORKSTATION"));

        assertEquals(400, ex.getCode().intValue());
    }

    @Test
    void resolve_pluginCodeFallback_returnsPlugin() {
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");
        when(plugin.supports("WORKSTATION")).thenReturn(false);
        when(resourceTypeMapper.selectPluginCodeByTypeCode("WORKSTATION")).thenReturn("TIME_EXCLUSIVE");

        assertSame(plugin, registry(plugin).resolveByTypeCode("WORKSTATION"));
    }

    @Test
    void resolve_pluginCodeFallbackMiss_throws400() {
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");
        when(plugin.supports("WORKSTATION")).thenReturn(false);
        when(resourceTypeMapper.selectPluginCodeByTypeCode("WORKSTATION")).thenReturn("QUOTA_SHARE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry(plugin).resolveByTypeCode("WORKSTATION"));

        assertEquals(400, ex.getCode().intValue());
    }

    @Test
    void resolve_blankType_throws400() {
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry(plugin).resolveByTypeCode(" "));

        assertEquals(400, ex.getCode().intValue());
    }

    @Test
    void duplicatePluginCode_throwsIllegalState() {
        ScheduleRulePlugin another = mock(ScheduleRulePlugin.class);
        when(plugin.pluginCode()).thenReturn("TIME_EXCLUSIVE");
        when(another.pluginCode()).thenReturn("TIME_EXCLUSIVE");

        assertThrows(IllegalStateException.class, () -> registry(plugin, another));
    }
}
