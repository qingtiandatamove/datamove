<template>
  <el-card>
    <div slot="header" class="clearfix">
      <span>角色管理</span>
      <el-button v-if="$hasPerm('system:role:add')" style="float:right" type="primary" size="mini"
        icon="el-icon-plus" @click="onAdd">新增角色</el-button>
    </div>

    <el-form :inline="true" :model="query">
      <el-form-item><el-input v-model="query.keyword" placeholder="角色名/标识" size="small" clearable /></el-form-item>
      <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
    </el-form>

    <el-table :data="page.rows" v-loading="loading" border class="role-table">
      <el-table-column prop="roleId" label="ID" width="60" />
      <el-table-column prop="roleName" label="角色名称" />
      <el-table-column prop="roleKey" label="角色标识" width="140" />
      <el-table-column prop="roleSort" label="排序" width="70" />
      <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template slot-scope="s">
          <el-switch v-model="s.row.status" active-value="0" inactive-value="1"
            :disabled="!$hasPerm('system:role:edit') || s.row.roleKey === 'admin'"
            @change="onStatus(s.row)" />
        </template>
      </el-table-column>
      <!-- 操作列: 高频在前(权限/编辑), 危险动作(删除)放最后; 内置 admin 不可删/不可停用 -->
      <el-table-column label="操作" min-width="230" fixed="right">
        <template slot-scope="s">
          <el-button v-if="$hasPerm('system:role:grant')" size="mini" type="warning" icon="el-icon-s-check"
            @click="onGrant(s.row)">权限</el-button>
          <el-button v-if="$hasPerm('system:role:edit')" size="mini" type="primary" icon="el-icon-edit"
            @click="onEdit(s.row)">编辑</el-button>
          <el-tooltip v-if="$hasPerm('system:role:remove')" :disabled="s.row.roleKey !== 'admin'"
            content="内置超级管理员角色不可删除" placement="top">
            <span class="op-del-wrap">
              <el-button size="mini" type="danger" icon="el-icon-delete"
                :disabled="s.row.roleKey === 'admin'" @click="onDel(s.row)">删除</el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      style="margin-top:16px" background layout="prev, pager, next, total"
      :total="page.total" :page-size="query.pageSize" :current-page.sync="query.pageNum" @current-change="load" />

    <el-dialog :title="form.roleId ? '编辑角色' : '新增角色'" :visible.sync="dialog" width="500px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="角色名称"><el-input v-model="form.roleName" /></el-form-item>
        <el-form-item label="角色标识">
          <!-- role_key 是权限判断依据(admin 靠它短路), 建好后不允许改 -->
          <el-input v-model="form.roleKey" :disabled="!!form.roleId" placeholder="如: operator" />
        </el-form-item>
        <el-form-item label="显示顺序"><el-input-number v-model="form.roleSort" :min="0" :max="999" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 权限分配: 目录 -> 菜单 -> 按钮 三级树, 勾选后写 sys_role_menu -->
    <el-dialog :title="`分配权限 - ${grantRole.roleName}`" :visible.sync="grantDialog" width="560px">
      <div class="perm-tip">
        勾选该角色可访问的菜单与按钮。保存后立即生效,
        拥有该角色的用户<b>重新登录</b>后菜单与按钮同步刷新。
        <span v-if="grantRole.roleKey === 'admin'" class="perm-warn">
          注意: 内置 admin 角色拥有全部权限(*:*:*), 这里的勾选不影响实际权限。
        </span>
      </div>
      <div class="perm-toolbar">
        <el-button size="mini" @click="toggleAll(true)">全选</el-button>
        <el-button size="mini" @click="toggleAll(false)">清空</el-button>
        <el-button size="mini" type="text" @click="expandAll = !expandAll">{{ expandAll ? '收起' : '展开' }}</el-button>
      </div>
      <el-tree
        ref="permTree"
        v-loading="grantLoading"
        :data="menuTree"
        :props="{ children: 'children', label: 'menuName' }"
        node-key="menuId"
        show-checkbox
        :default-expand-all="expandAll"
        :default-checked-keys="checkedMenuIds"
        class="perm-tree">
        <span slot-scope="{ node, data }" class="perm-node">
          <span>{{ data.menuName }}</span>
          <el-tag v-if="data.menuType === 'F'" size="mini" type="info" class="perm-perms">{{ data.perms }}</el-tag>
          <el-tag v-else-if="data.perms" size="mini" class="perm-perms">{{ data.perms }}</el-tag>
        </span>
      </el-tree>
      <div slot="footer">
        <el-button @click="grantDialog=false">取消</el-button>
        <el-button type="primary" :loading="grantSaving" @click="onSaveGrant">保 存</el-button>
      </div>
    </el-dialog>
  </el-card>
