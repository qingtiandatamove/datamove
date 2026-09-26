<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix">
        <span>数据中心</span>
      </div>

      <!-- 数据源 + 表 选择 -->
      <el-form :inline="true">
        <el-form-item label="数据源">
          <el-select v-model="dsId" size="small" filterable style="width:280px" placeholder="选择数据源"
            @change="onDsChange">
            <el-option v-for="d in datasources" :key="d.id" :value="d.id"
              :label="d.datasourceName + ' (' + d.host + '/' + d.dbName + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item label="表">
          <el-select v-model="tableName" size="small" filterable style="width:240px" placeholder="选择表"
            :disabled="!dsId" @change="onTableChange">
            <el-option v-for="t in tables" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button size="small" icon="el-icon-refresh" @click="loadData">刷新数据</el-button>
        </el-form-item>
      </el-form>

      <el-tabs v-model="activeTab">
        <!-- 数据 -->
        <el-tab-pane label="数据" name="data">
          <div style="margin-bottom:10px">
            <el-button size="small" type="primary" icon="el-icon-plus"
              :disabled="!tableName" @click="onAdd">新增一行</el-button>
          </div>
          <el-alert v-if="tableName && !hasPk" type="warning" :closable="false" show-icon style="margin-bottom:10px"
            title="该表没有主键, 仅支持查看, 不支持编辑/删除" />
          <el-table :data="dataRows" border v-loading="dataLoading" size="small"
            ref="dataTable" @sort-change="onSortChange">
            <el-table-column v-for="col in columns" :key="col.columnName"
              :prop="col.columnName" :label="col.columnName" min-width="110" show-overflow-tooltip
              sortable="custom" :sort-orders="['ascending', 'descending']">
              <template slot-scope="s">{{ fmtVal(s.row[col.columnName]) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right" class-name="op-col">
              <template slot-scope="s">
                <el-button size="mini" type="primary" :disabled="!hasPk" @click="onEdit(s.row)">编辑</el-button>
                <el-button size="mini" type="danger" :disabled="!hasPk" @click="onDel(s.row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination style="margin-top:12px" background layout="total, prev, pager, next, sizes"
            :total="dataTotal" :page-size.sync="query.pageSize" :current-page.sync="query.pageNum"
            :page-sizes="[10, 20, 50, 100]" @current-change="loadData" @size-change="onSizeChange" />
        </el-tab-pane>

        <!-- 表结构 -->
        <el-tab-pane label="表结构" name="struct">
          <div style="margin-bottom:10px">
            <el-button size="small" type="primary" icon="el-icon-plus"
              :disabled="!tableName" @click="onAddCol">新增字段</el-button>
            <el-button size="small" icon="el-icon-collection-tag"
              :disabled="!tableName" @click="onManageIndex">索引管理</el-button>
          </div>
          <el-table :data="structRows" border v-loading="structLoading" size="small"
            :row-class-name="({ row }) => row.__isNew ? 'new-col-row' : ''">
            <el-table-column label="字段名" min-width="120">
              <template slot-scope="s">
                <el-input v-if="s.row.__isNew" v-model="colForm.columnName" size="mini" placeholder="字段名" />
                <span v-else>{{ s.row.columnName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="175">
              <template slot-scope="s">
                <div v-if="s.row.__isNew" style="display:flex; align-items:center">
                  <el-select v-model="colForm.dataType" size="mini" filterable style="width:98px">
                    <el-option v-for="t in ddlTypes" :key="t" :value="t" :label="t" />
                  </el-select>
                  <el-input-number v-if="needLen(colForm)" v-model="colForm.length" size="mini"
                    :min="1" :max="16000" :controls="false" style="width:66px; margin-left:4px"
                    placeholder="长度" />
                </div>
                <span v-else>{{ s.row.dataType }}</span>
              </template>
            </el-table-column>
            <el-table-column label="注释" min-width="140">
              <template slot-scope="s">
                <el-input v-if="s.row.__isNew" v-model="colForm.comment" size="mini" placeholder="注释(可空)" />
                <span v-else>{{ s.row.columnComment ? s.row.columnComment : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="键" width="60">
              <template slot-scope="s">
                <el-tag v-if="s.row.columnKey === 'PRI'" size="mini" type="danger">主键</el-tag>
                <el-tag v-else-if="s.row.columnKey === 'UNI'" size="mini" type="warning">唯一</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="可空" width="60">
              <template slot-scope="s">
                <el-switch v-if="s.row.__isNew" v-model="colForm.nullable" />
                <span v-else>{{ s.row.nullable }}</span>
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="110">
              <template slot-scope="s">
                <el-input v-if="s.row.__isNew" v-model="colForm.defaultValue" size="mini" placeholder="默认值" />
                <span v-else>{{ s.row.defaultValue === null ? '-' : s.row.defaultValue }}</span>
              </template>
            </el-table-column>
            <el-table-column label="小数位" width="80">
              <template slot-scope="s">
                <el-input-number v-if="s.row.__isNew && needDec(colForm)" v-model="colForm.decimal" size="mini"
                  :min="0" :max="30" :controls="false" style="width:66px" placeholder="位" />
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="额外" min-width="90">
              <template slot-scope="s">
                <span v-if="s.row.__isNew">-</span>
                <span v-else>{{ s.row.extra || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right" class-name="op-col">
              <template slot-scope="s">
                <template v-if="s.row.__isNew">
                  <el-button size="mini" type="primary" :loading="colSaving" @click="saveCol">保存</el-button>
                  <el-button size="mini" @click="showNewCol = false">取消</el-button>
                </template>
                <template v-else>
                  <el-button size="mini" type="primary" @click="onEditCol(s.row)">修改</el-button>
                  <el-button size="mini" type="danger" @click="onDropCol(s.row)">删除</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog :title="editPk ? '编辑数据' : '新增数据'" :visible.sync="dialog" width="720px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules"
        label-width="160px" size="small">
        <el-form-item v-for="col in editCols" :key="col.columnName"
          :label="col.columnName" :prop="col.columnName"
          :error="fieldErrors[col.columnName]">

          <!-- 控件(固定宽度) + 输入框后面的限制提示 (int 等整数不提示) -->
          <div style="display:flex; align-items:center">
            <!-- 数字类型: 整数(int/age等)带加减按钮 -->
            <el-input-number v-if="isNumber(col)" v-model="editForm[col.columnName]"
              :disabled="editPk && col.columnName === pkColumn"
              :precision="numberPrecision(col)"
              :controls="isIntCol(col)"
              :min="intRange(col) ? intRange(col)[0] : -Infinity"
              :max="intRange(col) ? intRange(col)[1] : Infinity"
              :class="isIntCol(col) ? 'num-with-ctrl' : 'num-plain'"
              :placeholder="placeholderFor(col)" />

            <!-- 城市字段: 下拉选择 -->
            <el-select v-else-if="isCity(col)" v-model="editForm[col.columnName]"
              :disabled="editPk && col.columnName === pkColumn"
              filterable allow-create default-first-option clearable
              style="width:240px" placeholder="选择或输入城市">
              <el-option v-for="c in cityList" :key="c" :value="c" :label="c" />
            </el-select>

            <!-- 日期时间 -->
            <el-date-picker v-else-if="isDatetime(col)" v-model="editForm[col.columnName]"
              type="datetime" value-format="yyyy-MM-dd HH:mm:ss"
              :disabled="editPk && col.columnName === pkColumn"
              style="width:240px" :placeholder="placeholderFor(col)" />

            <!-- 纯日期 -->
            <el-date-picker v-else-if="isDate(col)" v-model="editForm[col.columnName]"
              type="date" value-format="yyyy-MM-dd"
              :disabled="editPk && col.columnName === pkColumn"
              style="width:240px" :placeholder="placeholderFor(col)" />

            <!-- 字符串等: varchar 按长度限制 -->
            <el-input v-else v-model="editForm[col.columnName]"
              :disabled="editPk && col.columnName === pkColumn"
              :maxlength="maxLength(col)"
              :show-word-limit="!!maxLength(col)"
              style="width:240px" :placeholder="placeholderFor(col)" />

            <span v-if="limitHint(col)" class="limit-hint">{{ limitHint(col) }}</span>
          </div>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog = false" size="small">取消</el-button>
        <el-button type="primary" @click="onSave" size="small" :loading="saving">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 字段修改弹窗 (新增走表结构表格底部内联行) -->
    <el-dialog title="修改字段" :visible.sync="colDialog" width="560px">
      <el-form :model="colForm" label-width="100px" size="small">
        <el-form-item label="字段名" required>
          <el-input v-model="colForm.columnName" style="width:220px"
            placeholder="字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="colForm.dataType" style="width:160px" filterable>
            <el-option v-for="t in ddlTypes" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needLen(colForm)" label="长度">
          <el-input-number v-model="colForm.length" :min="1" :max="16000"
            :controls="false" style="width:100px" />
        </el-form-item>
        <el-form-item v-if="needDec(colForm)" label="小数位">
          <el-input-number v-model="colForm.decimal" :min="0" :max="30"
            :controls="false" style="width:100px" />
        </el-form-item>
        <el-form-item v-if="isIntType(colForm) || needDec(colForm)" label="无符号">
          <el-switch v-model="colForm.unsigned" />
        </el-form-item>
        <el-form-item label="允许NULL">
          <el-switch v-model="colForm.nullable" />
        </el-form-item>
        <el-form-item label="默认值">
          <el-input v-model="colForm.defaultValue" style="width:220px"
            placeholder="可留空; 时间类型可用 CURRENT_TIMESTAMP" />
        </el-form-item>
        <el-form-item label="注释">
          <el-input v-model="colForm.comment" style="width:220px" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="colDialog = false" size="small">取消</el-button>
        <el-button type="primary" @click="saveCol" size="small" :loading="colSaving">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 索引管理弹窗 -->
    <el-dialog title="索引管理" :visible.sync="indexDialog" width="680px">
      <el-form :inline="true" size="small" style="margin-bottom:8px">
        <el-form-item label="索引名">
          <el-input v-model="indexForm.indexName" style="width:150px" placeholder="留空自动生成" />
        </el-form-item>
        <el-form-item label="字段">
          <el-select v-model="indexForm.columns" multiple filterable style="width:200px" placeholder="选择字段(可多选)">
            <el-option v-for="c in columns" :key="c.columnName" :value="c.columnName" :label="c.columnName" />
          </el-select>
        </el-form-item>
        <el-form-item label="唯一">
          <el-switch v-model="indexForm.unique" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" icon="el-icon-plus" @click="onAddIndex">添加</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="indexes" border v-loading="indexLoading" size="small">
        <el-table-column prop="indexName" label="索引名" min-width="140" />
        <el-table-column label="字段" min-width="180">
          <template slot-scope="s">{{ (s.row.columns || []).join(', ') }}</template>
        </el-table-column>
        <el-table-column label="唯一" width="70">
          <template slot-scope="s">{{ s.row.unique ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template slot-scope="s">
            <el-button v-if="s.row.indexName !== 'PRIMARY'" size="mini" type="danger"
              @click="onDropIndex(s.row)">删除</el-button>
            <span v-else style="color:#909399">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script>
import { listDataSource, listTables, listColumns,
         browseData, insertRow, updateRow, deleteRow,
         addColumn, updateColumn, dropColumn,
         listIndex, addIndex, dropIndex } from '@/api/datamove'

export default {
  data () {
    return {
      datasources: [],
      tables: [],
      columns: [],
      dsId: null,
      tableName: null,
      activeTab: 'data',
      structLoading: false,
      dataLoading: false,
      dataRows: [],
      dataTotal: 0,
      query: { pageNum: 1, pageSize: 10, orderBy: null, orderDir: 'asc' },
      dialog: false,
      saving: false,
      editForm: {},
      editPk: null, // null=新增; 有值=编辑(主键列旧值)
      fieldErrors: {}, // 后端返回的错误, 定位到具体字段内联显示
      cityList: [
        '北京', '上海', '广州', '深圳', '天津', '重庆',
        '成都', '杭州', '武汉', '西安', '南京', '长沙',
        '沈阳', '哈尔滨', '大连', '青岛', '济南', '郑州',
        '合肥', '福州', '厦门', '昆明', '贵阳', '兰州',
        '南昌', '南宁', '太原', '石家庄', '长春', '海口',
        '呼和浩特', '银川', '西宁', '乌鲁木齐', '拉萨', '苏州',
        '无锡', '宁波', '温州', '佛山', '东莞', '珠海'
      ],
      /* ---- DDL 表结构管理 ---- */
      ddlTypes: [
        'int', 'bigint', 'smallint', 'tinyint', 'decimal', 'float', 'double',
        'varchar', 'char', 'text', 'date', 'datetime', 'timestamp', 'time',
        'year', 'json', 'blob'
      ],
      colDialog: false,
      colSaving: false,
      colEditing: null, // null=新增; 有值=修改(原字段行)
      showNewCol: false, // 表结构底部内联新增行
      colForm: { columnName: '', dataType: 'varchar', length: 50, decimal: 2, unsigned: false, nullable: true, defaultValue: '', comment: '' },
      indexDialog: false,
      indexLoading: false,
      indexes: [],
      indexForm: { indexName: '', unique: false, columns: [] }
    }
  },
  computed: {
    pkColumn () {
      const pk = this.columns.find(c => c.columnKey === 'PRI')
      return pk ? pk.columnName : null
    },
    hasPk () { return !!this.pkColumn },
    /* 表单校验规则: 必填字段 (错误内联显示在输入框下方)
       坑: 规则里带 whitespace 会让 async-validator 不再用「仅必填」校验器, 而是走默认的
       string 类型校验器 —— 数字输入框(el-input-number)的值是 number, 会被判成
       "user_id is not a string" 而误报「必填项, 不能为空」。所以数字字段只保留 required。 */
    editRules () {
      const rules = {}
      for (const col of this.editCols) {
        if (!this.isRequired(col)) continue
        const base = { required: true, message: '必填项, 不能为空', trigger: 'blur' }
        rules[col.columnName] = [this.isNumber(col) ? base : Object.assign({ whitespace: true }, base)]
      }
      return rules
    },
    editCols () {
      // 新增时: 自增主键列(如ID)不显示, 由数据库自动生成; 编辑时全部显示
      if (this.editPk) return this.columns
      return this.columns.filter(c =>
        !(c.columnKey === 'PRI' && /auto_increment/i.test(c.extra || '')))
    },
    /* 表结构行: 底部追加一条内联新增行 */
    structRows () {
      const rows = this.columns.map(c => Object.assign({}, c))
      if (this.showNewCol) rows.push({ __isNew: true })
      return rows
    }
  },
  mounted () {
    listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
  },
  methods: {
    onDsChange () {
      this.tableName = null
      this.tables = []
      this.columns = []
      this.dataRows = []
      if (!this.dsId) return
      listTables(this.dsId).then(r => { this.tables = r.data || [] }).catch(() => {})
    },
    onTableChange () {
      if (!this.tableName) return
      this.query.pageNum = 1
      this.query.orderBy = null
      this.query.orderDir = 'asc'
      this.showNewCol = false
      this.loadColumns()
      this.loadData()
      this.$nextTick(() => { this.$refs.dataTable && this.$refs.dataTable.clearSort() })
    },
    loadColumns () {
      this.structLoading = true
      listColumns(this.dsId, this.tableName)
        .then(r => { this.columns = r.data || [] })
        .catch(() => { this.columns = [] })
        .finally(() => { this.structLoading = false })
    },
    loadData () {
      if (!this.dsId || !this.tableName) return
      this.dataLoading = true
      browseData(this.dsId, this.tableName, this.query)
        .then(r => {
          this.dataRows = r.data.rows || []
          this.dataTotal = r.data.total || 0
        })
        .catch(() => { this.dataRows = []; this.dataTotal = 0 })
        .finally(() => { this.dataLoading = false })
    },
    onSizeChange () {
      this.query.pageNum = 1
      this.loadData()
    },
    /* 点击列头排序 (后端排序, 翻页保持排序) */
    onSortChange ({ prop, order }) {
      this.query.orderBy = order ? prop : null
      this.query.orderDir = order === 'descending' ? 'desc' : 'asc'
      this.query.pageNum = 1
      this.loadData()
    },
    /* ---- CRUD ---- */
    onAdd () {
      this.editPk = null
      const cols = this.editCols
      // 先把所有字段 key 建出来(值 null): Vue2 对"后来才新增的对象属性"不做响应式,
      // 不预建 key 的话输入框的值可能不被表单接管, 保存时就漏了这个字段
      const form = {}
      for (const col of cols) form[col.columnName] = null
      // 再预填数据库默认值 (如 status=1, score=0.00); 自增主键已由 editCols 过滤, id 由数据库生成
      for (const col of cols) {
        if (col.defaultValue === null || col.defaultValue === undefined) continue
        // CURRENT_TIMESTAMP 之类表达式默认值不能预填进日期控件, 留空让数据库生成
        if (this.isDateLike(col) && /CURRENT_TIMESTAMP|now\(\)/i.test(String(col.defaultValue))) continue
        form[col.columnName] = this.isNumber(col) ? Number(col.defaultValue) : col.defaultValue
      }
      this.editForm = form
      this.fieldErrors = {}
      this.dialog = true
    },
    onEdit (row) {
      if (!this.hasPk) return
      this.editPk = { [this.pkColumn]: row[this.pkColumn] }
      const form = {}
      for (const col of this.columns) {
        let v = row[col.columnName]
        // 数字列转 Number, 供 el-input-number 正确回显
        if (v !== null && v !== undefined && this.isNumber(col)) v = Number(v)
        // 日期列: ISO 带 T 的格式转成 yyyy-MM-dd HH:mm:ss, 供日期控件正确回显
        if (v !== null && v !== undefined && this.isDatetime(col)) {
          v = String(v).replace('T', ' ').slice(0, 19)
        }
        form[col.columnName] = v
      }
      this.editForm = form
      this.fieldErrors = {}
      this.dialog = true
    },
    onSave () {
      if (!this.dsId || !this.tableName) return
      this.fieldErrors = {}
      // 前端必填校验: 错误内联显示在对应输入框下方, 不弹窗
      this.$refs.editFormRef.validate(valid => {
        if (!valid) return
        this.saving = true
        let p
        if (this.editPk) {
          p = updateRow(this.dsId, this.tableName, { pk: this.editPk, values: this.editForm })
        } else {
          p = insertRow(this.dsId, this.tableName, this.editForm)
        }
        p.then(() => {
          this.$message.success(this.editPk ? '已修改' : '已新增')
          this.dialog = false
          this.loadData()
        }).catch(err => {
          // 后端 SQL 错误: 解析出字段名, 内联显示在对应输入框下方; 解析不出才弹提示
          this.showFieldError(err && err.message)
        }).finally(() => { this.saving = false })
      })
    },
    /* 从后端错误消息中解析字段名, 把错误显示到对应字段的输入框下 */
    showFieldError (msg) {
      if (!msg) return
      // 匹配 "for column 'xxx'" / "column 'xxx'" / "Field 'xxx'"
      const m = /(?:for )?column '([^']+)'|Field '([^']+)'/.exec(msg)
      const colName = m && (m[1] || m[2])
      if (colName && this.editCols.some(c => c.columnName === colName)) {
        this.$set(this.fieldErrors, colName, msg.replace(/^插入失败: |^修改失败: /, ''))
      } else {
        this.$message.error(msg)
      }
    },
    onDel (row) {
      if (!this.hasPk) return
      this.$confirm(`确认删除 ${this.tableName} 中 ${this.pkColumn}=${row[this.pkColumn]} 的这一行?`, '删除确认', { type: 'warning' })
        .then(() => deleteRow(this.dsId, this.tableName, { [this.pkColumn]: row[this.pkColumn] }))
        .then(() => {
          this.$message.success('已删除')
          // 若删除后当前页只剩空页, 回退一页
          if (this.dataRows.length === 1 && this.query.pageNum > 1) this.query.pageNum--
          this.loadData()
        })
        .catch(err => { if (err !== 'cancel' && err && err.message) this.$message.error('删除失败:' + err.message) })
    },
    /* ---- DDL: 字段管理 ---- */
    needLen (f) { return ['varchar', 'char', 'varbinary', 'binary', 'decimal', 'numeric', 'float', 'double'].includes(f.dataType) },
    needDec (f) { return ['decimal', 'numeric', 'float', 'double'].includes(f.dataType) },
    isIntType (f) { return ['int', 'integer', 'bigint', 'smallint', 'tinyint'].includes(f.dataType) },
    onAddCol () {
      // 表结构表格底部显示内联新增行, 不弹窗
      this.colEditing = null
      this.colForm = { columnName: '', dataType: 'varchar', length: 50, decimal: 2, unsigned: false, nullable: true, defaultValue: '', comment: '' }
      this.showNewCol = true
    },
    onEditCol (row) {
      // 从 columnType 解析: 如 varchar(50) / decimal(10,2) unsigned
      const m = /^(\w+)(?:\((\d+)(?:,(\d+))?\))?( unsigned)?/.exec((row.columnType || row.dataType || '').toLowerCase())
      this.colForm = {
        columnName: row.columnName,
        dataType: m ? m[1] : (row.dataType || 'varchar').toLowerCase(),
        length: m && m[2] ? parseInt(m[2]) : null,
        decimal: m && m[3] ? parseInt(m[3]) : null,
        unsigned: !!(m && m[4]),
        nullable: row.nullable === 'YES',
        defaultValue: row.defaultValue === null ? '' : String(row.defaultValue),
        comment: row.columnComment || ''
      }
      this.colEditing = row
      this.colDialog = true
    },
    saveCol () {
      const f = this.colForm
      if (!f.columnName || !/^[A-Za-z0-9_]+$/.test(f.columnName)) {
        this.$message.error('字段名只能包含字母/数字/下划线'); return
      }
      if (!f.dataType) { this.$message.error('请选择字段类型'); return }
      if (this.needLen(f) && (!f.length || f.length < 1)) {
        this.$message.error('请填写长度'); return
      }
      this.colSaving = true
      const isEdit = !!this.colEditing
      const body = {
        columnName: f.columnName, dataType: f.dataType, length: f.length, decimal: f.decimal,
        unsigned: f.unsigned, nullable: f.nullable, defaultValue: f.defaultValue, comment: f.comment
      }
      const p = isEdit
        ? updateColumn(this.dsId, this.tableName, Object.assign({ oldColumnName: this.colEditing.columnName }, body))
        : addColumn(this.dsId, this.tableName, body)
      p.then(() => {
        this.$message.success(isEdit ? '字段已修改' : '字段已新增')
        if (isEdit) this.colDialog = false
        else this.showNewCol = false
        this.loadColumns()   // 刷新表结构
        this.loadData()      // 数据列表的列也变了
      }).catch(() => {}).finally(() => { this.colSaving = false })
    },
    onDropCol (row) {
      this.$confirm(`确认删除字段 [${row.columnName}]? 该列及列内数据将一并删除, 不可恢复`, '删除字段', { type: 'warning' })
        .then(() => dropColumn(this.dsId, this.tableName, { columnName: row.columnName }))
        .then(() => {
          this.$message.success('字段已删除')
          this.loadColumns()
          this.loadData()
        })
        .catch(() => {})
    },
    /* ---- DDL: 索引管理 ---- */
    onManageIndex () {
      this.indexDialog = true
      this.loadIndexes()
    },
    loadIndexes () {
      if (!this.dsId || !this.tableName) return
      this.indexLoading = true
      listIndex(this.dsId, this.tableName)
        .then(r => { this.indexes = r.data || [] })
        .catch(() => { this.indexes = [] })
        .finally(() => { this.indexLoading = false })
    },
    onAddIndex () {
      if (!this.indexForm.columns.length) { this.$message.error('请选择索引字段'); return }
      addIndex(this.dsId, this.tableName, this.indexForm)
        .then(() => {
          this.$message.success('索引已创建')
          this.indexForm = { indexName: '', unique: false, columns: [] }
          this.loadIndexes()
          this.loadColumns()   // 索引可能影响 columnKey 显示
        })
        .catch(() => {})
    },
    onDropIndex (row) {
      this.$confirm(`确认删除索引 [${row.indexName}]?`, '删除索引', { type: 'warning' })
        .then(() => dropIndex(this.dsId, this.tableName, { indexName: row.indexName }))
        .then(() => {
          this.$message.success('索引已删除')
          this.loadIndexes()
          this.loadColumns()
        })
        .catch(() => {})
    },
    /* ---- 工具 ---- */
    /* 类型判断 */
    isNumber (col) { return ['int', 'bigint', 'smallint', 'tinyint', 'integer', 'decimal', 'float', 'double', 'numeric'].includes((col.dataType || '').toLowerCase()) },
    isIntCol (col) { return ['int', 'bigint', 'smallint', 'tinyint', 'integer'].includes((col.dataType || '').toLowerCase()) },
    isDatetime (col) { return ['datetime', 'timestamp'].includes((col.dataType || '').toLowerCase()) },
    isDate (col) { return (col.dataType || '').toLowerCase() === 'date' },
    isDateLike (col) { return this.isDatetime(col) || this.isDate(col) },
    /* 城市字段: 列名是 city 或注释含"城市" */
    isCity (col) {
      const name = (col.columnName || '').toLowerCase()
      return name === 'city' || /城市/.test(col.columnComment || '')
    },
    numberPrecision (col) {
      const m = /\((\d+),(\d+)\)/.exec(col.columnType || '')
      return m ? parseInt(m[2]) : 0
    },
    maxLength (col) {
      const m = /(\w+)\((\d+)\)$/.exec(col.columnType || '')
      return m && ['varchar', 'char', 'varbinary'].includes(m[1].toLowerCase()) ? parseInt(m[2]) : null
    },
    /* 整数范围限制 */
    intRange (col) {
      const t = (col.columnType || col.dataType || '').toLowerCase()
      const unsigned = /unsigned/.test(t)
      switch ((col.dataType || '').toLowerCase()) {
        case 'tinyint': return unsigned ? [0, 255] : [-128, 127]
        case 'smallint': return unsigned ? [0, 65535] : [-32768, 32767]
        case 'int': case 'integer': return unsigned ? [0, 4294967295] : [-2147483648, 2147483647]
        case 'bigint': return unsigned ? [0, 18446744073709551615] : [-9223372036854775808, 9223372036854775807]
      }
      return null
    },
    /* 输入框后面的限制提示 (int 等整数无范围提示需求, 不显示) */
    limitHint (col) {
      const len = this.maxLength(col)
      if (len) return `≤${len}字`
      const m = /\((\d+),(\d+)\)/.exec(col.columnType || '')
      if (m) return `最多${m[2]}位小数`
      if (this.isDatetime(col)) return 'yyyy-MM-dd HH:mm:ss'
      if (this.isDate(col)) return 'yyyy-MM-dd'
      return null
    },
    isRequired (col) {
      return col.nullable === 'NO' && col.defaultValue === null &&
        !/auto_increment/i.test(col.extra || '')
    },
    fmtVal (v) {
      if (v === null || v === undefined) return 'NULL'
      return String(v)
    },
    placeholderFor (col) {
      if (col.columnKey === 'PRI' && /auto_increment/i.test(col.extra || '')) return '自增, 留空自动生成'
      if (this.isDateLike(col) && /CURRENT_TIMESTAMP|now\(\)/i.test(String(col.defaultValue || ''))) return '留空=自动取当前时间'
      if (col.nullable === 'NO' && col.defaultValue === null) return '必填'
      return '可留空'
    }
  }
}
</script>

<style scoped>
/* 输入框后面的限制提示 */
.limit-hint {
  margin-left: 8px;
  flex-shrink: 0;
  color: #909399;
  font-size: 12px;
  white-space: nowrap;
}
/* 整数: 带加减按钮 (按钮占位约80px) */
.num-with-ctrl {
  width: 200px;
}
/* 小数: 无按钮 */
.num-plain {
  width: 240px;
}
/* 操作列按钮不换行, 缩小按钮间距 */
/deep/ .op-col .cell {
  white-space: nowrap;
}
/deep/ .op-col .el-button + .el-button {
  margin-left: 4px;
}
/deep/ .op-col .el-button {
  padding: 7px 10px;
}
/* 内联新增行整行淡黄高亮 */
/deep/ .el-table .new-col-row {
  background: #fdfbe6;
}
</style>
