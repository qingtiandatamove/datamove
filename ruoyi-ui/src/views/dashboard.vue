<template>
  <div class="home">
    <!-- ============ 顶部欢迎 ============ -->
    <div class="hero">
      <div class="hero-left">
        <div class="hero-title">
          <i class="el-icon-data-analysis"></i>
          DataMove 数据同步工具
        </div>
        <div class="hero-sub">
          欢迎回来, <b>{{ userName }}</b>　·　{{ greet }}　·　{{ nowStr }}
        </div>
      </div>
      <div class="hero-right">
        <template v-if="lic.loaded">
          <div class="lic-card" :class="lic.cls">
            <i class="el-icon-key"></i>
            <div>
              <div class="lic-label">授权状态</div>
              <div class="lic-text">
                <b>{{ lic.statusText }}</b>
                <span v-if="lic.expireText">　·　{{ lic.expireText }}</span>
              </div>
            </div>
          </div>
          <el-button size="small" icon="el-icon-position" plain class="hero-btn"
                     @click="$router.push('/sync/license')">授权管理</el-button>
        </template>
      </div>
    </div>

    <!-- ============ 核心指标 ============ -->
    <el-row :gutter="12" class="row">
      <el-col :xs="12" :sm="6" :md="6" v-for="c in statCards" :key="c.label">
        <div class="stat-card" :class="'tone-' + c.tone">
          <div class="stat-icon"><i :class="c.icon"></i></div>
          <div class="stat-body">
            <div class="stat-val">{{ c.value }}</div>
            <div class="stat-label">{{ c.label }}</div>
            <div v-if="c.hint" class="stat-hint">{{ c.hint }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 同步趋势 ============ -->
    <el-card shadow="never" class="block">
      <div slot="header" class="clearfix">
        <span><b>近 {{ trendDays }} 天同步趋势</b>
          <span class="muted" v-if="trendLoaded">　合计 {{ trendTotal }} 行, 失败 {{ trendFailed }} 批</span>
        </span>
        <el-radio-group v-model="trendDays" size="mini" style="float:right" @change="loadTrend">
          <el-radio-button :label="7">7 天</el-radio-button>
          <el-radio-button :label="30">30 天</el-radio-button>
        </el-radio-group>
      </div>
      <div ref="trendChart" class="trend-chart" v-loading="trendLoading"></div>
    </el-card>

    <!-- ============ 快捷入口 ============ -->
    <el-card shadow="never" class="block">
      <div slot="header"><b>快捷入口</b></div>
      <el-row :gutter="12">
        <el-col :xs="12" :sm="6" v-for="q in quickActions" :key="q.path">
          <div class="quick" :class="'q-' + q.tone" @click="$router.push(q.path)">
            <i :class="q.icon"></i>
            <div class="q-text">
              <div class="q-title">{{ q.title }}</div>
              <div class="q-desc">{{ q.desc }}</div>
            </div>
            <i class="el-icon-arrow-right q-arrow"></i>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- ============ 数据源 + 最近活动 ============ -->
    <el-row :gutter="12" class="row">
      <!-- 数据源 -->
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="block">
          <div slot="header" class="clearfix">
            <span><b>数据源概览</b>　<span class="muted">共 {{ datasources.length }} 个</span></span>
            <el-button style="float:right" size="mini" type="text"
                       @click="$router.push('/sync/datasource')">前往管理 <i class="el-icon-arrow-right"></i></el-button>
          </div>
          <div v-if="!datasources.length" class="empty">
            还没有数据源, <a href="javascript:;" @click="$router.push('/sync/datasource')">立即添加</a>
          </div>
          <el-table v-else :data="datasources.slice(0, 6)" size="small" border>
            <!-- 修复: 实体字段是 datasourceName, 之前 prop="name" 一直取到 undefined -->
            <el-table-column label="名称" prop="datasourceName" min-width="120" show-overflow-tooltip />
            <!-- 类型: 当前工具仅支持 MySQL→MySQL 同步, 写死展示, 后续如接入多库可换回 row.dbType -->
            <el-table-column label="类型" width="100" align="center">
              <template slot-scope>
                <el-tag size="mini">MySQL</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="主机:端口" min-width="160" show-overflow-tooltip>
              <template slot-scope="{ row }">{{ row.host }}:{{ row.port }}</template>
            </el-table-column>
            <!-- 状态: sync_datasource 表暂无 enabled 字段, 全部视为启用 -->
            <el-table-column label="状态" width="80" align="center">
              <template slot-scope>
                <el-tag size="mini" type="success">启用</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 最近活动 -->
      <el-col :xs="24" :md="12">
        <el-card shadow="never" class="block">
          <div slot="header" class="clearfix">
            <span><b>最近同步活动</b>　<span class="muted">最新 {{ recent.length }} 条</span></span>
            <el-button style="float:right" size="mini" type="text"
                       @click="$router.push('/sync/log')">查看全部 <i class="el-icon-arrow-right"></i></el-button>
          </div>
          <div v-if="!recent.length" class="empty">暂无同步日志</div>
          <ul v-else class="recent">
            <li v-for="r in recent" :key="r.id">
              <el-tag size="mini" :type="logTag(r.status)" class="tag">{{ logText(r.status) }}</el-tag>
              <span class="t-name" :title="r.taskName">{{ r.taskName }}</span>
              <span class="t-table" :title="r.tableName">· {{ r.tableName }}</span>
              <span class="t-msg" :title="r.errorMsg">{{ r.status === 'FAILED' && r.errorMsg ? ('· ' + r.errorMsg) : '' }}</span>
              <span class="t-time">{{ fmtTime(r.createTime) }}</span>
            </li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <!-- ============ 使用帮助 ============ -->
    <el-card shadow="never" class="block">
      <div slot="header"><b>使用帮助</b></div>
      <ol class="help">
        <li>进入 <a href="javascript:;" @click="$router.push('/sync/datasource')">数据源管理</a> 添加源库和目标库, 支持连接测试与表结构预览</li>
        <li>进入 <a href="javascript:;" @click="$router.push('/sync/task')">同步任务</a> 创建一个全量 / 增量 / 表结构任务</li>
        <li>点击「启动」开始同步, 支持断点续传、批次动态告警 (钉钉 / 邮件)</li>
        <li>实时监控可在 <a href="javascript:;" @click="$router.push('/sync/dashboard')">任务大盘</a> 查看, 异常排查请到 <a href="javascript:;" @click="$router.push('/sync/log')">同步日志</a></li>
        <li>在线执行 SQL 请到 <a href="javascript:;" @click="$router.push('/sql')">SQL 工作台</a>, 支持 EXPLAIN 与 SQL 收藏</li>
      </ol>
    </el-card>
  </div>
</template>

<script>
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent,
  TitleComponent, DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent, DataZoomComponent, CanvasRenderer])

