<template>
  <el-card>
    <div slot="header" class="head">
      <span>SQL 执行日志</span>
      <span class="head-tip">记录 SQL 工作台对数据源的每一次操作: SQL / 结果 / 耗时 / 操作人</span>
    </div>

    <!-- 查询条件 -->
    <el-form :inline="true" :model="query" size="small">
      <el-form-item>
        <el-select v-model="query.dsId" filterable clearable placeholder="全部数据源" style="width:210px">
          <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.sourceType" clearable placeholder="来源" style="width:130px">
          <el-option label="SQL 工作台" value="SQL_CONSOLE" />
          <el-option label="数据中心" value="DATA_BROWSE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.status" clearable placeholder="状态" style="width:110px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input v-model="query.keyword" placeholder="SQL / 操作人 / 错误" clearable
          style="width:200px" @keyup.enter.native="onSearch" />
      </el-form-item>
      <el-form-item>
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="~"
          start-placeholder="开始时间" end-placeholder="结束时间" value-format="yyyy-MM-dd HH:mm:ss"
          style="width:340px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" @click="onSearch">查询</el-button>
        <el-button icon="el-icon-refresh" @click="onReset">重置</el-button>
        <el-button type="success" icon="el-icon-download" @click="onExport">导出CSV</el-button>
      </el-form-item>
    </el-form>

    <!-- 日志列表 -->
    <el-table :data="page.rows" v-loading="loading" border size="small"
      :row-class-name="({ row }) => row.status === 'FAILED' ? 'fail-row' : ''">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="createTime" label="时间" width="150" />
      <el-table-column prop="operName" label="操作人" width="90" show-overflow-tooltip />
      <el-table-column prop="dsName" label="数据源" width="140" show-overflow-tooltip />
      <el-table-column prop="dbName" label="数据库" min-width="110" show-overflow-tooltip />
      <el-table-column label="来源" width="100">
        <template slot-scope="s">{{ sourceLabel(s.row.sourceType) }}</template>
      </el-table-column>
      <el-table-column label="SQL" min-width="260" show-overflow-tooltip>
        <template slot-scope="s">
          <span class="sql-cell" @click="showDetail(s.row)">{{ oneLine(s.row.sqlText) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="stmtCount" label="语句" width="60" />
      <el-table-column prop="resultRows" label="结果行" width="70" />
      <el-table-column prop="affectedRows" label="影响行" width="70" />
      <el-table-column label="耗时" width="80">
        <template slot-scope="s">{{ s.row.costMs }}ms</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template slot-scope="s">
          <el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : 'danger'">{{ s.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template slot-scope="s">
          <el-button size="mini" @click="showDetail(s.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:16px" background layout="total, prev, pager, next, sizes"
      :total="page.total" :page-size.sync="query.pageSize" :current-page.sync="query.pageNum"
      :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSizeChange" />

    <!-- 详情弹窗 -->
    <el-dialog title="执行详情" :visible.sync="detailVisible" width="780px">
      <div v-if="detail" class="detail">
        <div class="d-row"><span class="d-label">执行时间</span><span>{{ detail.createTime }}</span></div>
        <div class="d-row"><span class="d-label">操作人</span><span>{{ detail.operName }} ({{ detail.operIp || '-' }})</span></div>
        <div class="d-row"><span class="d-label">数据源</span><span>{{ detail.dsName }} / {{ detail.dbName }}</span></div>
        <div class="d-row"><span class="d-label">来源</span><span>{{ sourceLabel(detail.sourceType) }}</span></div>
        <div class="d-row">
          <span class="d-label">执行结果</span>
          <span>
            <el-tag size="mini" :type="detail.status === 'SUCCESS' ? 'success' : 'danger'">{{ detail.status }}</el-tag>
            <span class="d-meta">语句 {{ detail.stmtCount }} 条 · 结果 {{ detail.resultRows }} 行 · 影响 {{ detail.affectedRows }} 行 · 耗时 {{ detail.costMs }}ms</span>
          </span>
        </div>
        <div class="d-row"><span class="d-label">客户端</span><span class="d-ua">{{ detail.clientInfo || '-' }}</span></div>
        <div class="d-row">
          <span class="d-label">SQL</span>
          <pre class="sql-pre">{{ detail.sqlText }}</pre>
        </div>
        <div v-if="detail.errorMsg" class="d-row">
          <span class="d-label">错误信息</span>
          <pre class="err-pre">{{ detail.errorMsg }}</pre>
        </div>
      </div>
      <div slot="footer">
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageSqlLog, exportSqlLogUrl, listDataSource } from '@/api/datamove'

export default {
  data () {
    return {
      datasources: [],
      timeRange: [],
      query: { dsId: null, sourceType: '', status: '', keyword: '', pageNum: 1, pageSize: 20 },
      page: { rows: [], total: 0 },
      loading: false,
      detail: null,
      detailVisible: false
    }
  },
  mounted () {
    listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    this.load()
  },
  methods: {
    /* 组装查询条件(不含分页) */
    cond () {
      return {
        dsId: this.query.dsId,
        sourceType: this.query.sourceType,
        status: this.query.status,
        keyword: this.query.keyword,
        beginTime: this.timeRange && this.timeRange[0] ? this.timeRange[0] : '',
        endTime: this.timeRange && this.timeRange[1] ? this.timeRange[1] : ''
      }
    },
    load () {
      this.loading = true
      pageSqlLog(Object.assign({}, this.cond(), { pageNum: this.query.pageNum, pageSize: this.query.pageSize }))
        .then(r => { this.page = r.data || { rows: [], total: 0 } })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },
    onSearch () { this.query.pageNum = 1; this.load() },
    onReset () {
      const size = this.query.pageSize
      this.query = { dsId: null, sourceType: '', status: '', keyword: '', pageNum: 1, pageSize: size }
      this.timeRange = []
      this.load()
    },
    onSizeChange () { this.query.pageNum = 1; this.load() },
    onExport () { window.open(exportSqlLogUrl(this.cond())) },
    sourceLabel (t) {
      if (t === 'DATA_BROWSE') return '数据中心'
      if (t === 'SQL_CONSOLE') return 'SQL 工作台'
      return t || '-'
    },
    /* 列表里 SQL 压成一行展示 */
    oneLine (sql) {
      if (!sql) return ''
      const one = sql.replace(/\s+/g, ' ')
      return one.length > 200 ? one.slice(0, 200) + '...' : one
    },
    showDetail (row) {
      this.detail = row
      this.detailVisible = true
    }
  }
}
</script>

<style scoped>
.head-tip { float: right; font-size: 12px; font-weight: normal; color: #909399 }
.sql-cell { cursor: pointer; color: #1890ff; font-family: Consolas, Menlo, monospace }
.detail .d-row { display: flex; margin-bottom: 10px; font-size: 13px; color: #333 }
.detail .d-label { width: 76px; flex: none; color: #909399 }
.d-meta { margin-left: 8px; color: #606266; font-size: 12px }
.d-ua { color: #909399; font-size: 12px; word-break: break-all }
.sql-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 320px; overflow: auto;
  background: #f5f7fa; border: 1px solid #ebeef5; border-radius: 4px;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.err-pre {
  flex: 1; margin: 0; padding: 10px; max-height: 160px; overflow: auto;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px; color: #f56c6c;
  font-family: Consolas, Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
</style>
