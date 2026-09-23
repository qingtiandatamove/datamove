<template>
  <el-card>
    <div slot="header" class="head">
      <span>审计日志</span>
      <span class="head-tip">谁在什么时候改了哪个任务的哪个字段 (字段级, 企业合规审计必备)</span>
    </div>

    <!-- 查询条件 -->
    <el-form :inline="true" :model="query" size="small">
      <el-form-item>
        <el-select v-model="query.opType" clearable placeholder="操作类型" style="width:130px">
          <el-option label="新增" value="CREATE" />
          <el-option label="修改" value="UPDATE" />
          <el-option label="删除" value="DELETE" />
          <el-option label="启动" value="START" />
          <el-option label="暂停" value="PAUSE" />
          <el-option label="继续" value="RESUME" />
          <el-option label="停止" value="STOP" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input v-model="query.keyword" placeholder="任务名 / 字段名 / 操作人 / IP / 旧值 / 新值" clearable
          style="width:280px" @keyup.enter.native="onSearch" />
      </el-form-item>
      <el-form-item>
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="~"
          start-placeholder="开始时间" end-placeholder="结束时间" value-format="yyyy-MM-dd HH:mm:ss"
          style="width:340px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="onSearch">查询</el-button>
        <el-button icon="el-icon-refresh" @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 列表 -->
    <el-table :data="page.rows" v-loading="loading" border size="small"
      :row-class-name="rowClass"
      @row-click="onRowClick"
      style="cursor:pointer">
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column label="操作人" width="140" show-overflow-tooltip>
        <template slot-scope="s">
          <div>{{ s.row.operatorName || '-' }}</div>
          <div class="cell-sub">{{ s.row.ip || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template slot-scope="s">
          <el-tag size="mini" :type="opTypeTag(s.row.opType)">{{ opTypeLabel(s.row.opType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="任务" width="180" show-overflow-tooltip>
        <template slot-scope="s">
          <div>{{ s.row.entityName || ('任务 #' + s.row.entityId) }}</div>
          <div class="cell-sub">id={{ s.row.entityId }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="fieldName" label="字段" width="140" show-overflow-tooltip />
      <el-table-column label="变更 (旧 → 新)" min-width="320">
        <template slot-scope="s">
          <span class="old-val" :title="s.row.oldValue || ''">{{ displayVal(s.row.oldValue) }}</span>
          <i class="el-icon-right arrow"></i>
          <span class="new-val" :title="s.row.newValue || ''">{{ displayVal(s.row.newValue) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="同次请求" width="90">
        <template slot-scope="s">
          <el-button size="mini" type="text" @click.stop="openRevision(s.row)">查看整次</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:16px" background layout="total, prev, pager, next, sizes"
      :total="page.total" :page-size.sync="query.pageSize" :current-page.sync="query.pageNum"
      :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSizeChange" />

    <!-- 单条详情 -->
    <el-dialog title="审计详情" :visible.sync="detailVisible" width="780px">
      <div v-if="detail" class="detail">
        <div class="d-row"><span class="d-label">时间</span><span>{{ detail.createTime }}</span></div>
        <div class="d-row"><span class="d-label">操作人</span><span>{{ detail.operatorName || '-' }} (ID {{ detail.operatorId || '-' }})</span></div>
        <div class="d-row"><span class="d-label">IP</span><span>{{ detail.ip || '-' }}</span></div>
        <div class="d-row"><span class="d-label">客户端</span><span class="d-ua">{{ detail.userAgent || '-' }}</span></div>
        <div class="d-row"><span class="d-label">操作</span><span>
          <el-tag size="mini" :type="opTypeTag(detail.opType)">{{ opTypeLabel(detail.opType) }}</el-tag>
        </span></div>
        <div class="d-row"><span class="d-label">任务</span><span>{{ detail.entityName || '-' }} (id={{ detail.entityId }})</span></div>
        <div class="d-row"><span class="d-label">字段</span><span>{{ detail.fieldName }}</span></div>
        <div class="d-row"><span class="d-label">旧值</span><pre class="val-pre old">{{ detail.oldValue == null ? '(空)' : detail.oldValue }}</pre></div>
        <div class="d-row"><span class="d-label">新值</span><pre class="val-pre new">{{ detail.newValue == null ? '(空)' : detail.newValue }}</pre></div>
        <div class="d-row"><span class="d-label">同次请求</span><span>
          <el-button size="mini" @click="openRevision(detail)">查看本次审计所有字段变更</el-button>
        </span></div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>

    <!-- 整次请求 (同 revision_id) 的全部字段变更 -->
    <el-dialog :title="revisionDialogTitle" :visible.sync="revisionVisible" width="820px">
      <div v-if="revisionRows.length" class="rev-list">
        <div class="rev-meta">
          <el-tag size="mini" :type="opTypeTag(revisionRows[0].opType)">{{ opTypeLabel(revisionRows[0].opType) }}</el-tag>
          <span class="rev-meta-text">
            <b>{{ revisionRows[0].operatorName || '-' }}</b>
            ({{ revisionRows[0].ip || '-' }})
            于 {{ revisionRows[0].createTime }} 修改
            <b>{{ revisionRows[0].entityName || ('任务 #' + revisionRows[0].entityId) }}</b>
            ({{ revisionRows[0].entityId }}), 共 {{ revisionRows.length }} 个字段变更
          </span>
        </div>
        <el-table :data="revisionRows" size="small" border>
          <el-table-column prop="fieldName" label="字段" width="140" />
          <el-table-column label="旧值" min-width="200" show-overflow-tooltip>
            <template slot-scope="s"><span class="old-val">{{ displayVal(s.row.oldValue) }}</span></template>
          </el-table-column>
          <el-table-column label="新值" min-width="200" show-overflow-tooltip>
            <template slot-scope="s"><span class="new-val">{{ displayVal(s.row.newValue) }}</span></template>
          </el-table-column>
        </el-table>
      </div>
      <div v-else class="empty">加载中…</div>
      <div slot="footer">
        <el-button size="small" @click="revisionVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageAudit, auditRevision } from '@/api/datamove'

export default {
  data () {
    return {
      timeRange: [],
      query: { opType: '', keyword: '', pageNum: 1, pageSize: 20 },
      page: { rows: [], total: 0 },
      loading: false,
      detail: null,
      detailVisible: false,
      revisionRows: [],
      revisionVisible: false,
      revisionDialogTitle: ''
    }
  },
  mounted () {
    this.load()
  },
  methods: {
    cond () {
      return {
        opType: this.query.opType,
        keyword: this.query.keyword,
        beginTime: this.timeRange && this.timeRange[0] ? this.timeRange[0] : '',
        endTime: this.timeRange && this.timeRange[1] ? this.timeRange[1] : ''
      }
    },
    load () {
      this.loading = true
      pageAudit(Object.assign({}, this.cond(), { pageNum: this.query.pageNum, pageSize: this.query.pageSize }))
        .then(r => { this.page = r.data || { rows: [], total: 0 } })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },
    onSearch () { this.query.pageNum = 1; this.load() },
    onReset () {
      const size = this.query.pageSize
      this.query = { opType: '', keyword: '', pageNum: 1, pageSize: size }
      this.timeRange = []
      this.load()
    },
    onSizeChange () { this.query.pageNum = 1; this.load() },
    onRowClick (row) {
      this.detail = row
      this.detailVisible = true
    },
    openRevision (row) {
      if (!row || !row.revisionId) return
      this.revisionDialogTitle = `本次请求 (revision #${row.revisionId}) 的全部字段变更`
      this.revisionVisible = true
      this.revisionRows = []
      auditRevision(row.revisionId)
        .then(r => { this.revisionRows = (r && r.data) || [] })
        .catch(() => { this.revisionRows = [] })
    },
    /* 操作类型 → 标签色 */
    opTypeTag (t) {
      if (t === 'CREATE') return 'success'
      if (t === 'DELETE') return 'danger'
      if (t === 'UPDATE') return 'warning'
      if (t === 'START') return 'success'
      if (t === 'STOP')  return 'info'
      if (t === 'PAUSE') return 'warning'
      if (t === 'RESUME') return 'success'
      return 'info'
    },
    opTypeLabel (t) {
      if (t === 'CREATE') return '新增'
      if (t === 'DELETE') return '删除'
      if (t === 'UPDATE') return '修改'
      if (t === 'START') return '启动'
      if (t === 'STOP')  return '停止'
      if (t === 'PAUSE') return '暂停'
      if (t === 'RESUME') return '继续'
      return t || '-'
    },
    /* 行底色: 删除偏红, 新增偏绿 */
    rowClass ({ row }) {
      if (row.opType === 'DELETE') return 'del-row'
      if (row.opType === 'CREATE') return 'create-row'
      return ''
    },
    /* 显示值: 空值显示为占位 */
    displayVal (v) {
      if (v === null || v === undefined || v === '') return '(空)'
      return v
    }
  }
}
</script>

<style scoped>
.head-tip { float: right; font-size: 12px; font-weight: normal; color: #909399 }
.cell-sub { font-size: 11px; color: #909399; line-height: 1.2 }
.arrow { color: #c0c4cc; margin: 0 6px }
.old-val { color: #f56c6c; text-decoration: line-through; font-family: Consolas, Menlo, monospace }
.new-val { color: #67c23a; font-family: Consolas, Menlo, monospace }

/* 行底色: 极淡, 不抢戏 */
>>> .create-row { background-color: #f0f9eb !important }
>>> .del-row    { background-color: #fef0f0 !important }

.detail .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: #333; align-items: flex-start }
.detail .d-label { width: 76px; flex: none; color: #909399 }
.d-ua { color: #909399; font-size: 12px; word-break: break-all }
.val-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 220px; overflow: auto;
  border: 1px solid #ebeef5; border-radius: 4px;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.val-pre.old { background: #fef0f0; border-color: #fde2e2; color: #f56c6c }
.val-pre.new { background: #f0f9eb; border-color: #e1f3d8; color: #67c23a }

.rev-meta { margin-bottom: 12px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap }
.rev-meta-text { color: #606266; font-size: 13px }
.rev-meta b { color: #303133 }
.empty { padding: 30px; text-align: center; color: #909399 }
</style>