import { listDataSource, pageTask, taskDashboard, getLicense, pageLog, logTrend } from '@/api/datamove'

export default {
  name: 'HomeDashboard',
  data () {
    return {
      userName: this.$store.state.user?.userName || 'admin',
      nowTimer: null,
      nowStr: '',
      datasources: [],
      tasks: [],
      liveTasks: [],
      recent: [],
      lic: { loaded: false },
      trendDays: 7,
      trendLoading: false,
      trendLoaded: false,
      trendTotal: 0,
      trendFailed: 0,
      chart: null,
      resizeHandler: null
    }
  },
  computed: {
    greet () {
      const h = new Date().getHours()
      if (h < 6)  return '凌晨好'
      if (h < 12) return '上午好'
      if (h < 14) return '中午好'
      if (h < 18) return '下午好'
      return '晚上好'
    },
    statCards () {
      const count = s => this.tasks.filter(t => t.status === s).length
      const running = this.tasks.filter(t => t.status === 'RUNNING')
      const rows = this.tasks.reduce((s, t) => s + (Number(t.totalRows) || 0), 0)
      const throughput = running.reduce((s, t) => s + (Number(t.rowsPerSec) || 0), 0)
      const failed = count('FAILED')
      return [
        { label: '运行中',  value: count('RUNNING'),  tone: 'green',  icon: 'el-icon-video-play',   hint: this.liveTasks.length ? `实时吞吐 ${fmtNum(throughput, 1)} 行/秒` : '暂无运行任务' },
        { label: '已暂停',  value: count('PAUSE'),    tone: 'orange', icon: 'el-icon-video-pause' },
        { label: '已完成',  value: count('COMPLETED'),tone: 'blue',   icon: 'el-icon-circle-check' },
        { label: '失败',    value: failed,            tone: 'red',    icon: 'el-icon-warning',       hint: failed > 0 ? '请到同步日志排查' : '' },
        { label: '数据源',  value: this.datasources.length,        tone: 'cyan',   icon: 'el-icon-collection' },
        { label: 'SQL 收藏',value: this.$store.state.user?.sqlFavCount ?? '—', tone: 'purple', icon: 'el-icon-star-off' },
        { label: '累计同步行数', value: fmtNum(rows, 0),          tone: 'grey',   icon: 'el-icon-data-line' },
        { label: '今日同步行数', value: fmtNum(this.todayRows, 0), tone: 'gold',   icon: 'el-icon-time',       hint: this.recent.length ? `最近 ${this.recent.length} 条日志` : '' }
      ]
    },
    quickActions () {
      return [
        { title: '数据源管理', desc: '管理源/目标库连接', path: '/sync/datasource', icon: 'el-icon-collection',   tone: 'blue' },
        { title: '同步任务',   desc: '创建全量/增量任务', path: '/sync/task',        icon: 'el-icon-pie-chart',    tone: 'green' },
        { title: '任务大盘',   desc: '实时速率/ETA/瓶颈', path: '/sync/dashboard',   icon: 'el-icon-odometer',     tone: 'orange' },
        { title: 'SQL 工作台', desc: '在线执行/EXPLAIN',  path: '/sql',              icon: 'el-icon-monitor',      tone: 'purple' }
      ]
    },
    todayRows () {
      if (!this.recent.length) return 0
      const today = new Date(); today.setHours(0, 0, 0, 0)
      return this.recent
        .filter(r => new Date(r.createTime) >= today)
        .reduce((s, r) => s + (Number(r.batchRows) || Number(r.totalRows) || 0), 0)
    }
  },
  mounted () {
    this.tickTime()
    this.nowTimer = setInterval(this.tickTime, 30 * 1000)
    this.load()
    this.resizeHandler = () => { if (this.chart) this.chart.resize() }
    window.addEventListener('resize', this.resizeHandler)
  },
  beforeDestroy () {
    if (this.nowTimer) clearInterval(this.nowTimer)
    if (this.resizeHandler) window.removeEventListener('resize', this.resizeHandler)
    if (this.chart) { this.chart.dispose(); this.chart = null }
  },
  methods: {
    tickTime () {
      const d = new Date(), p = n => String(n).padStart(2, '0')
      const w = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
      this.nowStr = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} 星期${w}  ${p(d.getHours())}:${p(d.getMinutes())}`
    },
    async load () {
      const safe = (p, d) => p.catch(() => d)
      const [ds, ts, dash, lg, lic] = await Promise.all([
        safe(listDataSource(), { data: [] }),
        safe(pageTask({ pageNum: 1, pageSize: 1000 }), { data: { rows: [], total: 0 } }),
        safe(taskDashboard(), { data: [] }),
        safe(pageLog({ pageNum: 1, pageSize: 5 }), { data: { rows: [], total: 0 } }),
        safe(getLicense(), { data: null })
      ])
      this.datasources = ds.data || []
      this.tasks       = ts.data?.rows || []
      this.liveTasks   = (dash.data || []).filter(t => t.status === 'RUNNING' || t.status === 'PAUSE')
      this.recent      = lg.data?.rows || []
      this.buildLic(lic.data)
      this.loadTrend()
    },
    async loadTrend () {
      this.trendLoading = true
      try {
        const r = await logTrend(this.trendDays)
        const data = (r && r.data) || []
        this.trendTotal  = data.reduce((s, x) => s + (Number(x.rows) || 0), 0)
        this.trendFailed = data.reduce((s, x) => s + (Number(x.failedCount) || 0), 0)
        this.trendLoaded = true
        await this.$nextTick()
        this.renderTrend(data)
      } catch (e) { /* 失败就空图 */ } finally { this.trendLoading = false }
    },
    renderTrend (data) {
      if (!this.$refs.trendChart) return
      if (!this.chart) this.chart = echarts.init(this.$refs.trendChart)
      const dates = data.map(d => d.date.slice(5))   // MM-DD
      const rows  = data.map(d => Number(d.rows) || 0)
      const succ  = data.map(d => Number(d.successCount) || 0)
      const fail  = data.map(d => Number(d.failedCount) || 0)
      const useBar = this.trendDays <= 14
      this.chart.setOption({
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: useBar ? 'shadow' : 'line' },
          formatter: params => {
            const day = params[0].axisValue
            let html = `<div style="font-weight:600;margin-bottom:4px">${day}</div>`
            params.forEach(p => {
              html += `<div>${p.marker} ${p.seriesName}: <b>${p.data.toLocaleString('en-US')}</b></div>`
            })
            return html
          }
        },
        legend: { right: 0, top: 0, icon: 'roundRect', textStyle: { fontSize: 12 } },
        grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
        xAxis: { type: 'category', data: dates, boundaryGap: useBar, axisLine: { lineStyle: { color: '#dcdfe6' } }, axisLabel: { color: '#909399', fontSize: 11 } },
        yAxis: [
          { type: 'value', name: '行数', position: 'left', axisLabel: { color: '#909399', fontSize: 11, formatter: v => v >= 1000 ? (v / 1000).toFixed(v >= 10000 ? 0 : 1) + 'k' : v }, splitLine: { lineStyle: { color: '#f0f2f5' } } },
          { type: 'value', name: '批次', position: 'right', axisLabel: { color: '#909399', fontSize: 11 }, splitLine: { show: false } }
        ],
        series: [
          { name: '同步行数', type: useBar ? 'bar' : 'line', data: rows, smooth: !useBar, barWidth: useBar ? '50%' : undefined,
            itemStyle: { color: '#409EFF' }, areaStyle: useBar ? undefined : { color: 'rgba(64,158,255,0.18)' }, symbol: 'circle', symbolSize: 6 },
          { name: '成功批次', type: 'line', yAxisIndex: 1, data: succ, smooth: true, symbol: 'circle', symbolSize: 5,
            itemStyle: { color: '#67C23A' }, lineStyle: { type: 'dashed' } },
          { name: '失败批次', type: 'line', yAxisIndex: 1, data: fail, smooth: true, symbol: 'circle', symbolSize: 5,
            itemStyle: { color: '#F56C6C' }, lineStyle: { type: 'dashed' } }
        ]
      }, true)
    },
    buildLic (l) {
      if (!l) { this.lic = { loaded: false }; return }
      const now = new Date()
      const exp = l.expireTime ? new Date(l.expireTime) : null
      let cls = 'green', statusText = '已授权'
      let expireText = ''
      if (exp) {
        const days = Math.ceil((exp - now) / 86400000)
        expireText = days >= 0 ? `剩余 ${days} 天 (${exp.getFullYear()}-${pad(exp.getMonth() + 1)}-${pad(exp.getDate())})` : `已于 ${exp.getFullYear()}-${pad(exp.getMonth() + 1)}-${pad(exp.getDate())} 到期`
        if (days < 0)       { cls = 'red';    statusText = '已过期' }
        else if (days <= 7) { cls = 'orange'; statusText = '即将过期' }
      } else {
        cls = 'red'; statusText = '未授权'
      }
      this.lic = { loaded: true, cls, statusText, expireText }
    },
    /* ---- 展示格式化 ---- */
    fmtTime (t) {
      if (!t) return ''
      const d = new Date(t); if (isNaN(d.getTime())) return ''
      const p = n => String(n).padStart(2, '0')
      const now = new Date(), same = d.toDateString() === now.toDateString()
      const time = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
      return same ? time : `${p(d.getMonth() + 1)}-${p(d.getDate())} ${time}`
    },
    logTag (s)   { return ({ SUCCESS: 'success', FAILED: 'danger', RUNNING: '' })[s] || 'info' },
    logText (s)  { return ({ SUCCESS: '成功', FAILED: '失败', RUNNING: '同步中' })[s] || s },
    tagForDb (t) { return ({ MySQL: '', PostgreSQL: 'success', Oracle: 'warning', SQLServer: 'info', DM: 'danger', Kingbase: 'danger' })[t] || '' }
  }
}

function fmtNum (n, fixed = 0) {
  if (n === null || n === undefined) return '—'
  const v = Number(n) || 0
  if (fixed > 0) return v >= 100 ? Math.round(v).toLocaleString('en-US') : v.toFixed(fixed)
  return Math.round(v).toLocaleString('en-US')
}
function pad (n) { return String(n).padStart(2, '0') }
</script>

<style scoped>
.home { padding: 0 2px }
.row  { margin-bottom: 12px }
.block { margin-bottom: 12px; border-radius: 4px }
.muted { color: #909399; font-size: 12px; margin-left: 6px }
.empty { color: #909399; font-size: 13px; text-align: center; padding: 22px 0 }

/* ============ Hero ============ */
.hero {
  background: linear-gradient(120deg, #1890ff 0%, #096dd9 60%, #0050b3 100%);
  color: #fff; border-radius: 4px; padding: 18px 22px;
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.hero-title { font-size: 20px; font-weight: 600 }
.hero-title i { margin-right: 8px; font-size: 22px }
.hero-sub { font-size: 13px; opacity: .85; margin-top: 4px }
.hero-right { display: flex; align-items: center; gap: 10px }
.hero-btn { background: rgba(255,255,255,.15); border-color: rgba(255,255,255,.4); color: #fff }
.hero-btn:hover { background: rgba(255,255,255,.25); color: #fff; border-color: #fff }

.lic-card {
  display: flex; align-items: center; gap: 10px;
  background: rgba(255,255,255,.12); padding: 8px 14px; border-radius: 4px;
}
.lic-card i { font-size: 22px; opacity: .9 }
.lic-label { font-size: 11px; opacity: .75 }
.lic-text  { font-size: 13px; font-weight: 600 }
.lic-card.tone-orange { background: rgba(230,162,60,.85) }
.lic-card.tone-red    { background: rgba(245,108,108,.85) }

/* ============ Stat cards ============ */
.stat-card {
  display: flex; align-items: center; gap: 14px;
  background: #fff; border-radius: 4px; padding: 16px 18px;
  box-shadow: 0 1px 4px rgba(0,0,0,.04);
  transition: box-shadow .15s;
}
.stat-card:hover { box-shadow: 0 2px 10px rgba(0,0,0,.08) }
.stat-icon {
  width: 48px; height: 48px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 24px; color: #fff;
}
.stat-body { flex: 1; min-width: 0 }
.stat-val   { font-size: 24px; font-weight: 600; line-height: 1.2 }
.stat-label { color: #909399; font-size: 12px; margin-top: 2px }
.stat-hint  { color: #909399; font-size: 11px; margin-top: 2px }
.tone-green .stat-icon { background: #67C23A }
.tone-blue  .stat-icon { background: #409EFF }
.tone-orange .stat-icon { background: #E6A23C }
.tone-red   .stat-icon { background: #F56C6C }
.tone-cyan  .stat-icon { background: #13C2C2 }
.tone-purple .stat-icon { background: #8E44AD }
.tone-grey  .stat-icon { background: #909399 }
.tone-gold  .stat-icon { background: #D4A017 }

/* ============ Trend chart ============ */
.trend-chart { width: 100%; height: 280px }

/* ============ Quick actions ============ */
.quick {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px; border-radius: 4px;
  background: #f5f7fa; cursor: pointer; margin-bottom: 8px;
  border-left: 3px solid #1890ff;
  transition: background .15s, transform .15s;
}
.quick:hover { background: #ecf5ff; transform: translateX(2px) }
.quick > i:first-child { font-size: 26px }
.q-text { flex: 1 }
.q-title { font-size: 14px; font-weight: 600; color: #303133 }
.q-desc  { font-size: 12px; color: #909399; margin-top: 2px }
.q-arrow { color: #c0c4cc }
.q-blue   { border-left-color: #409EFF }
.q-blue   i:first-child { color: #409EFF }
.q-green  { border-left-color: #67C23A }
.q-green  i:first-child { color: #67C23A }
.q-orange { border-left-color: #E6A23C }
.q-orange i:first-child { color: #E6A23C }
.q-purple { border-left-color: #8E44AD }
.q-purple i:first-child { color: #8E44AD }

/* ============ Recent activity ============ */
.recent { list-style: none; padding: 0; margin: 0 }
.recent li {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 4px; border-bottom: 1px dashed #ebeef5;
  font-size: 13px; color: #606266;
}
.recent li:last-child { border-bottom: 0 }
.recent .tag { flex-shrink: 0 }
.recent .t-name { font-weight: 600; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.recent .t-table { color: #909399; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.recent .t-msg { color: #F56C6C; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px }
.recent .t-time { color: #909399; font-size: 12px; margin-left: auto; flex-shrink: 0; font-family: Menlo, Consolas, monospace }

/* ============ Help ============ */
.help { margin: 0; padding-left: 22px; color: #606266; line-height: 1.9; font-size: 13px }
.help a { color: #1890ff; text-decoration: none }
.help a:hover { text-decoration: underline }
</style>