<template>
  <div class="page-container">
    <el-card>
      <div class="table-toolbar">
        <span>用户管理</span>
      </div>

      <el-table :data="tableData" border v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="账号" min-width="120" />
        <el-table-column prop="nickname" label="姓名" min-width="120" />
        <el-table-column prop="phone" label="手机号" min-width="130">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 1 ? 'danger' : 'success'">
              {{ row.role === 1 ? '管理员' : '员工' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'">
              {{ row.enabled === 1 ? '正常' : '已禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openRoleDialog(row)">
              改角色
            </el-button>
            <el-button
              :type="row.enabled === 1 ? 'warning' : 'success'"
              link
              @click="handleToggleStatus(row)"
            >
              {{ row.enabled === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button type="danger" link @click="openResetDialog(row)">
              重置密码
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[5, 10, 20]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @current-change="loadTable"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 修改角色 -->
    <el-dialog v-model="roleDialogVisible" title="修改角色" width="400px">
      <el-form label-width="80px">
        <el-form-item label="账号">
          <el-input :model-value="currentUser.username" disabled />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="roleForm.role" style="width: 100%">
            <el-option label="员工" :value="0" />
            <el-option label="管理员" :value="1" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRole">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="400px">
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="80px">
        <el-form-item label="账号">
          <el-input :model-value="currentUser.username" disabled />
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input
            v-model="resetForm.password"
            type="password"
            placeholder="不少于 6 位"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReset">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getUserPage,
  updateUserRole,
  updateUserStatus,
  resetUserPassword
} from '@/api/user'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref([])
const total = ref(0)

const query = ref({ pageNum: 1, pageSize: 5 })

const currentUser = ref({})
const roleDialogVisible = ref(false)
const roleForm = ref({ id: null, role: 0 })

const resetDialogVisible = ref(false)
const resetFormRef = ref(null)
const resetForm = ref({ id: null, password: '' })
const resetRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码不少于 6 位', trigger: 'blur' }
  ]
}

const loadTable = async () => {
  loading.value = true
  try {
    const res = await getUserPage({ ...query.value })
    tableData.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

const handleSizeChange = () => {
  query.value.pageNum = 1
  loadTable()
}

const openRoleDialog = (row) => {
  currentUser.value = row
  roleForm.value = { id: row.id, role: row.role }
  roleDialogVisible.value = true
}

const submitRole = async () => {
  submitting.value = true
  try {
    await updateUserRole({ ...roleForm.value })
    ElMessage.success('角色修改成功')
    roleDialogVisible.value = false
    loadTable()
  } catch (err) {
    // 错误提示由响应拦截器统一处理
  } finally {
    submitting.value = false
  }
}

const handleToggleStatus = async (row) => {
  const next = row.enabled === 1 ? 0 : 1
  const action = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定要${action}账号「${row.username}」吗？`, '提示', {
      type: 'warning'
    })
  } catch (e) {
    return
  }
  try {
    await updateUserStatus({ id: row.id, enabled: next })
    ElMessage.success(`${action}成功`)
    loadTable()
  } catch (err) {
    // 错误提示由响应拦截器统一处理
  }
}

const openResetDialog = (row) => {
  currentUser.value = row
  resetForm.value = { id: row.id, password: '' }
  resetDialogVisible.value = true
}

const submitReset = async () => {
  if (!resetFormRef.value) return
  await resetFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await resetUserPassword({ ...resetForm.value })
      ElMessage.success('密码重置成功')
      resetDialogVisible.value = false
    } catch (err) {
      // 错误提示由响应拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

onMounted(loadTable)
</script>