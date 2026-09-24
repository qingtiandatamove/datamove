<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix head">
        <span>SQL 工作台</span>
        <span class="head-tip">支持查询 / 增删改 / 建表改表, 多语句以分号分隔, Ctrl+Enter 执行选中或全部, Ctrl+空格 补全</span>
      </div>

      <!-- 数据源选择 + 操作按钮 -->
      <el-form :inline="true" class="sql-toolbar">
        <el-form-item label="数据源">
          <el-select v-model="dsId" size="small" filterable style="width:300px" placeholder="选择数据源"
            @change="onDsChange">
            <el-option v-for="d in datasources" :key="d.id" :value="d.id"
              :label="d.datasourceName + ' (' + d.host + '/' + d.dbName + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item class="toolbar-btns">
          <el-button size="small" type="primary" icon="el-icon-video-play"
            :disabled="!dsId || !runnable || running" :loading="running" @click="onRun">
            执行 (Ctrl+Enter)
          </el-button>
          <el-button size="small" icon="el-icon-delete" @click="onClear">清空</el-button>
          <el-button size="small" :type="schemaPanelVisible ? 'primary' : ''" icon="el-icon-files"
            :disabled="!dsId" @click="toggleSchemaPanel">表结构</el-button>
          <el-button size="small" icon="el-icon-star-off" :disabled="!dsId || !runnable"
            @click="openSaveFavorite">收藏</el-button>
          <el-button size="small" icon="el-icon-search"
            :disabled="!dsId || !runnable || !canExplain || explainRunning"
            :loading="explainRunning" @click="onExplain">EXPLAIN</el-button>
        </el-form-item>
      </el-form>

      <!-- SQL 编辑区 (CodeMirror) -->
      <div class="editor-wrap">
        <div ref="editor" class="sql-editor"></div>
        <div class="editor-bar">
          <span class="bar-hint">{{ selHint }}</span>
        </div>
      </div>

      <!-- 表结构助手 -->
      <div v-if="schemaPanelVisible" class="schema-panel">
        <div class="schema-head">
          <span class="schema-title">📋 表结构助手</span>
          <span class="schema-meta">点击列名 → 插入编辑器光标处</span>
          <el-button size="mini" icon="el-icon-close" class="schema-close" @click="schemaPanelVisible = false">关闭</el-button>
        </div>
        <div class="schema-toolbar">
          <el-select v-model="schemaActiveTable" placeholder="选择表" filterable clearable size="small" style="width: 280px"
            @change="onSchemaTableChange">
            <el-option v-for="t in schemaTablesForPanel" :key="t" :value="t" :label="t" />
          </el-select>
          <el-button size="mini" icon="el-icon-refresh" :disabled="!schemaActiveTable" @click="refreshSchemaPanel">刷新</el-button>
        </div>
        <div v-if="schemaLoading" class="schema-loading">加载中...</div>
        <div v-else-if="schemaActive && schemaActive.table">
          <div class="schema-meta-line">
            <span class="schema-meta-item">表: <b>{{ schemaActive.table.tableName }}</b></span>
            <span class="schema-meta-item">引擎: {{ schemaActive.table.engine || '-' }}</span>
            <span class="schema-meta-item">字符集: {{ schemaActive.table.collation || '-' }}</span>
            <span class="schema-meta-item" v-if="schemaActive.table.tableComment">备注: {{ schemaActive.table.tableComment }}</span>
          </div>
          <el-collapse v-model="schemaActiveNames">
            <el-collapse-item title="列" name="cols">
              <el-table :data="schemaActive.table.columns" border size="mini" max-height="240">
                <el-table-column prop="columnName" label="列名" min-width="140">
                  <template slot-scope="s">
                    <a href="javascript:;" class="col-link" @click="insertColToEditor(s.row.columnName)">{{ s.row.columnName }}</a>
                  </template>
                </el-table-column>
                <el-table-column prop="columnType" label="类型" min-width="120" />
                <el-table-column prop="columnKey" label="键" min-width="60">
                  <template slot-scope="s">
                    <el-tag size="mini" v-if="s.row.columnKey === 'PRI'" type="danger">主键</el-tag>
                    <el-tag size="mini" v-else-if="s.row.columnKey === 'UNI'" type="warning">唯一</el-tag>
                    <el-tag size="mini" v-else-if="s.row.columnKey === 'MUL'" type="info">索引</el-tag>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
                <el-table-column prop="nullable" label="可空" min-width="60">
                  <template slot-scope="s">
                    <el-tag size="mini" :type="s.row.nullable === 'YES' ? 'success' : 'info'">{{ s.row.nullable === 'YES' ? '是' : '否' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="defaultValue" label="默认值" min-width="100" show-overflow-tooltip />
                <el-table-column prop="columnComment" label="注释" min-width="160" show-overflow-tooltip />
              </el-table>
            </el-collapse-item>
            <el-collapse-item :title="`索引 (${schemaActive.table.indexes.length})`" name="idx">
              <el-table :data="indexGrouped(schemaActive.table.indexes)" border size="mini" max-height="200">
                <el-table-column prop="keyName" label="索引名" min-width="180" />
                <el-table-column prop="unique" label="唯一" min-width="60">
                  <template slot-scope="s">
                    <el-tag size="mini" :type="s.row.unique ? 'warning' : 'info'">{{ s.row.unique ? '唯一' : '普通' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="type" label="类型" min-width="60" />
                <el-table-column prop="columns" label="列" min-width="240" show-overflow-tooltip />
              </el-table>
            </el-collapse-item>
            <el-collapse-item title="DDL" name="ddl">
              <pre class="schema-ddl">{{ schemaActive.table.ddl }}</pre>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <!-- 历史 / 收藏 tabs -->
      <el-tabs v-model="sqlActiveTab" class="sql-tabs" @tab-click="onSqlTabClick">
        <el-tab-pane label="历史" name="history">
          <div v-if="history.length" class="history">
            <el-tag v-for="(h, i) in history" :key="i" size="small" type="info"
              class="history-item" @click.native="setSql(h)">{{ shortSql(h) }}</el-tag>
          </div>
          <div v-else class="empty-hint">尚无历史记录, 执行 SQL 后会自动保存</div>
        </el-tab-pane>
        <el-tab-pane label="收藏" name="favorite">
          <div class="favorite-toolbar">
            <el-input v-model="favoriteKeyword" placeholder="搜索标题/SQL/标签" size="mini" style="width: 220px"
              @keyup.enter.native="loadFavorites" clearable />
            <el-button size="mini" icon="el-icon-search" @click="loadFavorites">搜索</el-button>
            <el-button size="mini" type="primary" icon="el-icon-star-off" @click="openSaveFavorite"
              :disabled="!runnable">保存当前 SQL</el-button>
          </div>
          <div v-if="favoriteLoading" class="schema-loading">加载中...</div>
          <div v-else-if="favoriteList.length" class="favorite-list">
            <el-card v-for="f in favoriteList" :key="f.id" class="favorite-card" shadow="never">
              <div class="favorite-head">
                <span class="favorite-title">{{ f.title }}</span>
                <span class="favorite-meta">
                  <el-tag v-if="f.shared === 1" size="mini" type="success">共享</el-tag>
                  <el-tag v-for="t in (f.tags || '').split(',').filter(Boolean)" :key="t" size="mini" type="info" class="tag-item">{{ t }}</el-tag>
                  <span class="favorite-use">使用 {{ f.useCount || 0 }} 次</span>
                </span>
              </div>
              <pre class="favorite-sql" title="点击加载到编辑器" @click="useFavorite(f)">{{ f.sqlText }}</pre>
              <div class="favorite-foot">
                <span class="favorite-author">{{ f.userName }} · {{ formatFavoriteTime(f.updateTime) }}</span>
                <span class="favorite-ops">
                  <el-button size="mini" type="text" icon="el-icon-position" @click="useFavorite(f)">加载</el-button>
                  <el-button v-if="f.userName === currentUserName" size="mini" type="text" icon="el-icon-edit" @click="openEditFavorite(f)">编辑</el-button>
                  <el-button v-if="f.userName === currentUserName" size="mini" type="text" icon="el-icon-delete" @click="removeFavorite(f)">删除</el-button>
                </span>
              </div>
            </el-card>
          </div>
          <div v-else class="empty-hint">{{ dsId ? '尚无收藏' : '请先选择数据源' }}</div>
        </el-tab-pane>
      </el-tabs>

      <!-- 执行计划 (EXPLAIN 按钮触发, 独立展示) -->
      <div v-if="explainVisible" class="explain-area">
        <div class="explain-head">
          <span class="explain-title">📊 执行计划</span>
          <span class="explain-meta" v-if="explainResult">耗时 {{ explainResult.elapsed }} ms · {{ explainResult.result && explainResult.result.total }} 行 · {{ explainResult.analyze ? 'ANALYZE 实际执行' : '预估' }}</span>
          <el-button size="mini" icon="el-icon-close" class="explain-close" @click="closeExplain">关闭</el-button>
        </div>
        <div v-if="explainError" class="explain-error">
          <el-alert :title="explainError" type="error" :closable="false" show-icon />
        </div>
        <el-table v-else-if="explainResult && explainResult.result" :data="explainResult.result.rows" border size="small"
          max-height="420" class="explain-table">
          <el-table-column v-for="col in explainResult.result.columns" :key="col"
            :prop="col" :label="col" min-width="120" show-overflow-tooltip>
            <template slot-scope="s">
              <span :class="explainCellClass(col, s.row[col])">{{ s.row[col] === null || s.row[col] === undefined ? 'NULL' : s.row[col] }}</span>
            </template>
          </el-table-column>
        </el-table>
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

      <!-- 保存 / 编辑收藏 -->
      <el-dialog :title="editingFavoriteId ? '编辑收藏' : '保存收藏'" :visible.sync="showSaveDialog" width="560px" @closed="editingFavoriteId = null">
        <el-form :model="newFavorite" label-width="80px" size="small">
          <el-form-item label="标题">
            <el-input v-model="newFavorite.title" maxlength="64" show-word-limit placeholder="给这次收藏起个标题" />
          </el-form-item>
          <el-form-item label="标签">
            <el-input v-model="newFavorite.tags" placeholder="多个标签用英文/中文逗号分隔" />
          </el-form-item>
          <el-form-item label="团队共享">
            <el-switch v-model="newFavorite.shared" :active-value="1" :inactive-value="0" />
            <span class="form-tip">开启后所有用户可见</span>
          </el-form-item>
          <el-form-item label="SQL">
            <el-input type="textarea" v-model="newFavorite.sqlText" :rows="10" />
          </el-form-item>
        </el-form>
        <span slot="footer">
          <el-button @click="showSaveDialog = false">取消</el-button>
          <el-button type="primary" :loading="savingFavorite" @click="submitFavorite">{{ editingFavoriteId ? '更新' : '收藏' }}</el-button>
        </span>
      </el-dialog>

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

import { listDataSource, listTables, listColumns, getTableSchema, execSql, explainSql, pageSqlFavorite, detailSqlFavorite, addSqlFavorite, updateSqlFavorite, deleteSqlFavorite, useSqlFavorite } from '@/api/datamove'

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
      schema: {},
      // EXPLAIN 执行计划独立展示
      explainRunning: false,
      explainResult: null,
      explainError: '',
      explainVisible: false,
      // 表结构助手
      schemaPanelVisible: false,
      schemaTablesForPanel: [],
      schemaActiveTable: '',
      schemaLoading: false,
      schemaCache: {},
      schemaActive: null,
      schemaActiveNames: ['cols'],
      // 收藏
      sqlActiveTab: 'history',
      favoriteList: [],
      favoriteLoading: false,
      favoriteKeyword: '',
      showSaveDialog: false,
      savingFavorite: false,
      editingFavoriteId: null,
      newFavorite: { title: '', tags: '', shared: 0, sqlText: '', dsId: null, dsName: '' }
    }
  },
  computed: {
    runnable () { return !!(this.sqlText && this.sqlText.trim()) },
    resultCount () { return this.results.length },
    elapsed () { return this._elapsed || 0 },
    /* EXPLAIN 仅适用 SELECT / WITH / SHOW / TABLE / DESC / EXPLAIN 等只读类语句 */
    canExplain () {
      const sql = (this.sqlText || '').trim()
      if (!sql) return false
      const first = sql.split(';')[0]
      const stripped = first.replace(/(--[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)/g, '').trim()
      const head = stripped.split(/\s+/)[0] || ''
      return /^(SELECT|WITH|SHOW|TABLE|DESC|DESCRIBE|VALUES|EXPLAIN)$/i.test(head)
    },
    selHint () {
      const sel = this.selectedSql()
      if (sel) {
        const lines = sel.split('\n').length
        return `已选中 ${lines} 行, 将只执行选中部分`
      }
      return '未选中时执行全部语句'
    },
    currentUserName () {
      try {
        const u = (this.$store && this.$store.state && this.$store.state.user) || {}
        return u.userName || u.name || ''
      } catch (e) { return '' }
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
      this.schemaTablesForPanel = []
      this.schemaActiveTable = ''
      this.schemaActive = null
      this.schemaCache = {}
      if (!this.dsId) return
      // 先拿表名列表 (此时补全已可用表名)
      listTables(this.dsId)
        .then(r => {
          const tables = r.data || []
          this.schemaTablesForPanel = tables
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
    /* ---------- EXPLAIN 执行计划 ---------- */
    onExplain () {
      const sel = this.selectedSql()
      const sql = (sel || this.sqlText || '').trim()
      if (!this.dsId || !sql || this.explainRunning) return
      this.explainRunning = true
      this.explainError = ''
      explainSql(this.dsId, sql, false)
        .then(r => {
          this.explainResult = r.data || null
          this.explainVisible = true
        })
        .catch(err => {
          this.explainError = (err && err.message) || 'EXPLAIN 失败'
          this.explainResult = null
          this.explainVisible = !!this.explainError
        })
        .finally(() => { this.explainRunning = false })
    },
    closeExplain () {
      this.explainVisible = false
      this.explainResult = null
      this.explainError = ''
    },
    /* ---------- SQL 收藏 ---------- */
    onSqlTabClick (tab) {
      if (tab && tab.name === 'favorite') this.loadFavorites()
    },
    loadFavorites () {
      if (!this.dsId) { this.favoriteList = []; return }
      this.favoriteLoading = true
      pageSqlFavorite({ keyword: this.favoriteKeyword, dsId: this.dsId, pageNum: 1, pageSize: 100 })
        .then(r => {
          const d = r.data || {}
          this.favoriteList = d.rows || []
        })
        .catch(() => { this.favoriteList = [] })
        .finally(() => { this.favoriteLoading = false })
    },
    openSaveFavorite () {
      const dsName = (this.datasources.find(d => d.id === this.dsId) || {}).datasourceName || ''
      this.editingFavoriteId = null
      this.newFavorite = {
        title: '',
        tags: '',
        shared: 0,
        sqlText: this.sqlText || '',
        dsId: this.dsId,
        dsName
      }
      this.showSaveDialog = true
    },
    openEditFavorite (f) {
      this.editingFavoriteId = f.id
      this.newFavorite = {
        title: f.title || '',
        tags: f.tags || '',
        shared: f.shared || 0,
        sqlText: f.sqlText || '',
        dsId: f.dsId,
        dsName: f.dsName || ''
      }
      this.showSaveDialog = true
    },
    submitFavorite () {
      if (!this.newFavorite.title) { this.$message.warning('请输入标题'); return }
      if (!this.newFavorite.sqlText) { this.$message.warning('SQL 不能为空'); return }
      this.savingFavorite = true
      const p = this.editingFavoriteId
        ? updateSqlFavorite({ id: this.editingFavoriteId, title: this.newFavorite.title, tags: this.newFavorite.tags, sqlText: this.newFavorite.sqlText, shared: this.newFavorite.shared })
        : addSqlFavorite(this.newFavorite)
      p.then(() => {
        this.$message.success(this.editingFavoriteId ? '已更新' : '已收藏')
        this.showSaveDialog = false
        if (this.sqlActiveTab === 'favorite') this.loadFavorites()
      })
      .catch(err => this.$message.error((err && err.message) || '操作失败'))
      .finally(() => { this.savingFavorite = false })
    },
    removeFavorite (f) {
      this.$confirm(`确定删除收藏「${f.title}」?`, '提示', { type: 'warning' })
        .then(() => deleteSqlFavorite(f.id))
        .then(() => {
          this.$message.success('已删除')
          this.favoriteList = this.favoriteList.filter(x => x.id !== f.id)
        })
        .catch(() => {})
    },
    useFavorite (f) {
      this.setSql(f.sqlText)
      useSqlFavorite(f.id).catch(() => {})
      this.$message.success(`已加载「${f.title}」`)
    },
    formatFavoriteTime (t) {
      if (!t) return ''
      const d = new Date(t)
      const p = n => (n < 10 ? '0' + n : '' + n)
      return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
    },
    /* ---------- 表结构助手 ---------- */
    toggleSchemaPanel () {
      this.schemaPanelVisible = !this.schemaPanelVisible
      if (this.schemaPanelVisible) {
        // 首次展开时如果没有表列表, 拉取一次
        if (!this.schemaTablesForPanel.length && this.dsId) {
          listTables(this.dsId).then(r => {
            this.schemaTablesForPanel = r.data || []
          }).catch(() => {})
        }
        // 如果有选中表且没缓存, 加载一次
        if (this.schemaActiveTable && !this.schemaCache[this.schemaActiveTable]) {
          this.loadSchema(this.schemaActiveTable)
        } else if (this.schemaActiveTable && this.schemaCache[this.schemaActiveTable]) {
          this.schemaActive = { table: this.schemaCache[this.schemaActiveTable] }
        }
      }
    },
    onSchemaTableChange (t) {
      if (!t) { this.schemaActive = null; return }
      if (this.schemaCache[t]) {
        this.schemaActive = { table: this.schemaCache[t] }
        return
      }
      this.loadSchema(t)
    },
    refreshSchemaPanel () {
      if (!this.schemaActiveTable) return
      delete this.schemaCache[this.schemaActiveTable]
      this.loadSchema(this.schemaActiveTable)
    },
    loadSchema (t) {
      if (!this.dsId || !t) { this.schemaActive = null; return }
      this.schemaLoading = true
      getTableSchema(this.dsId, t)
        .then(r => {
          const data = r.data || null
          if (data) {
            this.$set(this.schemaCache, t, data)
            this.schemaActive = { table: data }
          }
        })
        .catch(err => {
          this.$message.error((err && err.message) || '加载表结构失败')
          this.schemaActive = null
        })
        .finally(() => { this.schemaLoading = false })
    },
    insertColToEditor (col) {
      if (!this.cm || !col) return
      this.cm.replaceSelection(col)
      this.cm.focus()
    },
    /* 把多行索引按 keyName 分组 */
    indexGrouped (indexes) {
      const groups = {}
      for (const i of indexes || []) {
        const k = i.keyName
        if (!groups[k]) {
          groups[k] = { keyName: k, columns: [], unique: i.nonUnique === '0', type: i.indexType }
        }
        if (groups[k].columns.indexOf(i.columnName) < 0) groups[k].columns.push(i.columnName)
      }
      return Object.values(groups).map(g => ({ keyName: g.keyName, unique: g.unique, type: g.type, columns: g.columns.join(', ') }))
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
    },
    /* ---------- 执行计划单元格染色 ---------- */
    explainCellClass (col, val) {
      if (val === null || val === undefined) return ''
      const v = String(val)
      if (col === 'type') {
        // 优秀连接
        if (['system', 'const', 'eq_ref', 'ref'].indexOf(v) >= 0) return 'explain-type-good'
        if (['range'].indexOf(v) >= 0) return 'explain-type-ok'
        // 全表扫描/全索引扫描 - 警告
        if (v === 'ALL' || v === 'index') return 'explain-type-bad'
      }
      if (col === 'Extra') {
        if (/Using filesort|Using temporary/i.test(v)) return 'explain-extra-warn'
      }
      if (col === 'rows') {
        // rows 大于 10000 视为警告
        const n = parseInt(v, 10)
        if (!isNaN(n) && n >= 10000) return 'explain-rows-warn'
      }
      return ''
    }
  }
}
</script>

<style scoped>
.head-tip { float: right; font-size: 12px; font-weight: normal; color: var(--color-text-secondary) }
/* CodeMirror 容器 */
.sql-editor {
  border: 1px solid var(--color-border-darker);
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
/* 数据源 + 操作按钮 同一行 */
.sql-toolbar { margin-bottom: 12px }
.sql-toolbar .el-form-item { margin-bottom: 0 }
.sql-toolbar .toolbar-btns { margin-right: 0 }
.editor-bar {
  display: flex; justify-content: flex-end; align-items: center;
  margin-top: 8px;
}
.bar-hint { font-size: 12px; color: var(--color-text-secondary) }
.history { margin-top: 10px }
.history-item { cursor: pointer; margin-right: 6px; margin-bottom: 4px; max-width: 100% }
/* SQL Tabs */
.sql-tabs { margin-top: 12px }
.sql-tabs /deep/ .el-tabs__header { margin-bottom: 8px }
.empty-hint { color: var(--color-text-secondary); font-size: 12px; padding: 10px 0 }
.favorite-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap }
.favorite-list { display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow-y: auto; padding-right: 4px }
.favorite-card /deep/ .el-card__body { padding: 10px 12px }
.favorite-head { display: flex; align-items: center; margin-bottom: 6px; gap: 10px }
.favorite-title { font-weight: 600; font-size: 14px; color: var(--color-text-primary) }
.favorite-meta { flex: 1; font-size: 12px; color: var(--color-text-secondary); display: flex; align-items: center; gap: 6px; flex-wrap: wrap }
.favorite-use { font-size: 12px; color: var(--color-text-secondary); margin-left: auto }
.favorite-sql {
  background: var(--bg-hover); border: 1px solid var(--color-border); border-radius: 4px;
  color: var(--color-text-primary);
  padding: 8px 10px; font-family: "JetBrains Mono", Consolas, Menlo, monospace;
  font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all;
  max-height: 200px; overflow: auto; margin: 0 0 6px;
  cursor: pointer;
}
.favorite-sql:hover { background: rgba(64, 158, 255, .12); border-color: var(--color-primary) }
.favorite-foot { display: flex; align-items: center; font-size: 12px; color: var(--color-text-secondary) }
.favorite-author { flex: 1 }
.favorite-ops .el-button { padding: 2px 4px }
.tag-item { margin-right: 0 }
.form-tip { margin-left: 10px; color: var(--color-text-secondary); font-size: 12px }
.result-area { margin-top: 16px }
.result-summary { margin-bottom: 10px }
.stmt-idx { margin-right: 8px; color: var(--color-text-secondary); font-size: 12px }
.stmt-sql {
  margin-left: 10px; color: var(--color-text-regular); font-size: 12px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 70%; display: inline-block; vertical-align: middle;
}
.update-info { color: #85d173; font-size: 13px; padding: 6px 4px }
.result-bar {
  display: flex; justify-content: space-between; align-items: center;
  margin: 8px 0 4px;
}
.result-count { font-size: 12px; color: var(--color-text-secondary) }
.result-table { margin: 4px 0 8px }
/* 执行计划 */
.explain-area { margin-top: 16px; border: 1px solid var(--color-border); border-radius: 4px; padding: 10px 12px; background: var(--bg-card) }
.explain-head { display: flex; align-items: center; margin-bottom: 8px; gap: 10px }
.explain-title { font-weight: 600; font-size: 14px; color: var(--color-text-primary) }
.explain-meta { font-size: 12px; color: var(--color-text-secondary); flex: 1 }
.explain-close { margin-left: auto }
.explain-error { margin: 8px 0 }
.explain-table /deep/ .el-table__row { font-family: "JetBrains Mono", Consolas, Menlo, monospace; font-size: 12px }
/* type 列染色: 优秀连接=绿色, range=绿, ALL/index=橙, filesort/temporary=红 */
.explain-area /deep/ .explain-type-good { color: #85d173; font-weight: 600 }
.explain-area /deep/ .explain-type-ok { color: #85d173 }
.explain-area /deep/ .explain-type-bad { color: #eebe77; font-weight: 600 }
.explain-area /deep/ .explain-extra-warn { color: #ff8585; font-weight: 600 }
.explain-area /deep/ .explain-rows-warn { color: #eebe77 }
/* 表结构助手 */
.schema-panel { margin-top: 12px; border: 1px solid var(--color-border); border-radius: 4px; padding: 10px 12px; background: var(--bg-card) }
.schema-head { display: flex; align-items: center; margin-bottom: 8px; gap: 10px }
.schema-title { font-weight: 600; font-size: 14px; color: var(--color-text-primary) }
.schema-meta { font-size: 12px; color: var(--color-text-secondary); flex: 1 }
.schema-close { margin-left: auto }
.schema-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px }
.schema-loading { color: var(--color-text-secondary); font-size: 12px; padding: 8px 0 }
.schema-meta-line { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12px; color: var(--color-text-regular); margin-bottom: 10px }
.schema-meta-item b { color: var(--color-text-primary) }
.col-link { color: var(--color-primary); text-decoration: none; cursor: pointer }
.col-link:hover { text-decoration: underline }
.schema-ddl { background: var(--bg-hover); border: 1px solid var(--color-border); border-radius: 4px; color: var(--color-text-primary); padding: 10px; font-family: "JetBrains Mono", Consolas, Menlo, monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; max-height: 260px; overflow: auto; margin: 0 }
</style>
