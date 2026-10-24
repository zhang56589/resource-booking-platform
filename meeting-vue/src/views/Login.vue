<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <h2 class="auth-title">会议室预约管理系统</h2>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="70px"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入账号" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            style="width: 100%"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="auth-footer">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, getUserMenu } from '@/api/auth'

const router = useRouter()
const route = useRoute()

const formRef = ref(null)
const loading = ref(false)
const form = ref({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      const res = await login(form.value)
      const { token, role, userId, username } = res.data
      localStorage.setItem('token', token)
      localStorage.setItem('role', String(role))
      localStorage.setItem('userId', String(userId))
      localStorage.setItem('username', username)
      // P1-5：登录后拉取动态菜单树 + 权限码数组（失败不阻断登录，侧边栏兜底静态菜单）
      try {
        const menuRes = await getUserMenu()
        localStorage.setItem('menus', JSON.stringify(menuRes.data.menus || []))
        localStorage.setItem('permissions', JSON.stringify(menuRes.data.permissions || []))
      } catch (e) {
        localStorage.removeItem('menus')
        localStorage.removeItem('permissions')
      }
      ElMessage.success('登录成功')
      const redirect = route.query.redirect
      if (redirect) {
        router.push(String(redirect))
      } else {
        router.push(Number(role) === 1 ? '/home/room-manage' : '/home/rooms')
      }
    } catch (err) {
      // 错误提示已由响应拦截器统一处理
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.auth-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1890ff 0%, #0050b3 100%);
}

.auth-card {
  width: 400px;
  padding: 8px 12px;
}

.auth-title {
  text-align: center;
  margin: 8px 0 24px;
  font-size: 20px;
  color: #303133;
}

.auth-footer {
  text-align: center;
  font-size: 13px;
  color: #909399;
}
</style>