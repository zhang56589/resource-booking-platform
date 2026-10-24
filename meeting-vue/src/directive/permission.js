// P1-5：v-permission 按钮权限指令
// 用法：v-permission="'booking:approve'"，不在当前用户权限码数组内则移除元素
// 权限码数组在登录成功后由 /api/user/menu 接口写入 localStorage.permissions
export default {
  mounted(el, binding) {
    let permissions = []
    try {
      permissions = JSON.parse(localStorage.getItem('permissions') || '[]')
    } catch (e) {
      permissions = []
    }
    const need = binding.value
    if (need && !permissions.includes(need)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
}
