<template>
  <div class="page-container">
    <el-card>
      <div class="table-toolbar">
        <span>可用会议室列表</span>
        <el-button type="primary" @click="loadRooms">刷新</el-button>
      </div>

      <el-table :data="roomList" border v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column prop="roomName" label="会议室名称" min-width="140" />
        <el-table-column prop="location" label="位置" min-width="140" />
        <el-table-column prop="capacity" label="容纳人数" width="100" />
        <el-table-column prop="description" label="描述" min-width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '可用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openBooking(row)">预约</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 提交预约弹窗 -->
    <el-dialog v-model="dialogVisible" title="提交预约" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="会议室">
          <el-input :model-value="form.roomName" disabled />
        </el-form-item>
        <el-form-item label="申请人">
          <el-input :model-value="uxCurrentUser" disabled />
        </el-form-item>
        <el-form-item label="预约时间" prop="timeRange">
          <el-date-picker
            v-model="form.timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :disabled-date="uxDisabledDate"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="会议主题" prop="title">
          <el-input
            v-model="form.title"
            placeholder="请填写会议主题"
            maxlength="30"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="申请理由" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            placeholder="请填写申请理由"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          提交
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAvailableRoomList } from '@/api/room'
import { submitBooking } from '@/api/booking'

const loading = ref(false)
const submitting = ref(false)
const roomList = ref([])
const dialogVisible = ref(false)
const formRef = ref(null)

const form = ref({
  roomId: null,
  roomName: '',
  timeRange: [],
  title: '',
  reason: ''
})

const rules = {
  timeRange: [
    { required: true, message: '请选择预约时间段', trigger: 'change' },
    {
      // 本次升级「新增」校验：结束时间必须晚于开始时间 + 仅允许工作日
      validator: (rule, value, callback) => {
        if (!value || value.length !== 2) return callback()
        const [s, e] = value
        if (s >= e) return callback(new Error('结束时间必须晚于开始时间'))
        const sd = new Date(String(s).replace(' ', 'T'))
        const ed = new Date(String(e).replace(' ', 'T'))
        const isWeekend = (d) => d.getDay() === 0 || d.getDay() === 6
        if (isWeekend(sd) || isWeekend(ed)) {
          return callback(new Error('仅允许选择工作日（周一至周五）'))
        }
        callback()
      },
      trigger: 'change'
    }
  ],
  title: [{ required: true, message: '请填写会议主题', trigger: 'blur' }],
  reason: [{ required: true, message: '请填写申请理由', trigger: 'blur' }]
}

// 禁止选择过去的日期
const disablePastDate = (time) => time.getTime() < Date.now() - 86400000

// 本次升级「新增」：当前登录人回填（仅展示，不改动提交入参）+ 工作日可选限制
const uxCurrentUser = localStorage.getItem('username') || '当前登录人'
const uxDisabledDate = (time) =>
  disablePastDate(time) || time.getDay() === 0 || time.getDay() === 6

const loadRooms = async () => {
  loading.value = true
  try {
    const res = await getAvailableRoomList()
    roomList.value = res.data || []
  } finally {
    loading.value = false
  }
}

const openBooking = (row) => {
  form.value = {
    roomId: row.id,
    roomName: row.roomName,
    timeRange: [],
    title: '',
    reason: ''
  }
  dialogVisible.value = true
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    const [startTime, endTime] = form.value.timeRange
    if (startTime >= endTime) {
      ElMessage.warning('开始时间必须小于结束时间')
      return
    }
    if (new Date(startTime.replace(' ', 'T')).getTime() < Date.now()) {
      ElMessage.warning('不能选择过去的时间')
      return
    }
    submitting.value = true
    try {
      await submitBooking({
        roomId: form.value.roomId,
        startTime,
        endTime,
        title: form.value.title,
        reason: form.value.reason
      })
      ElMessage.success('预约已提交，等待管理员审批')
      dialogVisible.value = false
    } catch (err) {
      // 错误提示由响应拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

onMounted(loadRooms)
</script>