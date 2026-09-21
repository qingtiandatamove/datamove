<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix">
        <span>同步任务</span>
        <el-button-group style="float:right">
          <el-button type="primary" icon="el-icon-plus" size="mini" @click="onAdd('FULL')">新建全量任务</el-button>
          <el-button type="success" icon="el-icon-plus" size="mini" @click="onAdd('INCR')">新建增量任务</el-button>
          <el-button type="warning" icon="el-icon-plus" size="mini" @click="onAdd('DDL')">同步表结构</el-button>
        </el-button-group>
      </div>

      <el-form :inline="true" :model="query">
        <el-form-item><el-input v-model="query.keyword" placeholder="任务名称/表名" size="small" clearable /></el-form-item>
        <el-form-item>
          <el-select v-model="query.taskType" size="small" clearable placeholder="任务类型" style="width:140px">
            <el-option label="全量" value="FULL" />
            <el-option label="增量" value="INCR" />
            <el-option label="同步表结构" value="DDL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.status" size="small" clearable placeholder="状态" style="width:140px">
            <el-option label="未启动" value="STOP" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已暂停" value="PAUSE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table :data="page.rows" v-loading="loading" border class="sync-task-table"
        @sort-change="onSortChange">
        <el-table-column prop="id" label="ID" width="70" sortable="custom" :sort-orders="['ascending','descending']" />
        <el-table-column prop="taskName" label="任务名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="taskType" label="类型" width="90">
          <template slot-scope="s">
            <el-tag size="mini" :type="taskTypeTag(s.row.taskType)">
              {{ taskTypeName(s.row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="syncMode" label="模式" width="85">
          <template slot-scope="s">{{ modeName(s.row.syncMode) }}</template>
        </el-table-column>
        <el-table-column prop="overwriteFlag" label="覆盖" width="70" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.taskType === 'FULL' && s.row.overwriteFlag === 1" size="mini" type="danger">覆盖</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="tableName" label="表名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="batchSize" label="批次" width="75">
          <template slot-scope="s">{{ s.row.taskType === 'DDL' ? '-' : s.row.batchSize }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ statusName(s.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 创建时间: 可按时间排序, 默认按 id 排序时此列无图标 -->
        <el-table-column prop="createTime" label="创建时间" width="170" sortable="custom" :sort-orders="['descending','ascending']">
          <template slot-scope="s">{{ fmtTime(s.row.createTime) }}</template>
        </el-table-column>
        <!-- 操作列: 按钮平铺, 通过去 icon + CSS 收紧 padding 减少宽度; 列宽按 FULL/INCR 7 按钮自然宽 -->
        <el-table-column label="操作" min-width="420">
          <template slot-scope="s">
            <!-- DDL 类型: 单次操作, 不支持暂停/继续/停止 -->
            <template v-if="s.row.taskType === 'DDL'">
              <el-button size="mini" type="success"
                :disabled="s.row.status === 'RUNNING'"
                @click="onStart(s.row)" icon="el-icon-document-add">
                {{ s.row.status === 'COMPLETED' ? '再次同步' : (s.row.status === 'FAILED' ? '重试' : '同步表结构') }}
              </el-button>
              <el-button size="mini" @click="onLog(s.row)">日志</el-button>
              <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
              <el-button size="mini" type="danger" @click="onDel(s.row)">删除</el-button>
            </template>
            <template v-else>
              <!-- 启动: 运行中禁用; 完成/未启动/暂停/失败 都可点 (文案随状态变化) -->
              <el-button size="mini" type="success"
                :disabled="s.row.status === 'RUNNING'"
                @click="onStart(s.row)">
                {{ s.row.status === 'COMPLETED' ? '重新启动' : (s.row.status === 'FAILED' ? '重试' : '启动') }}
              </el-button>
              <!-- 暂停: 仅运行中可点 -->
              <el-button size="mini" :disabled="s.row.status !== 'RUNNING'" @click="onPause(s.row)">暂停</el-button>
              <!-- 继续: 仅已暂停可点 -->
              <el-button size="mini" type="warning" :disabled="s.row.status !== 'PAUSE'" @click="onResume(s.row)">继续</el-button>
              <!-- 停止: 运行中或暂停可点 -->
              <el-button size="mini" type="danger" :disabled="!['RUNNING','PAUSE'].includes(s.row.status)" @click="onStop(s.row)">停止</el-button>
              <!-- 重置: 仅 FULL 任务, 运行中不可重置 -->
              <el-button size="mini" type="info"
                :disabled="s.row.status === 'RUNNING'"
                @click="onReset(s.row)">重置</el-button>
              <el-button size="mini" @click="onLog(s.row)">日志</el-button>
              <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
              <el-button size="mini" type="danger" @click="onDel(s.row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px" background layout="prev, pager, next, total"
        :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog :title="form.id ? '编辑任务' : '新建任务'" :visible.sync="dialog" width="680px" @closed="resetForm">
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="任务名称" prop="taskName"><el-input v-model="form.taskName" /></el-form-item>
        <el-form-item label="任务类型" prop="taskType">
          <el-radio-group v-model="form.taskType">
            <el-radio-button label="FULL">全量</el-radio-button>
            <el-radio-button label="INCR">增量</el-radio-button>
            <el-radio-button label="DDL">同步表结构</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- DDL 类型: 不需要选同步模式 -->
        <template v-if="form.taskType === 'FULL'">
          <el-form-item label="同步模式" prop="syncMode">
            <el-radio-group v-model="form.syncMode">
              <el-radio-button label="ID">按主键ID</el-radio-button>
              <el-radio-button label="TIME">按时间字段</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </template>
        <template v-else-if="form.taskType === 'INCR'">
          <el-form-item label="同步模式" prop="syncMode"><el-tag>Binlog 增量</el-tag></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="同步模式"><el-tag type="warning">仅同步表结构 (DDL)</el-tag></el-form-item>
        </template>

        <el-form-item label="源数据源" prop="sourceId">
          <el-select v-model="form.sourceId" filterable style="width:100%">
            <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标数据源" prop="targetId">
          <el-select v-model="form.targetId" filterable style="width:100%">
            <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
          </el-select>
        </el-form-item>

        <el-form-item :label="form.taskType === 'DDL' ? '源表名' : '同步表名'" prop="tableName">
          <el-select v-model="form.tableName" filterable allow-create style="width:100%" placeholder="可手动输入或选择">
            <el-option v-for="t in sourceTables" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>

        <template v-if="form.taskType === 'FULL'">
          <el-form-item v-if="form.syncMode === 'ID'" label="ID字段名">
            <el-input v-model="form.idField" placeholder="默认 id" />
          </el-form-item>
          <template v-if="form.syncMode === 'TIME'">
            <el-form-item label="时间字段"><el-input v-model="form.timeField" placeholder="默认 update_time" /></el-form-item>
            <el-form-item label="起始时间"><el-date-picker v-model="form.startTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" /></el-form-item>
          </template>
          <el-form-item v-if="form.syncMode === 'ID'" label="起始ID"><el-input-number v-model="form.startId" :min="0" /></el-form-item>
          <el-form-item label="批次大小" prop="batchSize"><el-input-number v-model="form.batchSize" :min="100" :max="100000" /></el-form-item>
          <el-form-item label="覆盖数据">
            <el-switch v-model="form.overwriteFlag" :active-value="1" :inactive-value="0" />
            <span style="margin-left:8px;color:#909399;font-size:12px">开启后每次启动会先清空目标表,再全量写入</span>
          </el-form-item>
        </template>

        <template v-else-if="form.taskType === 'INCR'">
          <el-form-item label="Canal Host"><el-input v-model="form.canalHost" /></el-form-item>
          <el-form-item label="Canal Port"><el-input-number v-model="form.canalPort" :min="1" :max="65535" /></el-form-item>
          <el-form-item label="Destination"><el-input v-model="form.canalDestination" /></el-form-item>
        </template>

        <template v-else>
          <el-alert type="info" :closable="false" show-icon
            title="DDL 同步说明"
            description="启动后会把源表结构复制到目标库; 目标表不存在会自动建表, 已存在则跳过 (不会覆盖现有数据)。"/>
        </template>

        <el-form-item label="钉钉告警"><el-input v-model="form.dingtalkWebhook" placeholder="https://oapi.dingtalk.com/robot/send?access_token=xxx" /></el-form-item>
        <el-form-item label="邮件告警">
          <el-input v-model="form.alertEmail" placeholder="多个邮箱用英文逗号分隔, 如 ops@a.com,dev@b.com" />
          <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
            留空则不发送邮件; 需服务端配置 SMTP 服务器并开启 sync.mail.enabled=true 后生效
          </div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="saving">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog :title="'任务日志 [ID=' + taskLogId + ']'" :visible.sync="logDialog" width="900px" @open="loadLogs">
      <el-table :data="logPage.rows" v-loading="logLoading" border max-height="500">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="batchNo" label="批次" width="60" />
        <el-table-column prop="batchStartId" label="起始" width="130" />
        <el-table-column prop="batchEndId" label="结束" width="130" />
        <el-table-column prop="batchRows" label="行数" width="70" />
        <el-table-column prop="totalRows" label="累计" width="100" />
        <el-table-column prop="costMs" label="耗时(ms)" width="90" />
        <el-table-column prop="status" label="状态" width="80">
          <template slot-scope="s">
            <el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : 'danger'">{{ s.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
      <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
        :total="logPage.total" :page-size="logQuery.pageSize"
        :current-page.sync="logQuery.pageNum" @current-change="loadLogs" />
    </el-dialog>
  </div>
</template>

<script>
import { pageTask, addTask, updateTask, deleteTask,
         startTask, pauseTask, resumeTask, stopTask, resetTask, pageLog,
         listDataSource, listTables } from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', taskType: '', status: '', pageNum: 1, pageSize: 10, orderByColumn: 'id', isAsc: 'asc' },
      page: { rows: [], total: 0 },
      loading: false, dialog: false, saving: false,
      form: { taskType: 'FULL', syncMode: 'ID', batchSize: 1000 },
      rules: {
        taskName: [{ required: true, message: '必填' }],
        taskType: [{ required: true }],
        syncMode: [{ required: true }],
        sourceId: [{ required: true }],
        targetId: [{ required: true }],
        tableName: [{ required: true }],
        batchSize: [{ required: true }]
      },
      datasources: [], sourceTables: [],
      logDialog: false, logLoading: false, taskLogId: 0,
      logQuery: { pageNum: 1, pageSize: 10 }, logPage: { rows: [], total: 0 },
      pollTimer: null, refreshing: false
    }
  },
  watch: {
    'form.sourceId' () { this.fetchTables() }
  },
  mounted () {
    this.load()
    this.loadDatasources()
    this.startPolling()
  },
  beforeDestroy () {
    this.stopPolling()
  },
  methods: {
    load () {
      this.loading = true
      pageTask(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false)
    },
    loadDatasources () {
      listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    },
    fetchTables () {
      if (!this.form.sourceId) return
      listTables(this.form.sourceId).then(r => { this.sourceTables = r.data || [] }).catch(() => {})
    },
    onAdd (type) {
      // DDL 类型不需要 batchSize / idField / timeField, syncMode 填 'DDL' 占位即可
      const base = { taskType: type, syncMode: type === 'FULL' ? 'ID' : (type === 'DDL' ? 'DDL' : 'BINLOG'), idField: 'id', timeField: 'update_time', overwriteFlag: 0 }
      if (type !== 'DDL') base.batchSize = 1000
      this.dialog = true; this.form = base
    },
    onEdit (row) { this.dialog = true; this.form = Object.assign({}, row) },
    onSave () {
      this.$refs.form.validate(ok => {
        if (!ok) return
        // DDL 类型不需要 batchSize, 若没填则用 100 占位 (后端不依赖该值)
        if (this.form.taskType === 'DDL' && !this.form.batchSize) this.form.batchSize = 100
        this.saving = true
        const api = this.form.id ? updateTask : addTask
        api(this.form).then(() => { this.$message.success('已保存'); this.dialog = false; this.load() })
          .catch(err => { this.$message.error('保存失败:' + (err.message || '未知错误')) })
          .finally(() => this.saving = false)
      })
    },
    onDel (row) {
      this.$confirm(`确认删除任务 [${row.taskName}]?`, '提示', { type: 'warning' })
        .then(() => deleteTask(row.id))
        .then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    onStart (row)  { startTask(row.id).then(() => { this.$message.success('已启动'); this.refreshTasks() }).catch(err => { this.$message.error('启动失败:' + (err.message || '')) }) },
    onPause (row)  { pauseTask(row.id).then(() => { this.$message.success('已暂停'); this.refreshTasks() }).catch(err => { this.$message.error('暂停失败:' + (err.message || '')) }) },
    onResume (row) { resumeTask(row.id).then(() => { this.$message.success('已继续'); this.refreshTasks() }).catch(err => { this.$message.error('继续失败:' + (err.message || '')) }) },
    onStop (row)   { stopTask(row.id).then(() => { this.$message.success('已停止'); this.refreshTasks() }).catch(err => { this.$message.error('停止失败:' + (err.message || '')) }) },
    onReset (row) {
      this.$confirm(`确认重置任务 [${row.taskName}] 的同步进度?\n清空断点后下次启动会从头全量同步,历史日志保留`, '重置确认', { type: 'warning' })
        .then(() => resetTask(row.id))
        .then(() => { this.$message.success('已重置, 下次启动将从头同步'); this.refreshTasks() })
        .catch(err => {
          // 全局请求拦截器已弹错误提示,这里不再重复弹窗;仅取消时不做处理
          if (err && err !== 'cancel' && process.env.NODE_ENV !== 'production') {
            // eslint-disable-next-line no-console
            console.error('[reset]', err.message || err)
          }
        })
    },

    /**
     * 自动轮询: 只在有任务处于 RUNNING 时定时拉取最新状态
     * 状态变化后按钮(disabled / 文案)会自动重渲
     * 轮询期间使用静默刷新 (不显示顶部 loading, 避免表格闪烁)
     */
    startPolling () {
      this.stopPolling()
      this.pollTimer = setInterval(() => {
        const rows = (this.page && this.page.rows) || []
        const hasRunning = rows.some(r => r.status === 'RUNNING')
        if (hasRunning) this.refreshTasks()
      }, 3000)
    },
    stopPolling () {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    refreshTasks () {
      if (this.refreshing) return // 防并发
      this.refreshing = true
      pageTask(this.query).then(r => {
        const oldStatuses = (this.page.rows || []).reduce((m, r) => (m[r.id] = r.status, m), {})
        this.page = r.data || { rows: [], total: 0 }
        // 状态变化的提示
        ;(this.page.rows || []).forEach(r => {
          if (oldStatuses[r.id] && oldStatuses[r.id] !== r.status) {
            const map = { COMPLETED: '已完成', FAILED: '失败', RUNNING: '运行中', PAUSE: '已暂停', STOP: '已停止' }
            this.$notify({
              title: '任务状态变化',
              message: `[${r.taskName}] ${map[oldStatuses[r.id]] || oldStatuses[r.id]} → ${map[r.status] || r.status}`,
              type: r.status === 'FAILED' ? 'error' : (r.status === 'COMPLETED' ? 'success' : 'info'),
              duration: 2500
            })
          }
        })
        // 如果当前页面没有 RUNNING 任务, 停止轮询; 之后再启动一个保守的轮询器, 兜底应对新启动
        const stillRunning = (this.page.rows || []).some(r => r.status === 'RUNNING')
        if (!stillRunning) {
          this.stopPolling()
          this.startPolling()
        }
      }).catch(() => {}).finally(() => { this.refreshing = false })
    },

    onLog (row) { this.taskLogId = row.id; this.logDialog = true },
    loadLogs () {
      this.logLoading = true
      pageLog({ ...this.logQuery, taskId: this.taskLogId }).then(r => { this.logPage = r.data }).catch(() => {}).finally(() => this.logLoading = false)
    },

    /**
     * 列头排序: 后端白名单仅 id / create_time, 点击升降切换, 第三次取消回到默认 (id asc)
     */
    onSortChange ({ prop, order }) {
      if (!order) {
        // 第三次点同列: 取消排序, 回到默认 id asc
        this.query.orderByColumn = 'id'
        this.query.isAsc = 'asc'
      } else {
        this.query.orderByColumn = prop
        this.query.isAsc = order === 'ascending' ? 'asc' : 'desc'
      }
      this.query.pageNum = 1
      this.load()
    },
    /**
     * 时间字段格式化: 后端 Date 序列化为 long 时间戳或 ISO 字符串, 统一转 yyyy-MM-dd HH:mm:ss
     */
    fmtTime (t) {
      if (!t) return '-'
      const d = new Date(t)
      if (isNaN(d.getTime())) return '-'
      const p = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },

    resetForm () { this.form = {} },
    modeName (m) { return ({ ID: '按ID', TIME: '按时间', BINLOG: 'Binlog', DDL: '表结构' })[m] || m },
    statusName (s) { return ({ STOP: '未启动', RUNNING: '运行中', PAUSE: '已暂停', COMPLETED: '已完成', FAILED: '失败' })[s] || s },
    statusType (s) { return ({ STOP: 'info', RUNNING: 'success', PAUSE: 'warning', COMPLETED: '', FAILED: 'danger' })[s] || '' },
    taskTypeName (t) { return ({ FULL: '全量', INCR: '增量', DDL: '表结构' })[t] || t },
    taskTypeTag (t) { return ({ FULL: '', INCR: 'success', DDL: 'warning' })[t] || '' }
  }
}
</script>

<style scoped>
/* 同步任务列表 - 操作列按钮紧凑: 去 icon 后 padding 收紧, 间距缩小, 整列不换行 */
.sync-task-table >>> .el-table .cell .el-button--mini {
  padding: 5px 8px;
  font-size: 12px;
}
/* 操作列: 强制整行不换行, 按钮用 flex 两端对齐铺满整列, 消除右侧空白 */
.sync-task-table >>> .el-table td:last-child .cell {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  white-space: nowrap;
  padding-left: 8px;
  padding-right: 8px;
}
/* space-between 已自动分布, 不再额外加按钮间距 */
</style>
