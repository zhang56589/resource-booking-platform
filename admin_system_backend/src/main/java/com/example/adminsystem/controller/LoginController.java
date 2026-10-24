package com.example.adminsystem.controller;

import com.example.adminsystem.dto.LoginDTO;
import com.example.adminsystem.dto.RegisterDTO;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.enums.UserRoleEnum;
import com.example.adminsystem.service.SysUserService;
import com.example.adminsystem.util.JwtUtil;
import com.example.adminsystem.util.PasswordUtil;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 登录注册 —— 纯入口 Controller。
 * 登录逻辑委托给 SysUserService.login（内部处理账号不存在、密码错误、账号禁用等校验）。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class LoginController {

    @Resource
    private SysUserService sysUserService;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * P1-4 修复：使用 LoginDTO + @Valid，username/password 空值/空串在 Controller 层就被拦截，
     * 不再透传到 SysUserService.login 做无谓查询。
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        SysUser user = sysUserService.login(dto.getUsername(), dto.getPassword());
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("role", user.getRole());
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        log.info("用户登录成功 username={}, userId={}", user.getUsername(), user.getId());
        return Result.success(data);
    }

    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterDTO dto) {
        boolean exist = sysUserService.existUsername(dto.getUsername());
        if (exist) {
            return Result.fail(400, "用户名已存在");
        }
        SysUser sysUser = new SysUser();
        sysUser.setUsername(dto.getUsername());
        sysUser.setNickname(dto.getNickname());
        sysUser.setPassword(PasswordUtil.encrypt(dto.getPassword()));
        // ====== P1-3 修复：用枚举常量替代硬编码 role=0 ======
        sysUser.setRole(UserRoleEnum.EMPLOYEE.getCode());
        sysUserService.save(sysUser);
        log.info("用户注册成功 username={}", dto.getUsername());
        return Result.success("注册成功");
    }
}
