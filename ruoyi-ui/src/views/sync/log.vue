<template>
  <div>
    <!-- 概览: 当前筛选条件下的统计 -->
    <el-row :gutter="12" class="stat-row">
      <el-col :span="4" v-for="c in statCards" :key="c.label">
        <el-card shadow="hover" class="stat-card" :body-style="{ padding: '14px 16px' }">
          <div class="stat-val" :style="{ color: c.color }">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <div slot="header">
        <el-form :inline="true" size="mini" :model="query" class="q-form" @submit.native.prevent>
          <el-form-item>
            <el-input v-model.trim="query.taskId" placeholder="任务ID" clearable style="width:100px" @keyup.enter.native="onSearch" />
          </el-form-item>
          <el-form-item>
            <el-input v-model.trim="query.taskName" placeholder="任务名称(模糊)" clearable style="width:150px" @keyup.enter.native="onSearch" />
          </el-form-item>
          <el-form-item>
            <el-input v-model.trim="query.tableName" placeholder="表名(模糊)" clearable style="width:140px" @keyup.enter.native="onSearch" />
          </el-form-item>
          <el-form-item>
            <el-select v-model="query.status" clearable placeholder="状态" style="width:110px">
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
              <el-option label="运行中" value="RUNNING" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-select v-model="query.shardNo" clearable placeholder="全部分片" style="width:110px">
              <el-option v-for="n in 16" :key="n" :label="'分片 S' + n" :value="n" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-input v-model.trim="query.batchNo" placeholder="批次号" clearable style="width:90px" @keyup.enter.native="onSearch" />
          </el-form-item>
          <el-form-item>
            <el-date-picker
              v-model="timeRange"
              type="datetimerange"
              size="mini"
              range-separator="~"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="yyyy-MM-dd HH:mm:ss"
              :default-time="['00:00:00', '23:59:59']"
              style="width:330px" />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model.trim="query.keyword"
              placeholder="关键字: 异常/同步内容/位点"
              clearable
              style="width:200px"
              @keyup.enter.native="onSearch" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="el-icon-search" @click="onSearch">查询</el-button>
            <el-button icon="el-icon-refresh" @click="onReset">重置</el-button>
            <el-button type="success" icon="el-icon-download" @click="onExport">导出CSV</el-button>
            <el-switch v-model="autoRefresh" active-text="自动刷新" style="margin-left:6px" @change="onAutoChange" />
          </el-form-item>
        </el-form>
        <div class="quick-bar">
          <span class="quick-label">快捷:</span>
          <el-tag size="mini" :effect="quick === 'ALL' ? 'dark' : 'plain'" class="quick-tag" @click="setQuick('ALL')">全部</el-tag>
          <el-tag size="mini" type="danger" :effect="quick === 'FAILED' ? 'dark' : 'plain'" class="quick-tag" @click="setQuick('FAILED')">只看失败</el-tag>
          <el-tag size="mini" type="warning" :effect="quick === 'ERROR' ? 'dark' : 'plain'" class="quick-tag" @click="setQuick('ERROR')">含异常信息</el-tag>
          <el-tag size="mini" type="success" :effect="quick === 'RUNNING' ? 'dark' : 'plain'" class="quick-tag" @click="setQuick('RUNNING')">运行中</el-tag>
          <span class="quick-tip">双击行可查看完整同步内容 / 异常堆栈</span>
        </div>
      </div>

      <el-table
        :data="page.rows"
        v-loading="loading"
        border
        size="small"
        :row-class-name="rowClass"
        @row-dblclick="showDetail">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="taskId" label="任务ID" width="76" />
        <el-table-column prop="taskName" label="任务名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="tableName" label="表" min-width="130" show-overflow-tooltip />
        <el-table-column label="模式" width="72" align="center">
          <template slot-scope="s">
            <el-tag size="mini" effect="plain">{{ modeName(s.row.syncMode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="批次" width="64" align="center">
          <template slot-scope="s"><span class="mono">{{ s.row.batchNo }}</span></template>
        </el-table-column>
        <el-table-column label="分片" width="64" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.shardNo" size="mini" type="warning" effect="plain">S{{ s.row.shardNo }}</el-tag>
            <span v-else class="empty-cell">-</span>
          </template>
        </el-table-column>
        <el-table-column label="位点 (起 ~ 止)" min-width="190">
          <template slot-scope="s">
            <span class="mono">{{ s.row.batchStartId || '-' }} <i class="arrow">~</i> {{ s.row.batchEndId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="84" align="right">
          <template slot-scope="s">{{ num(s.row.batchRows) }}</template>
        </el-table-column>
        <el-table-column label="累计" width="100" align="right">
          <template slot-scope="s">{{ num(s.row.totalRows) }}</template>
        </el-table-column>
        <el-table-column label="行/秒" width="84" align="right">
          <template slot-scope="s"><span class="mono">{{ rowRate(s.row) }}</span></template>
        </el-table-column>
        <el-table-column label="耗时" width="92" align="right">
          <template slot-scope="s">{{ fmtMs(s.row.costMs) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ s.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="同步内容" min-width="200">
          <template slot-scope="s">
            <span v-if="s.row.content" class="content-cell" @click="showDetail(s.row)">{{ oneLine(s.row.content) }}</span>
            <span v-else class="empty-cell">-</span>
          </template>
        </el-table-column>
        <el-table-column label="异常信息" min-width="170">
          <template slot-scope="s">
            <span v-if="s.row.errorMsg" class="err-cell" @click="showDetail(s.row)">
              <i class="el-icon-warning-outline" /> {{ oneLine(s.row.errorMsg) }}
            </span>
            <span v-else class="empty-cell">-</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template slot-scope="s">{{ fmtTime(s.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right">
          <template slot-scope="s">
            <el-button size="mini" @click="showDetail(s.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="page.total"
        :page-sizes="[20, 50, 100, 200]"
        :page-size="query.pageSize"
        :current-page.sync="query.pageNum"
        @current-change="load"
        @size-change="onSizeChange" />
    </el-card>

    <!-- 批次详情: 展示本批次同步的数据内容 -->
    <el-dialog title="同步批次详情" :visible.sync="detailVisible" width="820px">
      <div v-if="detail" class="detail">
        <div class="d-row"><span class="d-label">时间</span><span>{{ fmtTime(detail.createTime) }}</span></div>
        <div class="d-row"><span class="d-label">任务</span><span>{{ detail.taskName }} (ID {{ detail.taskId }})</span></div>
        <div class="d-row">
          <span class="d-label">表 / 模式</span>
          <span>
            {{ detail.tableName }} · {{ modeName(detail.syncMode) }}
            <el-tag v-if="detail.shardNo" size="mini" type="warning" effect="plain" style="margin-left:6px">分片 S{{ detail.shardNo }}</el-tag>
          </span>
        </div>
        <div class="d-row">
          <span class="d-label">执行结果</span>
          <span>
            <el-tag size="mini" :type="statusType(detail.status)">{{ detail.status }}</el-tag>
            <span class="d-meta">
              第 {{ detail.batchNo }} 批 · 本批 {{ num(detail.batchRows) }} 行 · 累计 {{ num(detail.totalRows) }} 行
              · 耗时 {{ fmtMs(detail.costMs) }} · 速率 {{ rowRate(detail) }} 行/秒
            </span>
          </span>
        </div>
        <div class="d-row"><span class="d-label">位点</span><span class="d-ua">{{ detail.batchStartId || '-' }} ~ {{ detail.batchEndId || '-' }}</span></div>
        <div class="d-row">
          <span class="d-label">同步内容</span>
          <pre class="content-pre">{{ detail.content || '无(该批次无数据变更)' }}</pre>
        </div>
        <div v-if="detail.errorMsg" class="d-row">
          <span class="d-label">异常信息</span>
          <pre class="err-pre">{{ detail.errorMsg }}</pre>
        </div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="copyDetail">复制详情</el-button>
        <el-button size="small" type="primary" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { pageLog, logSummary, exportLogUrl } from '@/api/datamove'

const EMPTY_SUMMARY = {
  total: 0, successCount: 0, failedCount: 0, runningCount: 0,
  rowsSum: 0, avgCostMs: 0, maxCostMs: 0, errorCount: 0
}

export default {
  data () {
    return {
      query: {
        taskId: '', taskName: '', tableName: '', status: '',
        shardNo: null, batchNo: '', keyword: '', hasError: null,
        pageNum: 1, pageSize: 20
      },
      timeRange: null,
      page: { rows: [], total: 0 },
      summary: { ...EMPTY_SUMMARY },
      loading: false,
      autoRefresh: false,
      timer: null,
      refreshing: false,
      quick: 'ALL',
      detail: null,
      detailVisible: false
    }
  },
  computed: {
    statCards () {
      const s = this.summary
      return [
        { label: '日志条数', value: this.num(s.total), color: '#303133' },
        { label: '成功', value: this.num(s.successCount), color: '#67C23A' },
        { label: '失败', value: this.num(s.failedCount), color: '#F56C6C' },
        { label: '同步行数', value: this.num(s.rowsSum), color: '#409EFF' },
        { label: '平均单批耗时', value: this.fmtMs(s.avgCostMs), color: '#E6A23C' },
        { label: '含异常信息', value: this.num(s.errorCount), color: '#F56C6C' }
      ]
    }
  },
  watch: {
    // 手动改状态下拉 / 筛选时, 快捷标签高亮跟随
    'query.status' (v) {
      if (v === 'FAILED') this.quick = 'FAILED'
      else if (v === 'RUNNING') this.quick = 'RUNNING'
      else if (!this.query.hasError) this.quick = 'ALL'
    },
    'query.hasError' (v) {
      if (v) this.quick = 'ERROR'
      else if (this.query.status === 'FAILED') this.quick = 'FAILED'
      else if (this.query.status === 'RUNNING') this.quick = 'RUNNING'
      else this.quick = 'ALL'
    }
  },
  mounted () {
    this.load()
  },
  beforeDestroy () {
    this.clearTimer()
  },
  methods: {
    /* ---------- 查询 ---------- */
    buildParams () {
      const p = { pageNum: this.query.pageNum, pageSize: this.query.pageSize }
      const add = (k, v) => { if (v !== '' && v !== null && v !== undefined) p[k] = v }
      add('taskId', this.query.taskId)
      add('taskName', this.query.taskName)
      add('tableName', this.query.tableName)
      add('status', this.query.status)
      add('shardNo', this.query.shardNo)
      add('batchNo', this.query.batchNo)
      add('keyword', this.query.keyword)
      if (this.query.hasError) p.hasError = true
      if (this.timeRange && this.timeRange.length === 2) {
        add('beginTime', this.timeRange[0])
        add('endTime', this.timeRange[1])
      }
      return p
    },
    filterParams () {
      const p = this.buildParams()
      delete p.pageNum
      delete p.pageSize
      return p
    },
    load () {
      if (this.refreshing) return
      this.refreshing = true
      this.loading = true
      const params = this.buildParams()
      Promise.all([
        pageLog(params).catch(() => null),
        logSummary(params).catch(() => null)
      ]).then(([p, s]) => {
        if (p && p.data) this.page = p.data
        if (s && s.data) this.summary = s.data
        this.$nextTick(this.schedule)
      }).finally(() => {
        this.loading = false
        this.refreshing = false
      })
    },
    onSearch () {
      this.query.pageNum = 1
      this.clearTimer()
      this.load()
    },
    onReset () {
      const size = this.query.pageSize
      this.query = {
        taskId: '', taskName: '', tableName: '', status: '',
        shardNo: null, batchNo: '', keyword: '', hasError: null,
        pageNum: 1, pageSize: size
      }
      this.timeRange = null
      this.quick = 'ALL'
      this.load()
    },
    onSizeChange (size) {
      this.query.pageSize = size
      this.query.pageNum = 1
      this.load()
    },
    setQuick (type) {
      const q = this.query
      q.hasError = null
      q.status = ''
      if (type === 'FAILED') {
        q.status = 'FAILED'
      } else if (type === 'RUNNING') {
        q.status = 'RUNNING'
      } else if (type === 'ERROR') {
        q.hasError = true
      }
      this.quick = type
      this.onSearch()
    },
    onExport () {
      window.open(exportLogUrl(this.filterParams()))
    },

    /* ---------- 自动刷新(有运行中日志时 5s) ---------- */
    schedule () {
      this.clearTimer()
      if (!this.autoRefresh) return
      const hasRunning = (this.page.rows || []).some(r => r.status === 'RUNNING')
      this.timer = setTimeout(() => { this.load() }, hasRunning ? 5000 : 15000)
    },
    clearTimer () {
      if (this.timer) { clearTimeout(this.timer); this.timer = null }
    },
    onAutoChange (v) {
      if (v) this.schedule()
      else this.clearTimer()
    },

    /* ---------- 展示格式化 ---------- */
    num (v) {
      if (v === null || v === undefined || v === '') return '0'
      const n = Number(v)
      return isNaN(n) ? String(v) : n.toLocaleString('en-US')
    },
    fmtMs (v) {
      if (v === null || v === undefined) return '—'
      return Number(v).toLocaleString('en-US') + 'ms'
    },
    rowRate (row) {
      const rows = Number(row && row.batchRows) || 0
      const cost = Number(row && row.costMs) || 0
      if (!rows || cost <= 0) return '—'
      const r = rows * 1000 / cost
      return r >= 100 ? Math.round(r).toLocaleString('en-US') : r.toFixed(1)
    },
    fmtTime (t) {
      if (!t) return '-'
      const d = new Date(t)
      if (isNaN(d.getTime())) return String(t)
      const p = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    modeName (m) {
      return ({ ID: '按主键', TIME: '按时间', BINLOG: '增量binlog', FULL: '全量', INCR: '增量' })[m] || m || '-'
    },
    statusType (s) {
      return ({ SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning' })[s] || ''
    },
    rowClass ({ row }) {
      return row.status === 'FAILED' ? 'failed-row' : ''
    },
    /* 列表里内容压成一行展示 */
    oneLine (text) {
      if (!text) return ''
      const one = String(text).replace(/\s+/g, ' ').trim()
      return one.length > 60 ? one.slice(0, 60) + '...' : one
    },
    showDetail (row) {
      this.detail = row
      this.detailVisible = true
    },
    copyDetail () {
      const d = this.detail || {}
      const text = [
        `时间: ${this.fmtTime(d.createTime)}`,
        `任务: ${d.taskName} (ID ${d.taskId})`,
        `表 / 模式: ${d.tableName} · ${this.modeName(d.syncMode)}`,
        `结果: ${d.status} 第 ${d.batchNo} 批${d.shardNo ? ' 分片S' + d.shardNo : ''}`,
        `本批 ${d.batchRows} 行 / 累计 ${d.totalRows} 行 / 耗时 ${d.costMs}ms`,
        `位点: ${d.batchStartId} ~ ${d.batchEndId}`,
        `同步内容: ${d.content || '无'}`,
        d.errorMsg ? `异常信息: ${d.errorMsg}` : ''
      ].filter(Boolean).join('\n')
      const done = () => this.$message.success('已复制到剪贴板')
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(done).catch(() => this.fallbackCopy(text, done))
      } else {
        this.fallbackCopy(text, done)
      }
    },
    fallbackCopy (text, done) {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy'); done() } catch (e) { this.$message.warning('复制失败, 请手动选择') }
      document.body.removeChild(ta)
    }
  }
}
</script>

<style scoped>
.stat-row { margin-bottom: 12px }
.stat-card { text-align: center }
.stat-val { font-size: 22px; font-weight: 600; line-height: 1.2 }
.stat-label { color: #909399; font-size: 12px; margin-top: 6px }
.q-form .el-form-item { margin-bottom: 6px; margin-right: 10px }
.quick-bar { display: flex; align-items: center; gap: 6px; padding-top: 6px; border-top: 1px dashed #ebeef5 }
.quick-label { color: #909399; font-size: 12px }
.quick-tag { cursor: pointer }
.quick-tip { color: #c0c4cc; font-size: 12px; margin-left: 8px }

.mono { font-family: Consolas, Menlo, monospace; font-size: 12px }
.arrow { color: #c0c4cc; font-style: normal }
.content-cell { cursor: pointer; color: #1890ff; font-family: Consolas, Menlo, monospace; font-size: 12px }
.err-cell { cursor: pointer; color: #f56c6c; font-family: Consolas, Menlo, monospace; font-size: 12px }
.empty-cell { color: #c0c4cc }

.detail .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: #333 }
.detail .d-label { width: 76px; flex: none; color: #909399 }
.d-meta { margin-left: 8px; color: #606266; font-size: 12px }
.d-ua { color: #909399; font-size: 12px; word-break: break-all }
.content-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 360px; overflow: auto;
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 4px;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.err-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 160px; overflow: auto;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px; color: #f56c6c;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
/deep/ .el-table .failed-row td { background: #fef0f0 !important }
</style>
