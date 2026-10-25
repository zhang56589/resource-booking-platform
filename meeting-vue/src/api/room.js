import request from '@/utils/request'

// 管理员：分页查询全部会议室
export function getRoomPage(params) {
  return request.get('/room/page', { params })
}

// 员工：查询可用（启用）会议室列表
export function getAvailableRoomList() {
  return request.get('/room/list')
}

// 管理员：新增会议室
export function addRoom(data) {
  return request.post('/room/add', data)
}

// 管理员：编辑会议室
export function updateRoom(data) {
  return request.put('/room/update', data)
}

// 管理员：删除会议室（存在未完结预约时由后端拦截并返回提示）
export function deleteRoom(id) {
  return request.delete(`/room/delete/${id}`)
}

// 管理员：启用会议室
export function enableRoom(id) {
  return request.put(`/room/enable/${id}`)
}

// 管理员：禁用会议室
export function disableRoom(id) {
  return request.put(`/room/disable/${id}`)
}

// 管理员：批量启用 { ids: [] }
export function batchEnableRooms(ids) {
  return request.put('/room/batch-enable', { ids })
}

// 管理员：批量禁用 { ids: [] }
export function batchDisableRooms(ids) {
  return request.put('/room/batch-disable', { ids })
}