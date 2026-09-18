<template>
  <el-card>
    <div slot="header" class="clearfix">
      <span>用户管理</span>
      <el-button style="float:right" type="primary" size="mini" icon="el-icon-plus" @click="onAdd">新增用户</el-button>
    </div>

    <el-form :inline="true" :model="query">
      <el-form-item><el-input v-model="query.keyword" placeholder="账号/昵称" size="small" clearable /></el-form-item>
      <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
    </el-form>

    <el-table :data="page.rows" v-loading="loading" border>
      <el-table-column prop="userId" label="ID" width="60" />
      <el-table-column prop="userName" label="账号" />
      <el-table-column prop="nickName" label="昵称" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="phonenumber" label="手机" />
      <el-table-column label="状态" width="100">
        <template slot-scope="s">
          <el-switch v-model="s.row.status" active-value="0" inactive-value="1" @change="onStatus(s.row)" />
        </template>
      </el-table-column>
      <el-table-column prop="loginDate" label="最后登录" width="160" />
      <el-table-column label="操作" width="220">
        <template slot-scope="s">
          <el-button size="mini" type="primary" @click="onEdit(s.row)">编辑</el-button>
          <el-button size="mini" @click="onReset(s.row)">重置密码</el-button>
          <el-button size="mini" type="danger" :disabled="s.row.userId === 1" @click="onDel(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      style="margin-top:16px" background layout="prev, pager, next, total"
      :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />

    <el-dialog :title="form.userId ? '编辑用户' : '新增用户'" :visible.sync="dialog" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="账号"><el-input v-model="form.userName" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickName" /></el-form-item>
        <el-form-item label="密码" v-if="!form.userId">
          <el-input v-model="form.password" placeholder="不填则默认 123456" />
        </el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phonenumber" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave">保 存</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import { pageUser, addUser, updateUser, deleteUser, resetUser, changeUserStatus } from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', pageNum: 1, pageSize: 10 },
      page: { rows: [], total: 0 }, loading: false, dialog: false, form: {}
    }
  },
  mounted () { this.load() },
  methods: {
    load () { this.loading = true; pageUser(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false) },
    onAdd () { this.dialog = true; this.form = {} },
    onEdit (row) { this.dialog = true; this.form = Object.assign({}, row) },
    onSave () {
      const api = this.form.userId ? updateUser : addUser
      api(this.form).then(() => { this.$message.success('已保存'); this.dialog = false; this.load() }).catch(err => { this.$message.error('保存失败:' + (err.message || '')) })
    },
    onDel (row) {
      this.$confirm(`删除用户 [${row.userName}]?`, '提示', { type: 'warning' })
        .then(() => deleteUser(row.userId)).then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    onReset (row) {
      this.$prompt('请输入新密码(留空为默认 123456)', '重置密码', { inputValue: '' }).then(({ value }) => {
        return resetUser(row.userId, value || '123456')
      }).then(() => this.$message.success('已重置')).catch(() => {})
    },
    onStatus (row) { changeUserStatus(row.userId, row.status) }
  }
}
</script>
