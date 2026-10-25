package com.example.adminsystem.plugin;

import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件注册表：启动期收集所有 ScheduleRulePlugin，运行期按资源类型路由。
 * 使用 ObjectProvider 注入，保证没有插件实现时也能正常启动。
 */
@Slf4j
@Component
public class PluginRegistry {

    private final Map<String, ScheduleRulePlugin> pluginMap = new LinkedHashMap<>();

    public PluginRegistry(ObjectProvider<ScheduleRulePlugin> pluginProvider) {
        pluginProvider.orderedStream().forEach(this::register);
        log.info("调度插件注册完成 count={}, codes={}", pluginMap.size(), pluginMap.keySet());
    }

    public ScheduleRulePlugin resolveByTypeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw new BusinessException(Result.BAD_REQUEST, "不支持的资源类型");
        }
        return pluginMap.values().stream()
                .filter(plugin -> plugin.supports(typeCode))
                .findFirst()
                .orElseThrow(() -> new BusinessException(Result.BAD_REQUEST, "不支持的资源类型"));
    }

    private void register(ScheduleRulePlugin plugin) {
        String code = plugin.pluginCode();
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("调度插件编码不能为空");
        }
        if (pluginMap.putIfAbsent(code, plugin) != null) {
            throw new IllegalStateException("调度插件编码重复: " + code);
        }
    }
}
