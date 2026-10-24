import request from '@/utils/request'

// 登录：返回 { role, token, userId, username }
export function login(data) {
  return request.post('/login', data)
}

// 注册：{ username, password, nickname }
export function register(data) {
  return request.post('/register', data)
}

// P1-5：拉取当前用户的动态菜单树 + 权限码数组
export function getUserMenu() {
  return request.get('/user/menu')
}