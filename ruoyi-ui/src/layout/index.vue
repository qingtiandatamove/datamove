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
        active-text-color="#1890ff"
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
  data () { return { user: {} } },
  computed: {
    crumb () { return this.$route.meta?.title || '' }
  },
  mounted () {
    this.user = this.$store.state.user || {}
  },
  methods: {
    onCmd (cmd) {
      if (cmd === 'logout') this.logout()
      if (cmd === 'profile') this.$router.push('/profile')
    },
    async logout () {
      await this.$store.dispatch('logout')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
.layout-container { height: 100vh }
.aside { background: #001529; color: #fff }
.logo {
  height: 60px; line-height: 60px; color: #fff; text-align: center;
  font-size: 18px; font-weight: bold; letter-spacing: 2px;
  border-bottom: 1px solid #1e2940;
}
.logo i { font-size: 26px; vertical-align: middle; color: #1890ff; margin-right: 8px }
.el-menu { border: 0 }
.topbar {
  background: #fff; border-bottom: 1px solid #eee;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; height: 56px;
}
.crumb { color: #555; font-size: 14px }
.topright .user-info { cursor: pointer; color: #333 }
.main { background: #f0f2f5; padding: 16px }
.el-menu { background: #001529 }
</style>