</template>

<script>
import {
  pageRole, addRole, updateRole, deleteRole, changeRoleStatus,
  getRoleMenus, saveRoleMenus, listMenuTree
} from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', pageNum: 1, pageSize: 10 },
      page: { rows: [], total: 0 }, loading: false, dialog: false, form: {},
      menuTree: [],
      grantDialog: false, grantRole: {}, checkedMenuIds: [],
      grantLoading: false, grantSaving: false, expandAll: true
    }
  },
  mounted () { this.load(); this.loadMenuTree() },
  methods: {
    load () { this.loading = true; pageRole(this.query).then(r => { this.page = r.data }).catch(() => {}).finally(() => this.loading = false) },
    loadMenuTree () { listMenuTree().then(r => { this.menuTree = r.data || [] }).catch(() => {}) },

    onAdd () { this.dialog = true; this.form = { roleSort: 99, status: '0' } },
    onEdit (row) { this.dialog = true; this.form = Object.assign({}, row) },
    onSave () {
      const api = this.form.roleId ? updateRole : addRole
      api(this.form).then(() => { this.$message.success('已保存'); this.dialog = false; this.load() })
        .catch(err => { this.$message.error('保存失败:' + (err.message || '')) })
    },
    onDel (row) {
      this.$confirm(`删除角色 [${row.roleName}]?`, '提示', { type: 'warning' })
        .then(() => deleteRole(row.roleId)).then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    onStatus (row) { changeRoleStatus(row.roleId, row.status) },

    onGrant (row) {
      this.grantRole = row
      this.checkedMenuIds = []
      this.grantDialog = true
      this.grantLoading = true
      getRoleMenus(row.roleId).then(r => {
        /* 只回显「叶子被勾」的状态: 父节点交给 el-tree 自动推导半选,
           否则父节点全勾会连带把没勾的子项也选上 */
        this.checkedMenuIds = r.data || []
        if (this.$refs.permTree) this.$refs.permTree.setCheckedKeys(this.checkedMenuIds)
      }).catch(() => {}).finally(() => { this.grantLoading = false })
    },
    toggleAll (checked) {
      if (!this.$refs.permTree) return
      const all = []
      const walk = list => (list || []).forEach(m => { all.push(m.menuId); walk(m.children) })
      walk(this.menuTree)
      this.$refs.permTree.setCheckedKeys(checked ? all : [])
    },
    onSaveGrant () {
      if (!this.$refs.permTree) return
      const tree = this.$refs.permTree
      // 半选的父节点也要提交: 否则只勾了子按钮、父菜单没勾, 下次回显会丢层级
      const menuIds = tree.getCheckedKeys().concat(tree.getHalfCheckedKeys())
      this.grantSaving = true
      saveRoleMenus(this.grantRole.roleId, menuIds)
        .then(() => { this.$message.success('已分配权限'); this.grantDialog = false })
        .catch(() => {})
        .finally(() => { this.grantSaving = false })
    }
  }
}
</script>

<style scoped>
.perm-tip { color: #606266; font-size: 12px; line-height: 18px; margin-bottom: 10px }
.perm-warn { color: #E6A23C; display: block; margin-top: 4px }
.perm-toolbar { margin-bottom: 8px }
.perm-tree { max-height: 52vh; overflow: auto; border: 1px solid var(--color-border, #dcdfe6); border-radius: 4px; padding: 8px }
.perm-node { font-size: 13px }
.perm-perms { margin-left: 8px; font-family: Menlo, Consolas, monospace }

/* 操作列: 与用户管理/同步任务保持一致的紧凑排布 */
.role-table >>> td:last-child .cell {
  display: flex; flex-wrap: nowrap; align-items: center; gap: 8px; white-space: nowrap;
}
.role-table >>> td:last-child .cell .el-button { flex-shrink: 0; padding: 5px 8px; font-size: 12px }
.role-table >>> td:last-child .cell .el-button + .el-button { margin-left: 0 }
.op-del-wrap { display: inline-flex; flex-shrink: 0 }
</style>
