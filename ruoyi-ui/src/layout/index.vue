<template>
  <el-container class="layout-container">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <i class="el-icon-data-analysis"></i>
        <span>DataMove</span>
      </div>
      <!-- 菜单颜色不用 el-menu 的 background-color/text-color props:
           那些 props 会生成内联样式, 优先级压过 CSS 变量, 暗色主题下菜单颜色切不过来。
           统一走下面的 CSS (var(--bg-aside) 在暗色主题下自动变 #0d0d0d) -->
      <!-- 菜单项由后端按当前用户权限下发(store.menus), 不再写死在前端:
           改了角色授权后重新登录即可看到新菜单 -->
      <el-menu
        router
        v-loading="menuLoading"
        :default-active="$route.path">
        <template v-for="m in menus">
          <el-submenu v-if="m.children && m.children.length" :key="m.menuId" :index="'dir-' + m.menuId">
            <template slot="title"><i :class="iconOf(m)" /><span>{{ m.menuName }}</span></template>
            <el-menu-item v-for="c in m.children" :key="c.menuId" :index="c.path">
              <i :class="iconOf(c)" /><span>{{ c.menuName }}</span>
            </el-menu-item>
          </el-submenu>
          <el-menu-item v-else :key="m.menuId" :index="m.path">
            <i :class="iconOf(m)" /><span>{{ m.menuName }}</span>
          </el-menu-item>
        </template>
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
  data () { return { user: {}, theme: 'light', menuLoading: false } },
  computed: {
    crumb () { return this.$route.meta?.title || '' },
    /* 侧边栏菜单: 来自后端(按权限过滤后的菜单树) */
    menus () { return this.$store.state.menus || [] }
  },
  mounted () {
    this.user = this.$store.state.user || {}
    this.loadMenus()
    // 从 App.vue 暴露的 __datamoveTheme 读取已应用的主题, 让按钮图标对应当前状态
    if (window.__datamoveTheme) this.theme = window.__datamoveTheme.get()
  },
  methods: {
    async loadMenus () {
      // 通常路由守卫已经加载过(直接命中缓存), 这里兜底直接刷新/首次进入的场景
      if (this.menus.length) return
      this.menuLoading = true
      try { await this.$store.dispatch('loadMenus') } catch (e) { /* 失败保持空菜单, 不阻塞页面 */ }
      this.menuLoading = false
    },
    /**
     * 菜单图标: sys_menu.icon 里既有 element 的 el-icon-*, 也有早期 RuoYi 的自定义名(guide/dict/build...),
     * 后者在当前前端没有对应的 svg 图标, 统一降级成一个通用图标, 避免渲染成空白
     */
    iconOf (m) {
      return (m.icon && m.icon.indexOf('el-icon-') === 0) ? m.icon : 'el-icon-menu'
    },
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
/* 注意: 子菜单展开后的 ul.el-menu--inline 是 el-submenu 组件内部节点, 拿不到本组件的 scoped data-v,
   scoped 的 .el-menu 只能命中最外层 —— 必须用 >>> 深选择器, 否则子菜单露出 Element 默认白底 */
.aside >>> .el-menu { border: 0; background-color: var(--bg-aside) }
/* 侧边菜单配色: 亮/暗主题下侧栏都是深色底 (亮=#001529, 暗=#0d0d0d), 文字统一白色,
   hover 用半透明白提亮 (两种底色下都自然), 激活项用品牌蓝 */
.aside >>> .el-menu-item,
.aside >>> .el-submenu__title {
  color: #fff;
  background-color: transparent;
}
.aside >>> .el-menu-item:hover,
.aside >>> .el-submenu__title:hover {
  background-color: rgba(255, 255, 255, .08);
  color: #fff;
}
.aside >>> .el-menu-item.is-active { color: #007bff; }
.aside >>> .el-menu-item:focus,
.aside >>> .el-submenu__title:focus { background-color: transparent; }
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