import { request } from '@/utils/request'

/* ============ 数据源 ============ */
export function pageDataSource (params) { return request({ url: '/sync/datasource/page', method: 'get', params }) }
export function listDataSource () { return request({ url: '/sync/datasource/list', method: 'get' }) }
export function addDataSource (data) { return request({ url: '/sync/datasource', method: 'post', data }) }
export function updateDataSource (data) { return request({ url: '/sync/datasource', method: 'put', data }) }
export function deleteDataSource (id) { return request({ url: `/sync/datasource/${id}`, method: 'delete' }) }
export function testDataSource (data) { return request({ url: '/sync/datasource/test', method: 'post', data }) }
export function listTables (id) { return request({ url: `/sync/datasource/${id}/tables`, method: 'get' }) }
export function listColumns (id, table) { return request({ url: `/sync/datasource/${id}/columns`, method: 'get', params: { table } }) }
export function getTableSchema (id, table) { return request({ url: `/sync/datasource/${id}/schema`, method: 'get', params: { table } }) }

/* ============ 数据浏览 ============ */
export function browseData (id, table, params) { return request({ url: `/sync/browse/${id}/table/${table}/data`, method: 'get', params }) }
export function insertRow (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}`, method: 'post', data, customError: true }) }
export function updateRow (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}`, method: 'put', data, customError: true }) }
export function deleteRow (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}`, method: 'delete', data }) }

/* 表结构 DDL 管理 */
export function addColumn (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/column`, method: 'post', data }) }
export function updateColumn (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/column`, method: 'put', data }) }
export function dropColumn (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/column`, method: 'delete', data }) }
export function listIndex (id, table) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/index`, method: 'get' }) }
export function addIndex (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/index`, method: 'post', data }) }
export function dropIndex (id, table, data) { return request({ url: `/sync/browse/${id}/table/${table}/ddl/index`, method: 'delete', data }) }

/* ============ SQL 工作台 ============ */
export function execSql (id, sql) { return request({ url: `/sync/sql/${id}/execute`, method: 'post', data: { sql } }) }
export function explainSql (id, sql, analyze) { return request({ url: `/sync/sql/${id}/explain`, method: 'post', data: { sql, analyze: !!analyze } }) }

/* ============ SQL 工作台 收藏 ============ */
export function pageSqlFavorite (params) { return request({ url: '/sync/sql/favorite/page', method: 'get', params }) }
export function detailSqlFavorite (id) { return request({ url: `/sync/sql/favorite/${id}`, method: 'get' }) }
export function addSqlFavorite (data) { return request({ url: '/sync/sql/favorite', method: 'post', data }) }
export function updateSqlFavorite (data) { return request({ url: '/sync/sql/favorite', method: 'put', data }) }
export function deleteSqlFavorite (id) { return request({ url: `/sync/sql/favorite/${id}`, method: 'delete' }) }
export function useSqlFavorite (id) { return request({ url: `/sync/sql/favorite/${id}/use`, method: 'post' }) }

