<template>
  <el-card>
    <div slot="header" class="clearfix">
      <span>用户管理</span>
      <el-button v-if="$hasPerm('system:user:add')" style="float:right" type="primary" size="mini"
        icon="el-icon-plus" @click="onAdd">新增用户</el-button>
    </div>

    <el-form :inline="true" :model="query">
      <el-form-item><el-input v-model="query.keyword" placeholder="账号/昵称" size="small" clearable /></el-form-item>
      <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
    </el-form>

    <el-table :data="page.rows" v-loading="loading" border class="user-table">
      <el-table-column prop="userId" label="ID" width="60" />
      <el-table-column prop="userName" label="账号" />
      <el-table-column prop="nickName" label="昵称" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="phonenumber" label="手机" />
      <!-- 角色: 授权结果的直观反馈, 否则配完不知道配没配上 -->
      <el-table-column label="角色" min-width="130">
        <template slot-scope="s">
          <el-tag v-for="r in (s.row.roleNames || [])" :key="r" size="mini" type="info" class="role-tag">{{ r }}</el-tag>
          <span v-if="!s.row.roleNames || !s.row.roleNames.length" class="no-role">未授权</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template slot-scope="s">
          <el-switch v-model="s.row.status" active-value="0" inactive-value="1"
            :disabled="!$hasPerm('system:user:edit')" @change="onStatus(s.row)" />
        </template>
      </el-table-column>
      <el-table-column prop="loginDate" label="最后登录" width="160" />
      <!-- 操作列: 按钮高频在前(编辑/重置密码/授权), 危险动作(删除)放最后;
           列宽用 min-width 给足(四个按钮 + 间距实测约 320px), 窄屏交给表格整体横向滚动,
           不在单元格内部开滚动条 —— 避免行高被挤占、按钮被裁切;
           内置 admin(userId=1) 禁止删除, 用 tooltip 说明禁用原因, 而不是让人点了没反应 -->
      <el-table-column label="操作" min-width="320" fixed="right">
        <template slot-scope="s">
          <el-button v-if="$hasPerm('system:user:edit')" size="mini" type="primary" icon="el-icon-edit"
            @click="onEdit(s.row)">编辑</el-button>
          <el-button v-if="$hasPerm('system:user:edit')" size="mini" icon="el-icon-key"
            @click="onReset(s.row)">重置密码</el-button>
          <el-button v-if="$hasPerm('system:user:grant')" size="mini" type="warning" icon="el-icon-s-check"
            @click="onGrant(s.row)">授权</el-button>
          <el-tooltip v-if="$hasPerm('system:user:remove')" :disabled="s.row.userId !== 1"
            content="内置超级管理员不可删除" placement="top">
            <span class="op-del-wrap">
              <el-button size="mini" type="danger" icon="el-icon-delete"
                :disabled="s.row.userId === 1" @click="onDel(s.row)">删除</el-button>
            </span>
          </el-tooltip>
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
        <!-- 角色也放进新增/编辑: 新建用户后必须顺手授权, 否则该用户登录后没有任何权限 -->
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple style="width:100%"
            placeholder="不授权则该用户登录后无任何权限">
            <el-option v-for="r in roles" :key="r.roleId" :label="r.roleName" :value="r.roleId" />
          </el-select>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 授权弹窗: 只管角色分配, 与用户资料分开, 避免改个昵称还会动到权限 -->
    <el-dialog title="用户授权" :visible.sync="grantDialog" width="440px">
      <div class="grant-tip">
        给用户 <b>{{ grantUser.userName }}</b> 分配角色。保存后后端鉴权立即生效,
        该用户<b>重新登录</b>后按钮显隐同步刷新。
      </div>
      <el-checkbox-group v-model="grantRoleIds">
        <div v-for="r in roles" :key="r.roleId" class="grant-item">
          <el-checkbox :label="r.roleId" :disabled="grantUser.userId === 1 && r.roleKey === 'admin'">
            {{ r.roleName }}
            <span class="grant-key">{{ r.roleKey }}</span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <div class="grant-tip grant-warn" v-if="!roles.length">还没有可用角色, 请先在 sys_role 表中初始化角色数据。</div>
      <div slot="footer">
        <el-button @click="grantDialog=false">取消</el-button>
        <el-button type="primary" :loading="grantSaving" @click="onSaveGrant">保 存</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import {
  pageUser, addUser, updateUser, deleteUser, resetUser, changeUserStatus,
  listRoles, getUserRoles, saveUserRoles
} from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', pageNum: 1, pageSize: 10 },
      page: { rows: [], total: 0 }, loading: false, dialog: false, form: {},
      roles: [],
      grantDialog: false, grantUser: {}, grantRoleIds: [], grantSaving: false
    }
  },
  mounted () { this.loadRoles(); this.load() },
  methods: {
    load () { this.loading = true; pageUser(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false) },
    loadRoles () { listRoles().then(r => { this.roles = r.data || [] }).catch(() => {}) },
    onAdd () { this.dialog = true; this.form = { roleIds: [] } },
    onEdit (row) {
      this.dialog = true
      this.form = Object.assign({}, row, { roleIds: [], password: '' })
      // 行数据只带角色名(展示用), 编辑要回填角色ID, 这里现查一次
      getUserRoles(row.userId).then(r => { this.form.roleIds = r.data || [] }).catch(() => {})
    },
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
    onStatus (row) { changeUserStatus(row.userId, row.status) },

    onGrant (row) {
      this.grantUser = row
      this.grantRoleIds = []
      this.grantDialog = true
      getUserRoles(row.userId).then(r => { this.grantRoleIds = r.data || [] }).catch(() => {})
    },
    onSaveGrant () {
      this.grantSaving = true
      saveUserRoles(this.grantUser.userId, this.grantRoleIds)
        .then(() => { this.$message.success('已授权'); this.grantDialog = false; this.load() })
        .catch(() => {})
        .finally(() => { this.grantSaving = false })
    }
  }
}
</script>

<style scoped>
/* 角色标签 */
.role-tag { margin-right: 4px }
.no-role { color: #909399; font-size: 12px }

/* 授权弹窗 */
.grant-tip { color: #606266; font-size: 12px; line-height: 18px; margin-bottom: 12px }
.grant-warn { color: #E6A23C; margin: 8px 0 0 }
.grant-item { line-height: 28px }
.grant-key { color: #909399; font-size: 12px; margin-left: 4px }

/* 用户管理 - 操作列
   与同步任务列表保持同一套排布: 按钮不换行、间距统一交给 gap,
   去掉 element-ui 默认的相邻按钮 10px 左边距(否则会叠加在 gap 上, 间距忽宽忽窄) */
.user-table >>> td:last-child .cell {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  white-space: nowrap;
}
/* 按钮不参与压缩: 横向滚动出现时也要完整可见 */
.user-table >>> td:last-child .cell .el-button {
  flex-shrink: 0;
  padding: 5px 8px;
  font-size: 12px;
}
.user-table >>> td:last-child .cell .el-button + .el-button { margin-left: 0; }
/* 删除按钮外层套了 span 以支持禁用态 tooltip, 不能让它把按钮挤变形 */
.op-del-wrap { display: inline-flex; flex-shrink: 0; }
</style>
