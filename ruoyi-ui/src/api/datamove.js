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
/* 任务大盘: 进度 + 实时速率/ETA/当前批次/瓶颈库 */
export function taskDashboard () { return request({ url: '/sync/task/dashboard', method: 'get' }) }

/* 任务-字段映射 (源字段 -> 目标字段, FULL + INCR 都生效) */
export function listFieldMapping (taskId) { return request({ url: `/sync/task/fieldMapping/list/${taskId}`, method: 'get' }) }
export function saveFieldMapping (taskId, list) { return request({ url: `/sync/task/fieldMapping/save/${taskId}`, method: 'post', data: list }) }
export function clearFieldMapping (taskId) { return request({ url: `/sync/task/fieldMapping/${taskId}`, method: 'delete' }) }

/* ============ 日志 ============ */
export function pageLog (params) { return request({ url: '/sync/log/page', method: 'get', params }) }
export function exportLogUrl (params) {
  const qs = Object.entries(params).filter(([_, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')
  return (process.env.NODE_ENV === 'production' ? '' : '/dev-api') + '/sync/log/export?' + qs
}
/* 按天聚合同步日志(首页趋势图) */
export function logTrend (days) { return request({ url: '/sync/log/trend', method: 'get', params: { days } }) }

/* ============ License ============ */
export function getLicense () { return request({ url: '/sync/license', method: 'get' }) }
export function updateLicense (data) { return request({ url: '/sync/license', method: 'put', data }) }

/* ============ 用户管理 ============ */
export function pageUser (params) { return request({ url: '/system/user/page', method: 'get', params }) }
export function addUser (data) { return request({ url: '/system/user', method: 'post', data }) }
export function updateUser (data) { return request({ url: '/system/user', method: 'put', data }) }
export function deleteUser (id) { return request({ url: `/system/user/${id}`, method: 'delete' }) }
export function resetUser (id, password) { return request({ url: `/system/user/${id}/reset`, method: 'post', data: { password } }) }
export function changeUserStatus (id, status) { return request({ url: `/system/user/${id}/status?status=${status}`, method: 'post' }) }