/* ============ SQL 执行日志 ============ */
export function pageSqlLog (params) { return request({ url: '/sync/sql/log/page', method: 'get', params }) }
export function exportSqlLogUrl (params) {
  const qs = Object.entries(params).filter(([_, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
  return (process.env.NODE_ENV === 'production' ? '' : '/dev-api') + '/sync/sql/log/export?' + qs
}

/* ============ 同步任务 ============ */
export function pageTask (params) { return request({ url: '/sync/task/page', method: 'get', params }) }
export function detailTask (id) { return request({ url: `/sync/task/${id}`, method: 'get' }) }
export function addTask (data) { return request({ url: '/sync/task', method: 'post', data }) }
export function updateTask (data) { return request({ url: '/sync/task', method: 'put', data }) }
export function deleteTask (id) { return request({ url: `/sync/task/${id}`, method: 'delete' }) }
export function startTask (id) { return request({ url: `/sync/task/${id}/start`, method: 'post' }) }
export function pauseTask (id) { return request({ url: `/sync/task/${id}/pause`, method: 'post' }) }
export function resumeTask (id) { return request({ url: `/sync/task/${id}/resume`, method: 'post' }) }
export function stopTask (id) { return request({ url: `/sync/task/${id}/stop`, method: 'post' }) }
export function resetTask (id) { return request({ url: `/sync/task/${id}/reset`, method: 'post' }) }
export function taskProgress (id) { return request({ url: `/sync/task/${id}/progress`, method: 'get' }) }
/* 清理某个任务的日志: 不传 beforeDays = 清全部; 传 N = 只清 N 天前的历史日志. 返回删除条数 */
export function clearTaskLog (id, beforeDays) {
  return request({ url: `/sync/task/${id}/logs`, method: 'delete', params: beforeDays ? { beforeDays } : {} })
}
/* 按筛选条件批量清理日志 (任务/状态/关键字/时间/保留天数); 无条件时需 force=true. 返回删除条数 */
export function clearLogByFilter (params) { return request({ url: '/sync/log/clear', method: 'delete', params }) }
/* 任务大盘: 进度 + 实时速率/ETA/当前批次/瓶颈库 */
export function taskDashboard () { return request({ url: '/sync/task/dashboard', method: 'get' }) }

/* ============ 运行历史 (每次启动任务一条记录) ============ */
export function runPage (params) { return request({ url: '/sync/task/run/page', method: 'get', params }) }
/* 按当前筛选条件统计 (运行次数/成功/失败/行数/平均耗时/平均速率) */
export function runSummary (params) { return request({ url: '/sync/task/run/summary', method: 'get', params }) }
/* 近 N 天运行趋势 (次数/行数) */
export function runTrend (params) { return request({ url: '/sync/task/run/trend', method: 'get', params }) }
/* 按筛选条件清理运行历史 (保留天数 beforeDays; 无条件时需 force=true). 返回删除条数 */
export function clearTaskRun (params) { return request({ url: '/sync/task/run/clear', method: 'delete', params }) }
export function exportRunUrl (params) {
  const qs = Object.entries(params).filter(([_, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
  return (process.env.NODE_ENV === 'production' ? '' : '/dev-api') + '/sync/task/run/export?' + qs
}

/* 任务-字段映射 (源字段 -> 目标字段, FULL + INCR 都生效) */
export function listFieldMapping (taskId) { return request({ url: `/sync/task/fieldMapping/list/${taskId}`, method: 'get' }) }
export function saveFieldMapping (taskId, list) { return request({ url: `/sync/task/fieldMapping/save/${taskId}`, method: 'post', data: list }) }
export function clearFieldMapping (taskId) { return request({ url: `/sync/task/fieldMapping/${taskId}`, method: 'delete' }) }

/* ============ 日志 ============ */
export function pageLog (params) { return request({ url: '/sync/log/page', method: 'get', params }) }
/* 按当前筛选条件统计(条数/行数/耗时/异常数) */
export function logSummary (params) { return request({ url: '/sync/log/summary', method: 'get', params }) }
export function exportLogUrl (params) {
  const qs = Object.entries(params).filter(([_, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
  return (process.env.NODE_ENV === 'production' ? '' : '/dev-api') + '/sync/log/export?' + qs
}
/* 按天聚合同步日志(首页趋势图) */
export function logTrend (days) { return request({ url: '/sync/log/trend', method: 'get', params: { days } }) }

/* ============ License ============ */
/* customError: 授权模块是 opt-in 的, 未启用时后端路由不存在 (404),
   由 license.vue 自行降级为「模块未启用」占位, 不走全局错误弹窗 */
export function getLicense () { return request({ url: '/sync/license', method: 'get', customError: true }) }
export function updateLicense (data) { return request({ url: '/sync/license', method: 'put', data, customError: true }) }

/* ============ 用户管理 ============ */
export function pageUser (params) { return request({ url: '/system/user/page', method: 'get', params }) }
export function addUser (data) { return request({ url: '/system/user', method: 'post', data }) }
export function updateUser (data) { return request({ url: '/system/user', method: 'put', data }) }
export function deleteUser (id) { return request({ url: `/system/user/${id}`, method: 'delete' }) }
export function resetUser (id, password) { return request({ url: `/system/user/${id}/reset`, method: 'post', data: { password } }) }
export function changeUserStatus (id, status) { return request({ url: `/system/user/${id}/status?status=${status}`, method: 'post' }) }
