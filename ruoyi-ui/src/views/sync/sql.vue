<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix head">
        <span>SQL 工作台</span>
        <span class="head-tip">支持查询 / 增删改 / 建表改表, 多语句以分号分隔, Ctrl+Enter 执行选中或全部, Ctrl+空格 补全</span>
      </div>

      <!-- 数据源选择 -->
      <el-form :inline="true">
        <el-form-item label="数据源">
          <el-select v-model="dsId" size="small" filterable style="width:300px" placeholder="选择数据源"
            @change="onDsChange">
            <el-option v-for="d in datasources" :key="d.id" :value="d.id"
              :label="d.datasourceName + ' (' + d.host + '/' + d.dbName + ')'" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- SQL 编辑区 (CodeMirror) -->
      <div class="editor-wrap">
        <div ref="editor" class="sql-editor"></div>
        <div class="editor-bar">
          <span class="bar-hint">{{ selHint }}</span>
          <div>
            <el-button size="small" icon="el-icon-delete" @click="onClear">清空</el-button>
            <el-button size="small" type="primary" icon="el-icon-video-play"
              :disabled="!dsId || !runnable || running" :loading="running" @click="onRun">
              执行 (Ctrl+Enter)
            </el-button>
          </div>
        </div>
      </div>

      <!-- 历史记录 -->
      <div v-if="history.length" class="history">
        <el-tag v-for="(h, i) in history" :key="i" size="small" type="info"
          class="history-item" @click.native="setSql(h)">{{ shortSql(h) }}</el-tag>
      </div>

      <!-- 执行结果 -->
      <div v-if="ranOnce" class="result-area">
        <div class="result-summary">
          <template v-if="errorMsg">
            <el-alert :title="errorMsg" type="error" :closable="false" show-icon />
          </template>
          <template v-else>
            <el-alert type="success" :closable="false" show-icon
              :title="`共 ${resultCount} 条语句, 耗时 ${elapsed} ms`" />
          </template>
        </div>

        <el-collapse v-if="results.length" v-model="activePanels">
          <el-collapse-item v-for="(r, i) in results" :key="i" :name="String(i)">
            <template slot="title">
              <span class="stmt-idx">#{{ i + 1 }}</span>
              <el-tag size="mini" :type="r.type === 'query' ? 'success' : 'warning'">
                {{ r.type === 'query' ? r.total + ' 行' : '影响 ' + r.affected + ' 行' }}
              </el-tag>
              <span class="stmt-sql">{{ r.sql }}</span>
            </template>

            <!-- 查询结果表 -->
            <div v-if="r.type === 'query'" class="result-bar">
              <span class="result-count">共 {{ r.total }} 行{{ r.total >= 1000 ? ' (已达 1000 行上限, 仅展示前 1000 行)' : '' }}</span>
              <span>
                <el-button size="mini" type="primary" icon="el-icon-download" :disabled="!r.rows || !r.rows.length"
                  @click="exportExcel(r, i)">导出 Excel</el-button>
                <el-button size="mini" icon="el-icon-document" :disabled="!r.rows || !r.rows.length"
                  @click="exportCsv(r, i)">导出 CSV</el-button>
              </span>
            </div>
            <el-table v-if="r.type === 'query'" :data="r.rows" border size="small"
              max-height="420" class="result-table">
              <el-table-column v-for="col in r.columns" :key="col"
                :prop="col" :label="col" min-width="120" show-overflow-tooltip>
                <template slot-scope="s">{{ s.row[col] === null || s.row[col] === undefined ? 'NULL' : s.row[col] }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="update-info">
              <i class="el-icon-success"></i>
              语句执行成功, 影响 {{ r.affected }} 行
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>
  </div>
</template>

<script>
import CodeMirror from 'codemirror'
// SQL 语法高亮
import 'codemirror/mode/sql/sql.js'
// 自动补全 + SQL 补全源
import 'codemirror/addon/hint/show-hint.js'
import 'codemirror/addon/hint/sql-hint.js'
import 'codemirror/addon/hint/show-hint.css'
// 括号匹配 + 当前行高亮 + 激活选区样式
import 'codemirror/addon/edit/matchbrackets.js'
import 'codemirror/addon/selection/active-line.js'
// 主题
import 'codemirror/lib/codemirror.css'
import 'codemirror/theme/material-darker.css'

import * as XLSX from 'xlsx'

import { listDataSource, listTables, listColumns, execSql } from '@/api/datamove'

const HISTORY_KEY = 'sql-workbench-history'
const HISTORY_MAX = 10

export default {
  data () {
    return {
      datasources: [],
      dsId: null,
      sqlText: '',
      running: false,
      ranOnce: false,
      errorMsg: '',
      results: [],
      activePanels: ['0'],
      history: [],
      // 补全元数据: 表名 -> 列名数组
      schema: {}
    }
  },
  computed: {
    runnable () { return !!(this.sqlText && this.sqlText.trim()) },
    resultCount () { return this.results.length },
    elapsed () { return this._elapsed || 0 },
    selHint () {
      const sel = this.selectedSql()
      if (sel) {
        const lines = sel.split('\n').length
        return `已选中 ${lines} 行, 将只执行选中部分`
      }
      return '未选中时执行全部语句'
    }
  },
  mounted () {
    listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    try { this.history = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') } catch (e) { this.history = [] }
    this.initEditor()
  },
  beforeDestroy () {
    if (this.cm) { this.cm.toTextArea && this.cm.toTextArea(); this.cm = null }
  },
  methods: {
    /* ---------- CodeMirror ---------- */
    initEditor () {
      this.cm = CodeMirror(this.$refs.editor, {
        value: this.sqlText,
        mode: 'text/x-mysql',
        theme: 'material-darker',
        lineNumbers: true,
        lineWrapping: true,
        autofocus: true,
        indentUnit: 2,
        tabSize: 2,
        matchBrackets: true,
        styleActiveLine: true,
        extraKeys: {
          'Ctrl-Enter': () => this.onRun(),
          'Cmd-Enter': () => this.onRun(),
          // Ctrl+空格 / Cmd+空格 手动触发补全
          'Ctrl-Space': cm => this.hint(cm),
          'Cmd-Space': cm => this.hint(cm),
          // 输入 . 后自动弹补全 (schema.table 列名)
          '.': cm => {
            cm.replaceSelection('.')
            setTimeout(() => this.hint(cm), 50)
          }
        }
      })
      // 输入字母自动触发补全
      this.cm.on('inputRead', (cm, change) => {
        if (!this.schemaTables().length) return
        const text = change.text && change.text[0]
        // 输入的是标识符字符时触发
        if (text && /^[A-Za-z_@`]/.test(text) && change.origin === '+input') {
          clearTimeout(this._hintTimer)
          this._hintTimer = setTimeout(() => this.hint(cm), 120)
        }
      })
      // 光标移动/选区变化时更新 sqlText 与提示
      this.cm.on('changes', () => { this.sqlText = this.cm.getValue() })
      this.cm.on('cursorActivity', () => {
        // 触发 selHint 计算依赖更新
        this.sqlText = this.cm.getValue() + ''
      })
    },
    hint (cm) {
      cm.showHint({
        hint: CodeMirror.hint.sql,
        completeSingle: false,
        // SQL 关键词 + 表名 + 字段名 全部作为补全源
        tables: this.hintTables(),
        defaultTable: ''
      })
    },
    /* 补全用的 tables 对象: { 表名: [列1, 列2...], ... } — CodeMirror sql-hint 原生支持表->列提示 */
    hintTables () {
      const t = {}
      for (const [table, cols] of Object.entries(this.schema)) {
        t[table] = cols
      }
      return t
    },
    schemaTables () { return Object.keys(this.schema) },
    selectedSql () {
      if (!this.cm) return ''
      return this.cm.somethingSelected() ? this.cm.getSelection() : ''
    },
    setSql (sql) {
      this.sqlText = sql
      if (this.cm) this.cm.setValue(sql)
    },
    onClear () {
      this.setSql('')
      this.cm && this.cm.focus()
    },
    /* ---------- 补全元数据加载 ---------- */
    onDsChange () {
      this.schema = {}
      if (!this.dsId) return
      // 先拿表名列表 (此时补全已可用表名)
      listTables(this.dsId)
        .then(r => {
          const tables = r.data || []
          const init = {}
          tables.forEach(t => { init[t] = [] })
          this.schema = init
          // 懒加载列: 表多时逐表请求太慢, 只预加载前 30 张表的列
          tables.slice(0, 30).forEach(t => {
            listColumns(this.dsId, t)
              .then(cr => {
                const cols = (cr.data || []).map(c => c.columnName)
                this.$set(this.schema, t, cols)
              })
              .catch(() => {})
          })
        })
        .catch(() => {})
    },
    /* ---------- 执行 ---------- */
    onRun () {
      const sel = this.selectedSql()
      const sql = (sel || this.sqlText || '').trim()
      if (!this.dsId || !sql || this.running) return
      this.running = true
      this.ranOnce = true
      this.errorMsg = ''
      execSql(this.dsId, sql)
        .then(r => {
          const d = r.data || {}
          this.results = d.results || []
          this._elapsed = d.elapsed || 0
          this.activePanels = this.results.length ? ['0'] : []
          this.pushHistory(sql)
        })
        .catch(err => {
          this.results = []
          this.errorMsg = (err && err.message) || '执行失败'
        })
        .finally(() => { this.running = false })
    },
    pushHistory (sql) {
      const list = this.history.filter(h => h !== sql)
      list.unshift(sql)
      this.history = list.slice(0, HISTORY_MAX)
      try { localStorage.setItem(HISTORY_KEY, JSON.stringify(this.history)) } catch (e) { /* 忽略存储异常 */ }
    },
    shortSql (sql) {
      const one = sql.replace(/\s+/g, ' ')
      return one.length > 60 ? one.slice(0, 60) + '...' : one
    },
    /* ---------- 导出结果集为 Excel (.xlsx) ---------- */
    exportExcel (r, idx) {
      const cols = r.columns || []
      const rows = r.rows || []
      if (!cols.length) { this.$message.warning('没有可导出的列'); return }
      // 二维数组: 首行表头, null/undefined 置空
      const aoa = [cols.slice()]
      rows.forEach(row => {
        aoa.push(cols.map(c => (row[c] === null || row[c] === undefined) ? '' : row[c]))
      })
      const ws = XLSX.utils.aoa_to_sheet(aoa)
      // 列宽自适应: 按表头 + 前 200 行数据的显示宽度计算 (中文按 2 字符宽)
      ws['!cols'] = cols.map(c => {
        let w = this.dispWidth(c)
        for (let i = 0; i < Math.min(rows.length, 200); i++) {
          w = Math.max(w, this.dispWidth(rows[i][c]))
        }
        return { wch: Math.min(Math.max(w + 2, 8), 50) }
      })
      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, ws, `结果${(idx || 0) + 1}`)
      XLSX.writeFile(wb, `query_${(idx || 0) + 1}_${this.stamp()}.xlsx`)
      this.$message.success(`已导出 ${rows.length} 行`)
    },
    /* 显示宽度: 非 ASCII(中文等) 按 2 个字符宽估算 */
    dispWidth (v) {
      const s = (v === null || v === undefined) ? '' : String(v)
      let w = 0
      for (let i = 0; i < s.length; i++) w += s.charCodeAt(i) > 255 ? 2 : 1
      return w
    },
    /* ---------- 导出结果集为 CSV ---------- */
    exportCsv (r, idx) {
      const cols = r.columns || []
      const rows = r.rows || []
      if (!cols.length) { this.$message.warning('没有可导出的列'); return }
      const lines = [cols.map(c => this.csvCell(c)).join(',')]
      rows.forEach(row => {
        lines.push(cols.map(c => this.csvCell(row[c])).join(','))
      })
      // BOM 头: 保证 Excel 打开 UTF-8 中文不乱码
      const csv = '\ufeff' + lines.join('\r\n')
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `query_${(idx || 0) + 1}_${this.stamp()}.csv`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      this.$message.success(`已导出 ${rows.length} 行`)
    },
    /* CSV 单元格转义: 含逗号/引号/换行时加双引号包裹, 内部引号翻倍 */
    csvCell (v) {
      if (v === null || v === undefined) return ''
      const s = String(v)
      return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
    },
    stamp () {
      const d = new Date()
      const p = n => (n < 10 ? '0' + n : '' + n)
      return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
    }
  }
}
</script>

<style scoped>
.head-tip { float: right; font-size: 12px; font-weight: normal; color: #909399 }
/* CodeMirror 容器 */
.sql-editor {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}
.sql-editor /deep/ .CodeMirror {
  height: 260px;
  font-family: "JetBrains Mono", Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
}
/* 选中区高亮更醒目 (material-darker 默认选区偏淡) */
.sql-editor /deep/ .CodeMirror-focused .CodeMirror-selected {
  background: #3d6d99;
}
.editor-bar {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 8px;
}
.bar-hint { font-size: 12px; color: #909399 }
.history { margin-top: 10px }
.history-item { cursor: pointer; margin-right: 6px; margin-bottom: 4px; max-width: 100% }
.result-area { margin-top: 16px }
.result-summary { margin-bottom: 10px }
.stmt-idx { margin-right: 8px; color: #909399; font-size: 12px }
.stmt-sql {
  margin-left: 10px; color: #606266; font-size: 12px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 70%; display: inline-block; vertical-align: middle;
}
.update-info { color: #67c23a; font-size: 13px; padding: 6px 4px }
.result-bar {
  display: flex; justify-content: space-between; align-items: center;
  margin: 8px 0 4px;
}
.result-count { font-size: 12px; color: #909399 }
.result-table { margin: 4px 0 8px }
</style>
