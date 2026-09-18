<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix">
        <span>数据源管理</span>
        <el-button type="primary" icon="el-icon-plus" size="mini" style="float:right" @click="onAdd">新增数据源</el-button>
      </div>

      <el-form :inline="true" :model="query">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="名称/IP/数据库" size="small" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="page.rows" v-loading="loading" border>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="datasourceName" label="名称" />
        <el-table-column prop="host" label="IP" />
        <el-table-column prop="port" label="端口" width="70" />
        <el-table-column prop="dbName" label="数据库" />
        <el-table-column prop="username" label="账号" />
        <el-table-column prop="showPassword" label="密码" />
        <el-table-column prop="remark" label="备注" />
        <el-table-column label="操作" width="230">
          <template slot-scope="s">
            <el-button size="mini" @click="onTest(s.row)">测试</el-button>
            <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
            <el-button size="mini" type="danger" @click="onDel(s.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px"
        background layout="prev, pager, next, total"
        :total="page.total" :page-size="query.pageSize"
        :current-page.sync="query.pageNum" @current-change="load" />
    </el-card>

    <!-- 表单弹窗 -->
    <el-dialog :title="form.id ? '编辑数据源' : '新增数据源'" :visible.sync="dialog" width="520px" @closed="resetForm">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="datasourceName"><el-input v-model="form.datasourceName" /></el-form-item>
        <el-form-item label="IP" prop="host"><el-input v-model="form.host" placeholder="如:127.0.0.1" /></el-form-item>
        <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="数据库" prop="dbName"><el-input v-model="form.dbName" /></el-form-item>
        <el-form-item label="账号" prop="username"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码" :prop="form.id ? '' : 'password'">
          <el-input v-model="form.password" type="password" :placeholder="form.id ? '不修改请留空' : ''" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="warning" @click="onTestDialog" :loading="testing">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保 存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { pageDataSource, addDataSource, updateDataSource, deleteDataSource, testDataSource } from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', pageNum: 1, pageSize: 10 },
      page: { rows: [], total: 0 },
      loading: false, dialog: false, saving: false, testing: false,
      form: {},
      rules: {
        datasourceName: [{ required: true, message: '必填' }],
        host:           [{ required: true, message: '必填' }],
        port:           [{ required: true, message: '必填' }],
        dbName:         [{ required: true, message: '必填' }],
        username:       [{ required: true, message: '必填' }],
        password:       [{ required: true, message: '必填' }]
      }
    }
  },
  mounted () { this.load() },
  methods: {
    load () {
      this.loading = true
      pageDataSource(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => { this.loading = false })
    },
    onAdd () { this.dialog = true; this.form = { port: 3306 } },
    onEdit (row) { this.dialog = true; this.form = Object.assign({}, row); delete this.form.password },
    onTest (row) {
      testDataSource(row).then(r => {
        this.$message[r.data ? 'success' : 'error'](r.data ? '连接成功' : '连接失败')
      }).catch(err => {
        // 测试连接超时/失败的友好提示, 避免抛到全局触发红屏
        const msg = err && err.message ? err.message : '连接失败'
        this.$message.error(msg.includes('超时') ? '连接超时,请检查 IP/端口 是否可达' : '连接失败:' + msg)
      })
    },
    onTestDialog () {
      this.$refs.form.validate(ok => {
        if (!ok) return
        this.testing = true
        testDataSource(this.form).then(r => {
          this.$message[r.data ? 'success' : 'error'](r.data ? '连接成功' : '连接失败,请检查配置')
        }).catch(err => {
          const msg = err && err.message ? err.message : '连接失败'
          this.$message.error(msg.includes('超时') ? '连接超时,请检查 IP/端口/账号/密码 是否正确' : '连接失败:' + msg)
        }).finally(() => { this.testing = false })
      })
    },
    onSave () {
      this.$refs.form.validate(ok => {
        if (!ok) return
        this.saving = true
        const api = this.form.id ? updateDataSource : addDataSource
        api(this.form).then(() => {
          this.$message.success('保存成功')
          this.dialog = false
          this.load()
        }).finally(() => { this.saving = false })
      })
    },
    onDel (row) {
      this.$confirm(`确认删除数据源 [${row.datasourceName}]?`, '提示', { type: 'warning' }).then(() => {
        return deleteDataSource(row.id)
      }).then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    resetForm () { this.form = {} }
  }
}
</script>
