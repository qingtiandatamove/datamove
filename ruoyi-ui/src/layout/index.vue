<template>
  <el-container class="layout-container">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <i class="el-icon-data-analysis"></i>
        <span>DataMove</span>
      </div>
      <el-menu
        background-color="#001529"
        text-color="#fff"
        active-text-color="#007bff"
        router
        :default-active="$route.path">
        <el-menu-item index="/index"><i class="el-icon-house"></i><span>首页</span></el-menu-item>
        <el-submenu index="200">
          <template slot="title"><i class="el-icon-edit-outline"></i><span>数据工作台</span></template>
          <el-menu-item index="/browse"><i class="el-icon-search"></i><span>数据中心</span></el-menu-item>
          <el-menu-item index="/sql"><i class="el-icon-monitor"></i><span>SQL 工作台</span></el-menu-item>
          <el-menu-item index="/sql-log"><i class="el-icon-tickets"></i><span>SQL 执行日志</span></el-menu-item>
        </el-submenu>
        <el-submenu index="100">
          <template slot="title"><i class="el-icon-share"></i><span>数据集成中心</span></template>
          <el-menu-item index="/sync/datasource"><i class="el-icon-collection"></i><span>数据源管理</span></el-menu-item>
          <el-menu-item index="/sync/task"><i class="el-icon-pie-chart"></i><span>同步任务</span></el-menu-item>
          <el-menu-item index="/sync/dashboard"><i class="el-icon-odometer"></i><span>任务大盘</span></el-menu-item>
          <el-menu-item index="/sync/log"><i class="el-icon-document"></i><span>同步日志</span></el-menu-item>
        </el-submenu>
        <el-menu-item index="/sync/audit"><i class="el-icon-view"></i><span>审计日志</span></el-menu-item>
        <el-submenu index="105">
          <template slot="title"><i class="el-icon-setting"></i><span>系统管理</span></template>
          <el-menu-item index="/system/user"><i class="el-icon-user"></i><span>用户管理</span></el-menu-item>
          <el-menu-item index="/sync/license"><i class="el-icon-key"></i><span>授权管理</span></el-menu-item>
        </el-submenu>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="crumb"><i class="el-icon-location" /> {{ crumb }}</div>
        <div class="topright">
          <!-- 主题切换按钮: 月亮=亮色(点切换到暗色), 太阳=暗色(点切换到亮色) -->
          <el-tooltip :content="theme === 'dark' ? '切换到亮色' : '切换到暗色'" placement="bottom">
            <el-button
              circle
              size="medium"
              class="theme-toggle"
              :icon="theme === 'dark' ? 'el-icon-sunny' : 'el-icon-moon'"
              @click="toggleTheme" />
          </el-tooltip>

          <el-dropdown @command="onCmd">
            <span class="user-info">
              {{ user.userName || 'admin' }}
              <i class="el-icon-arrow-down" />
            </span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script>
export default {
  data () { return { user: {}, theme: 'light' } },
  computed: {
    crumb () { return this.$route.meta?.title || '' }
  },
  mounted () {
    this.user = this.$store.state.user || {}
    // 从 App.vue 暴露的 __datamoveTheme 读取已应用的主题, 让按钮图标对应当前状态
    if (window.__datamoveTheme) this.theme = window.__datamoveTheme.get()
  },
  methods: {
    onCmd (cmd) {
      if (cmd === 'logout') this.logout()
      if (cmd === 'profile') this.$router.push('/profile')
    },
    async logout () {
      await this.$store.dispatch('logout')
      this.$router.push('/login')
    },
    /**
     * 切换主题 (light ↔ dark): 调用 App.vue 暴露的全局工具, 不在 layout 里直接操作 <html> class,
     * 避免双源不一致 (App.vue 已监听 storage 事件做跨页签同步)
     */
    toggleTheme () {
      if (!window.__datamoveTheme) return
      this.theme = window.__datamoveTheme.toggle()
      this.$message.success(this.theme === 'dark' ? '已切换为暗色主题' : '已切换为亮色主题')
    }
  }
}
</script>

<style scoped>
.layout-container { height: 100vh }
.aside { background: var(--bg-aside); color: #fff }
.logo {
  height: 60px; line-height: 60px; color: #fff; text-align: center;
  font-size: 18px; font-weight: bold; letter-spacing: 2px;
  border-bottom: 1px solid #1e2940;
}
.logo i { font-size: 26px; vertical-align: middle; color: #007bff; margin-right: 8px }
.el-menu { border: 0; background-color: var(--bg-aside) }
.topbar {
  background: var(--bg-topbar); border-bottom: 1px solid var(--color-border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; height: 56px;
}
.crumb { color: var(--color-text-regular); font-size: 14px }
.topright { display: flex; align-items: center; gap: 12px }
.topright .user-info { cursor: pointer; color: var(--color-text-primary) }
.main { background: var(--bg-page); padding: 16px; color: var(--color-text-primary) }

/* 主题切换按钮: 暗色下要重新染色, 亮色下用 Element 默认 */
.theme-toggle { margin-right: 4px }
html.theme-dark .theme-toggle {
  background-color: var(--bg-input);
  border-color: var(--color-border-darker);
  color: var(--color-warning);
  &:hover { background-color: var(--bg-hover); color: var(--color-warning); border-color: var(--color-warning) }
}
</style>