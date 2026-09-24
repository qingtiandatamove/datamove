<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script>
/**
 * App.vue 只负责挂载根容器, 主题初始化全部在 main.js 顶部完成:
 *   - main.js 在 import ElementUI 之后立即读 localStorage 同步切换 <html> class
 *   - 这样 Vue 渲染第一个组件(可能是 login 也可能是 layout)前 class 已就位, 不会闪亮色
 *
 * 跨页签同步由 main.js 的 window.addEventListener('storage') 统一处理, 这里不再重复
 */
export default {
  name: 'App'
}
</script>