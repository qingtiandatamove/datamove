<template>
  <el-card>
    <div slot="header"><span>个人中心 / 修改密码</span></div>
    <el-form :model="form" label-width="100px" style="max-width:480px">
      <el-form-item label="账号">
        <el-input v-model="user.userName" disabled />
      </el-form-item>
      <el-form-item label="原密码">
        <el-input v-model="form.oldPassword" type="password" />
        <transition name="fade">
          <div v-if="errorMsg" class="field-tip">
            <i class="el-icon-info"></i>
            <span>{{ errorMsg }}</span>
          </div>
        </transition>
      </el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" /></el-form-item>
      <el-form-item><el-button type="primary" @click="onSave">提交修改</el-button></el-form-item>
    </el-form>
  </el-card>
</template>

<script>
import { changePassword } from '@/api/auth'

export default {
  data () { return { user: {}, errorMsg: '', errorTimer: null, form: { oldPassword: '', newPassword: '', confirm: '' } } },
  mounted () { this.user = this.$store.state.user || {} },
  methods: {
    showError (msg) {
      this.errorMsg = msg
      clearTimeout(this.errorTimer)
      this.errorTimer = setTimeout(() => { this.errorMsg = '' }, 4000)
    },
    onSave () {
      if (!this.form.oldPassword || !this.form.newPassword) { this.showError('请填写完整'); return }
      if (this.form.newPassword !== this.form.confirm) { this.showError('两次新密码不一致'); return }
      changePassword({ userId: this.user.userId, oldPassword: this.form.oldPassword, newPassword: this.form.newPassword })
        .then(() => { this.$message.success('修改成功,请重新登录'); this.$router.push('/login'); this.$store.dispatch('logout') })
        .catch(err => { this.showError(err.message || '修改失败') })
    }
  }
}
</script>

<style scoped>
.field-tip {
  margin-top: 6px;
  padding: 6px 10px;
  background: #f4f4f5;
  color: #909399;
  font-size: 12px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  line-height: 1.4;
}
.field-tip i { margin-right: 6px; font-size: 13px; }
.fade-enter-active, .fade-leave-active { transition: opacity .2s ease; }
.fade-enter, .fade-leave-to { opacity: 0; }
</style>