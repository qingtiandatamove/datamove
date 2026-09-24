<template>
  <div>
    <el-card>
      <div slot="header" class="clearfix">
        <span>同步任务</span>
        <el-button-group style="float:right">
          <el-button type="primary" icon="el-icon-plus" size="mini" @click="onAdd('FULL')">新建全量任务</el-button>
          <el-button type="success" icon="el-icon-plus" size="mini" @click="onAdd('INCR')">新建增量任务</el-button>
          <el-button type="warning" icon="el-icon-plus" size="mini" @click="onAdd('DDL')">同步表结构</el-button>
          <el-button type="danger" plain icon="el-icon-delete" size="mini" @click="openClearDialog">日志清理</el-button>
        </el-button-group>
      </div>

      <el-form :inline="true" :model="query">
        <el-form-item><el-input v-model="query.keyword" placeholder="任务名称/表名" size="small" clearable /></el-form-item>
        <el-form-item>
          <el-select v-model="query.taskType" size="small" clearable placeholder="任务类型" style="width:140px">
            <el-option label="全量" value="FULL" />
            <el-option label="增量" value="INCR" />
            <el-option label="同步表结构" value="DDL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.status" size="small" clearable placeholder="状态" style="width:140px">
            <el-option label="未启动" value="STOP" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已暂停" value="PAUSE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" size="small" icon="el-icon-search" @click="load">查询</el-button></el-form-item>
      </el-form>

      <el-table :data="page.rows" v-loading="loading" border class="sync-task-table"
        @sort-change="onSortChange">
        <el-table-column prop="id" label="ID" width="70" sortable="custom" :sort-orders="['ascending','descending']" />
        <el-table-column prop="taskName" label="任务名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="taskType" label="类型" width="90">
          <template slot-scope="s">
            <el-tag size="mini" :type="taskTypeTag(s.row.taskType)">
              {{ taskTypeName(s.row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="syncMode" label="模式" width="85">
          <template slot-scope="s">{{ modeName(s.row.syncMode) }}</template>
        </el-table-column>
        <el-table-column prop="overwriteFlag" label="覆盖" width="70" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.taskType === 'FULL' && s.row.overwriteFlag === 1" size="mini" type="danger">覆盖</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="tableName" label="表名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="batchSize" label="批次" width="75">
          <template slot-scope="s">{{ s.row.taskType === 'DDL' ? '-' : s.row.batchSize }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template slot-scope="s">
            <el-tag size="mini" :type="statusType(s.row.status)">{{ statusName(s.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 创建时间: 可按时间排序, 默认按 id 排序时此列无图标 -->
        <el-table-column prop="createTime" label="创建时间" width="170" sortable="custom" :sort-orders="['descending','ascending']">
          <template slot-scope="s">{{ fmtTime(s.row.createTime) }}</template>
        </el-table-column>
        <!-- 操作列: 外面只留高频动作(状态动作/停止/数据校验), 低频与危险动作收进「更多」下拉, 不再平铺 9 个按钮 -->
        <el-table-column label="操作" min-width="300" fixed="right">
          <template slot-scope="s">
            <!-- DDL 类型: 单次操作, 不支持暂停/继续/停止 -->
            <template v-if="s.row.taskType === 'DDL'">
              <el-button size="mini" type="success"
                :disabled="s.row.status === 'RUNNING'"
                @click="onStart(s.row)">
                {{ s.row.status === 'COMPLETED' ? '再次同步' : (s.row.status === 'FAILED' ? '重试' : '同步表结构') }}
              </el-button>
            </template>
            <template v-else>
              <!-- 状态动作: 同一时刻只会出现一个 —— 运行中=暂停 / 已暂停=继续 / 其余=启动(文案随状态变化) -->
              <el-button v-if="s.row.status === 'RUNNING'" size="mini" @click="onPause(s.row)">暂停</el-button>
              <el-button v-else-if="s.row.status === 'PAUSE'" size="mini" type="warning" @click="onResume(s.row)">继续</el-button>
              <el-button v-else size="mini" type="success" @click="onStart(s.row)">
                {{ s.row.status === 'COMPLETED' ? '重新启动' : (s.row.status === 'FAILED' ? '重试' : '启动') }}
              </el-button>
              <!-- 停止: 运行中或暂停可点 -->
              <el-button size="mini" type="danger" plain
                :disabled="!['RUNNING','PAUSE'].includes(s.row.status)"
                @click="onStop(s.row)">停止</el-button>
              <!-- 数据校验: 比对源库与目标库, 展示差异并可一键同步缺失数据 -->
              <el-button size="mini" type="primary" plain @click="onOpenVerify(s.row)">数据校验</el-button>
              <!-- 编辑任务: 直接放在操作列, 不再藏进下拉 -->
              <el-button size="mini" type="primary" plain icon="el-icon-edit" @click="onEdit(s.row)">编辑任务</el-button>
            </template>

            <!-- 低频/危险动作收进下拉: 重置进度 / 日志 / 清日志 / 删除 -->
            <el-dropdown trigger="click" @command="onRowCommand($event, s.row)">
              <el-button size="mini" class="op-more">
                更多<i class="el-icon-arrow-down el-icon--right"></i>
              </el-button>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item command="clone" icon="el-icon-document-copy"
                  :disabled="s.row.status === 'RUNNING'">克隆任务</el-dropdown-item>
                <el-dropdown-item v-if="s.row.taskType !== 'DDL'" command="reset"
                  icon="el-icon-refresh-left" :disabled="s.row.status === 'RUNNING'">重置进度</el-dropdown-item>
                <el-dropdown-item command="log" icon="el-icon-tickets">查看日志</el-dropdown-item>
                <el-dropdown-item command="clearLog" icon="el-icon-delete-solid">清理日志</el-dropdown-item>
                <el-dropdown-item command="del" icon="el-icon-delete" divided>
                  <span class="op-danger">删除任务</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px" background
        layout="total, sizes, prev, pager, next, jumper"
        :total="page.total"
        :page-sizes="[10, 20, 50, 100]"
        :page-size.sync="query.pageSize"
        :current-page.sync="query.pageNum"
        @size-change="onPageSizeChange"
        @current-change="load" />
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog :title="form.id ? '编辑任务' : '新建任务'" :visible.sync="dialog" width="900px" @closed="onDialogClosed">
      <!-- 克隆提示: 后端已复制全部业务配置, 但表名沿用源任务, 直接启动会重复同步同一张表 → 强提示用户改表名+任务名 -->
      <el-alert v-if="cloneHint" type="warning" :closable="false" show-icon style="margin-bottom:12px">
        <template slot="title">这是从任务 #{{ cloneHint.sourceId }} 克隆过来的新任务</template>
        <div style="font-size:13px;line-height:1.6">
          已复制:数据源、同步模式、批次/分片、Canal 配置、字段映射等<br/>
          <b>请修改「同步表名」(不修改会重复同步源任务那张表)和「任务名称」(后端已加 .copy 后缀, 但你可以再改)后启动</b>
        </div>
      </el-alert>
      <el-tabs v-model="tabActive" :before-leave="onBeforeTabLeave">
        <el-tab-pane label="基本信息" name="base">
          <el-form ref="form" :model="form" :rules="rules" label-width="110px">
            <el-form-item label="任务名称" prop="taskName"><el-input v-model="form.taskName" /></el-form-item>
            <el-form-item label="任务类型" prop="taskType">
              <el-radio-group v-model="form.taskType" @change="onTaskTypeChange">
                <el-radio-button label="FULL">全量</el-radio-button>
                <el-radio-button label="INCR">增量</el-radio-button>
                <el-radio-button label="DDL">同步表结构</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <!-- DDL 类型: 不需要选同步模式 -->
            <template v-if="form.taskType === 'FULL'">
              <el-form-item label="同步模式" prop="syncMode">
                <el-radio-group v-model="form.syncMode">
                  <el-radio-button label="ID">按主键ID</el-radio-button>
                  <el-radio-button label="TIME">按时间字段</el-radio-button>
                </el-radio-group>
              </el-form-item>
            </template>
            <template v-else-if="form.taskType === 'INCR'">
              <el-form-item label="同步模式" prop="syncMode"><el-tag>Binlog 增量</el-tag></el-form-item>
            </template>
            <template v-else>
              <el-form-item label="同步模式"><el-tag type="warning">仅同步表结构 (DDL)</el-tag></el-form-item>
            </template>

            <el-form-item label="源数据源" prop="sourceId">
              <el-select v-model="form.sourceId" filterable style="width:100%">
                <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标数据源" prop="targetId">
              <el-select v-model="form.targetId" filterable style="width:100%">
                <el-option v-for="d in datasources" :key="d.id" :value="d.id" :label="d.datasourceName + ' (' + d.host + ')'" />
              </el-select>
            </el-form-item>

            <el-form-item :label="form.taskType === 'DDL' ? '源表名' : '同步表名'" prop="tableName">
              <el-select v-model="form.tableName" filterable allow-create style="width:100%" placeholder="可手动输入或选择">
                <el-option v-for="t in sourceTables" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>

            <template v-if="form.taskType === 'FULL'">
              <el-form-item v-if="form.syncMode === 'ID'" label="ID字段名">
                <el-input v-model="form.idField" placeholder="默认 id" />
              </el-form-item>
              <template v-if="form.syncMode === 'TIME'">
                <el-form-item label="时间字段"><el-input v-model="form.timeField" placeholder="默认 update_time" /></el-form-item>
                <el-form-item label="起始时间"><el-date-picker v-model="form.startTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" /></el-form-item>
              </template>
              <el-form-item v-if="form.syncMode === 'ID'" label="起始ID"><el-input-number v-model="form.startId" :min="0" /></el-form-item>
              <el-form-item label="批次大小" prop="batchSize"><el-input-number v-model="form.batchSize" :min="100" :max="100000" /></el-form-item>
              <el-form-item v-if="form.syncMode === 'ID'" label="并行分片数">
                <el-input-number v-model="form.shardCount" :min="1" :max="16" />
                <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
                  大于 1 时按主键区间分片多线程并行同步, 大表提速明显 (自增主键效果最佳)。<br/>
                  需为覆盖式全量或首次全量; 断点续传任务自动回退单线程。暂停后再继续会整体重跑 (幂等写, 无脏数据)。
                </div>
              </el-form-item>
              <el-form-item label="覆盖数据">
                <el-switch v-model="form.overwriteFlag" :active-value="1" :inactive-value="0" />
                <span style="margin-left:8px;color:#909399;font-size:12px">开启后每次启动会先清空目标表,再全量写入</span>
              </el-form-item>
            </template>

            <template v-else-if="form.taskType === 'INCR'">
              <el-form-item label="Canal Host"><el-input v-model="form.canalHost" /></el-form-item>
              <el-form-item label="Canal Port"><el-input-number v-model="form.canalPort" :min="1" :max="65535" /></el-form-item>
              <el-form-item label="Destination"><el-input v-model="form.canalDestination" /></el-form-item>
              <el-form-item label="DML 过滤">
                <el-checkbox-group v-model="dmlTypes">
                  <el-checkbox label="INSERT">新增</el-checkbox>
                  <el-checkbox label="UPDATE">更新</el-checkbox>
                  <el-checkbox label="DELETE">删除</el-checkbox>
                </el-checkbox-group>
                <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
                  只同步勾选的 binlog 事件类型, 未勾选的直接丢弃 (不写目标库)。<br/>
                  订阅范围已自动收紧为「源库.任务表」, 其他库表事件不会进入本任务。
                </div>
              </el-form-item>
            </template>

            <template v-else>
              <el-alert type="info" :closable="false" show-icon
                title="DDL 同步说明"
                description="启动后会把源表结构复制到目标库; 目标表不存在会自动建表, 已存在则跳过 (不会覆盖现有数据)。"/>
            </template>

            <!-- 数据校验忽略字段: 目标库自动维护的列天然与源库不同, 不忽略会刷满假差异 -->
            <el-form-item v-if="form.taskType !== 'DDL'" label="校验忽略字段">
              <el-input v-model="form.ignoreFields" placeholder="逗号分隔, 如 update_time,update_by" />
              <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
                点「数据校验」比对源库与目标库时不比较这些列 (填目标列名)。<br/>
                数据库自动维护的列 (update_time / ON UPDATE CURRENT_TIMESTAMP) 两边天然不同, 建议填上。
              </div>
            </el-form-item>
            <el-form-item label="钉钉告警"><el-input v-model="form.dingtalkWebhook" placeholder="https://oapi.dingtalk.com/robot/send?access_token=xxx" /></el-form-item>
            <el-form-item label="邮件告警">
              <el-input v-model="form.alertEmail" placeholder="多个邮箱用英文逗号分隔, 如 ops@a.com,dev@b.com" />
              <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
                留空则不发送邮件; 需服务端配置 SMTP 服务器并开启 sync.mail.enabled=true 后生效
              </div>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- 字段映射: 仅 FULL / INCR 显示; DDL 不需要 -->
        <el-tab-pane v-if="form.taskType !== 'DDL'" label="字段映射" name="mapping">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
            title="字段映射说明"
            :description="mappingHint"/>
          <div class="fm-toolbar">
            <el-button size="mini" @click="reloadMapping" icon="el-icon-refresh" :loading="fmLoading">刷新列</el-button>
            <el-button size="mini" type="warning" @click="clearMappings" icon="el-icon-delete"
                       :disabled="!mappings.length">清空映射 (回到同名模式)</el-button>
            <span class="fm-stat">
              已配对 <b class="ok">{{ mappings.length }}</b> 对 / 源 {{ sourceFields.length }} 字段 / 目标 {{ targetFields.length }} 字段
            </span>
          </div>

          <div class="fm-stage" ref="fmStage" v-loading="fmLoading">
            <!-- 左: 源字段 -->
            <div class="fm-col fm-col-src">
              <div class="fm-col-head">源字段 (sourceId = {{ form.sourceId || '-' }}, table = {{ form.tableName || '-' }})</div>
              <div class="fm-col-body">
                <div v-if="!sourceFields.length" class="fm-empty">无字段, 请先填写源数据源与表名</div>
                <div v-for="f in sourceFields" :key="'s-' + f.name"
                     class="fm-item fm-item-src"
                     :class="{ 'fm-item-dim': isSrcMapped(f.name), 'fm-item-picking': drag.active && drag.srcName === f.name }"
                     :data-name="f.name"
                     @mousedown="onSrcMouseDown($event, f)">
                  <span class="fm-item-name">{{ f.name }}</span>
                  <span class="fm-item-type">{{ f.type }}</span>
                </div>
              </div>
            </div>

            <!-- 中: SVG 连线层 (覆盖整个 stage, 像素坐标与 DOM 1:1 对应) -->
            <svg class="fm-svg" :viewBox="'0 0 ' + fmSize.w + ' ' + fmSize.h" preserveAspectRatio="none">
              <g v-for="(m, i) in mappings" :key="'m-' + i + '-' + m.sourceField + '-' + m.targetField"
                 class="fm-line" v-show="m.path">
                <path :d="m.path" class="fm-line-path" />
                <circle :cx="m.sx" :cy="m.sy" r="3.5" class="fm-line-dot" />
                <circle :cx="m.tx" :cy="m.ty" r="3.5" class="fm-line-dot" />
                <circle :cx="midX(m)" :cy="midY(m)" r="9" class="fm-line-close-bg" @click="removeMapping(i)" />
                <text :x="midX(m)" :y="midY(m) + 4" text-anchor="middle" class="fm-line-close-x"
                      @click="removeMapping(i)">×</text>
              </g>
              <!-- 拖拽中的临时线 -->
              <g v-if="drag.active">
                <path :d="drag.path" class="fm-line-drag" />
                <circle :cx="drag.startX" :cy="drag.startY" r="4" class="fm-line-dot drag" />
                <circle :cx="drag.curX" :cy="drag.curY" r="4" class="fm-line-dot drag" />
              </g>
            </svg>

            <!-- 右: 目标字段 -->
            <div class="fm-col fm-col-tgt">
              <div class="fm-col-head">目标字段 (targetId = {{ form.targetId || '-' }}, table = {{ form.tableName || '-' }})</div>
              <div class="fm-col-body">
                <div v-if="!targetFields.length" class="fm-empty">无字段, 请先填写目标数据源与表名</div>
                <div v-for="f in targetFields" :key="'t-' + f.name"
                     class="fm-item fm-item-tgt"
                     :class="{ 'fm-item-dim': isTgtMapped(f.name), 'fm-item-drop': drag.active && drag.hoverName === f.name }"
                     :data-name="f.name">
                  <span class="fm-item-name">{{ f.name }}</span>
                  <span class="fm-item-type">{{ f.type }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="fm-hint-row">
            <i class="el-icon-info" /> 按住源字段往右拖拽到目标字段即可建立映射; 连线上的 × 可删除映射; 只连部分字段 = 只同步这些字段
          </div>
        </el-tab-pane>
      </el-tabs>

      <div slot="footer">
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="saving">保 存</el-button>
      </div>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog :title="'任务日志 [ID=' + taskLogId + ']'" :visible.sync="logDialog" width="900px" @open="loadLogs">
      <el-table :data="logPage.rows" v-loading="logLoading" border max-height="500">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="batchNo" label="批次" width="60" />
        <el-table-column label="分片" width="70" align="center">
          <template slot-scope="s">
            <el-tag v-if="s.row.shardNo" size="mini" type="warning" effect="plain">S{{ s.row.shardNo }}</el-tag>
            <span v-else style="color:#c0c4cc">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="batchStartId" label="起始" width="130" />
        <el-table-column prop="batchEndId" label="结束" width="130" />
        <el-table-column prop="batchRows" label="行数" width="70" />
        <el-table-column prop="totalRows" label="累计" width="100" />
        <el-table-column prop="costMs" label="耗时(ms)" width="90" />
        <el-table-column prop="status" label="状态" width="80">
          <template slot-scope="s">
            <el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : 'danger'">{{ s.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
      </el-table>
      <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
        :total="logPage.total" :page-size="logQuery.pageSize"
        :current-page.sync="logQuery.pageNum" @current-change="loadLogs" />
      <div slot="footer">
        <el-button type="danger" plain size="small" icon="el-icon-delete" @click="onClearTaskLogById(taskLogId)">清理该任务日志</el-button>
        <el-button size="small" @click="logDialog = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 日志清理弹窗: 按条件批量清理 (与同步日志页的筛选口径一致) -->
    <el-dialog title="日志清理" :visible.sync="clearDialog" width="560px">
      <el-alert type="warning" :closable="false" show-icon
        title="清理后不可恢复"
        description="只删除 sync_task_log 里的历史日志, 任务配置、断点进度和数据源都不受影响; 运行中的任务会继续写入新日志。"/>
      <el-form :model="clearForm" label-width="90px" style="margin-top:14px">
        <el-form-item label="清理任务">
          <el-select v-model="clearForm.taskId" clearable filterable placeholder="全部任务" style="width:100%">
            <el-option v-for="t in page.rows" :key="t.id" :value="t.id" :label="`[${t.id}] ${t.taskName}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="日志状态">
          <el-select v-model="clearForm.status" clearable placeholder="全部状态" style="width:100%" :disabled="clearForm.all">
            <el-option label="仅成功 (SUCCESS)" value="SUCCESS" />
            <el-option label="仅失败 (FAILED)" value="FAILED" />
            <el-option label="仅运行中 (RUNNING)" value="RUNNING" />
          </el-select>
        </el-form-item>
        <el-form-item label="保留天数">
          <el-input-number v-model="clearForm.beforeDays" :min="1" :max="3650" :disabled="clearForm.all" />
          <div style="color:#909399;font-size:12px;line-height:18px;margin-top:4px">
            只清理 {{ clearForm.beforeDays }} 天前(含更早)的历史日志, 今天与最近 {{ clearForm.beforeDays - 1 }} 天的日志保留
          </div>
        </el-form-item>
        <el-form-item label="清空全部">
          <el-checkbox v-model="clearForm.all">忽略上面的状态与保留天数, 清空{{ clearForm.taskId ? '该任务' : '全部任务' }}的所有日志</el-checkbox>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="clearDialog = false">取 消</el-button>
        <el-button type="danger" icon="el-icon-delete" :loading="clearing" @click="onClearConfirm">确认清理</el-button>
      </div>
    </el-dialog>

    <!-- 数据校验抽屉: 比对进度 + 差异明细 + 一键同步缺失数据 -->
    <el-drawer :title="verifyTitle" :visible.sync="verifyDrawer" size="75%" @closed="onVerifyClosed">
      <div style="padding:0 20px 24px">
        <div style="margin-bottom:12px">
          <el-button type="primary" icon="el-icon-search" size="small"
            :loading="verifyStarting" :disabled="verifyRunning" @click="onStartVerify">开始校验</el-button>
          <el-button type="danger" plain size="small" :disabled="!verifyRunning" @click="onStopVerify">中止校验</el-button>
          <el-button type="success" icon="el-icon-refresh" size="small" :disabled="!canRepair" @click="onRepair">
            一键同步差异
          </el-button>
          <el-button size="small" :disabled="!verifyRecord" @click="loadVerifyDiffs">刷新差异</el-button>
        </div>

        <el-alert v-if="!verifyRecord" type="info" :closable="false" show-icon
          title="还没有校验记录"
          description="点「开始校验」比对源库与目标库的行级差异。校验全程只读源库, 不修改任何数据。" />

        <template v-else>
          <div style="margin-bottom:12px">
            <el-tag size="small" style="margin:0 6px 6px 0" :type="verifyStatusTag(verifyRecord.status)">
              {{ verifyStatusName(verifyRecord.status) }}
            </el-tag>
            <el-tag size="small" style="margin:0 6px 6px 0" type="info">
              <i v-if="verifyRunning" class="el-icon-loading"></i>
              已比对 {{ verifyRecord.checkedRows || 0 }} 行
            </el-tag>
            <el-tag size="small" style="margin:0 6px 6px 0">
              源 {{ verifyRecord.sourceRows || 0 }} / 目标 {{ verifyRecord.targetRows || 0 }}
            </el-tag>
            <el-tag size="small" style="margin:0 6px 6px 0" type="danger">缺失 {{ verifyRecord.missingRows || 0 }}</el-tag>
            <el-tag size="small" style="margin:0 6px 6px 0" type="warning">不一致 {{ verifyRecord.mismatchRows || 0 }}</el-tag>
            <el-tag size="small" style="margin:0 6px 6px 0" type="info">多余 {{ verifyRecord.extraRows || 0 }}</el-tag>
            <el-tag v-if="verifyRecord.repairStatus" size="small" style="margin:0 6px 6px 0" type="success">
              修复 {{ verifyRecord.repairedRows || 0 }} / 失败 {{ verifyRecord.repairFailedRows || 0 }}
            </el-tag>
            <el-tag v-if="verifyRecord.truncated === 1" size="small" style="margin:0 6px 6px 0" type="danger">
              差异过多, 仅保留前 {{ verifyRecord.savedDiffs }} 条明细
            </el-tag>
          </div>

          <el-alert v-if="verifyRecord.errorMsg" type="error" :closable="false" show-icon
            :title="verifyRecord.errorMsg" style="margin-bottom:10px" />

          <div style="color:#909399;font-size:12px;line-height:20px;margin-bottom:10px">
            <b>缺失</b> = 源库有、目标库没有 → 一键同步会 INSERT;<br>
            <b>不一致</b> = 两边都有但字段值不同 → 一键同步只 UPDATE 不一致的字段;<br>
            <b>多余</b> = 目标库有、源库没有 → 只展示, 不会删除目标库数据。
          </div>

          <div style="margin-bottom:8px">
            <el-radio-group v-model="verifyDiffQuery.diffType" size="mini" @change="onDiffFilterChange">
              <el-radio-button label="">全部</el-radio-button>
              <el-radio-button label="MISSING">缺失</el-radio-button>
              <el-radio-button label="MISMATCH">不一致</el-radio-button>
              <el-radio-button label="EXTRA">多余</el-radio-button>
            </el-radio-group>
          </div>

          <el-table :data="verifyDiffPage.rows" v-loading="verifyDiffLoading" border size="mini" max-height="430">
            <el-table-column prop="diffType" label="类型" width="90">
              <template slot-scope="s">
                <el-tag size="mini" :type="verifyTypeTag(s.row.diffType)">{{ verifyTypeName(s.row.diffType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="pkValue" label="主键值" width="140" show-overflow-tooltip />
            <el-table-column prop="diffFields" label="不一致字段" min-width="150" show-overflow-tooltip />
            <el-table-column prop="sourceRow" label="源行" min-width="230" show-overflow-tooltip />
            <el-table-column prop="targetRow" label="目标行" min-width="230" show-overflow-tooltip />
            <el-table-column prop="repairStatus" label="修复" width="90">
              <template slot-scope="s">
                <el-tag size="mini" :type="repairStatusTag(s.row.repairStatus)">{{ repairStatusName(s.row.repairStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="repairError" label="失败原因" min-width="150" show-overflow-tooltip />
          </el-table>

          <el-pagination
            style="margin-top:12px" background layout="prev, pager, next, total"
            :total="verifyDiffPage.total" :page-size="verifyDiffQuery.pageSize"
            :current-page.sync="verifyDiffQuery.pageNum" @current-change="loadVerifyDiffs" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { pageTask, detailTask, addTask, updateTask, deleteTask, cloneTask,
         startTask, pauseTask, resumeTask, stopTask, resetTask, pageLog,
         clearTaskLog, clearLogByFilter,
         listDataSource, listTables, listColumns,
         listFieldMapping, saveFieldMapping, clearFieldMapping,
         startVerify, verifyDetail, latestVerify, verifyDiffs,
         repairVerify, stopVerify } from '@/api/datamove'

export default {
  data () {
    return {
      query: { keyword: '', taskType: '', status: '', pageNum: 1, pageSize: 10, orderByColumn: 'id', isAsc: 'asc' },
      page: { rows: [], total: 0 },
      loading: false, dialog: false, saving: false,
      form: { taskType: 'FULL', syncMode: 'ID', batchSize: 1000, ignoreFields: '' },
      // 克隆提示: { sourceId: 源任务ID } 表示当前打开的弹窗是某个源任务的克隆结果, 顶部展示强提示条
      cloneHint: null,
      // binlog DML 类型过滤 (勾选数组, 提交时拼成逗号串 binlogDmlTypes; 空 = 全部同步)
      dmlTypes: ['INSERT', 'UPDATE', 'DELETE'],
      rules: {
        taskName: [{ required: true, message: '必填' }],
        taskType: [{ required: true }],
        syncMode: [{ required: true }],
        sourceId: [{ required: true }],
        targetId: [{ required: true }],
        tableName: [{ required: true }],
        batchSize: [{ required: true }]
      },
      datasources: [], sourceTables: [],
      logDialog: false, logLoading: false, taskLogId: 0,
      logQuery: { pageNum: 1, pageSize: 10 }, logPage: { rows: [], total: 0 },
      // 日志清理弹窗 (按条件批量清理)
      clearDialog: false, clearing: false,
      clearForm: { taskId: '', status: '', beforeDays: 30, all: false },
      pollTimer: null, refreshing: false,

      /* ============ 字段映射 ============ */
      tabActive: 'base',
      fmLoading: false,
      // 已配对的映射 (按 sort_no 升序); 每条: { sourceField, targetField, path, sx, sy, tx, ty }
      mappings: [],
      // 源/目标全部字段 (常驻渲染; 已配对置灰, 不从列表移除 - 保证连线锚点 DOM 一直存在)
      sourceFields: [],   // [{ name, type }]
      targetFields: [],   // [{ name, type }]
      // 连线层像素尺寸 (与 stage DOM 1:1, resize/滚动后刷新)
      fmSize: { w: 600, h: 360 },
      // 拖拽临时状态 (hoverName = 当前悬停的目标字段, 命中测试结果)
      drag: { active: false, startX: 0, startY: 0, curX: 0, curY: 0, srcName: '', hoverName: '', path: '' },
      // 已保存的映射原始列表 (用于保存前检测变化)
      originalMappings: [],

      /* ============ 数据校验 (差异对账 + 一键同步) ============ */
      verifyDrawer: false,
      // 当前正在校验的任务行
      verifyTask: null,
      // 当前展示的校验记录 (sync_task_verify)
      verifyRecord: null,
      verifyStarting: false,
      verifyDiffPage: { rows: [], total: 0 },
      verifyDiffQuery: { pageNum: 1, pageSize: 20, diffType: '' },
      verifyDiffLoading: false,
      // 校验/修复期间轮询进度
      verifyTimer: null,
      // 校验轮询连续失败次数: 连续失败就停轮询, 避免无意义地一直打接口
      verifyFailCount: 0
    }
  },
  computed: {
    mappingHint () {
      return '按 kettle 风格拖拽源字段到目标字段, 建立一对一字段映射。' +
             '不建立映射 = 按源/目标字段同名同步(原行为, 老任务不受影响)。' +
             '支持部分映射: 只同步已连线配对的字段, 未连线字段不同步(断点字段未连线时自动补读, 不写入目标)。' +
             '建立后: SELECT 按源字段读、INSERT 按目标字段写, 自动建表也会用目标列名(未映射列放宽为可空)。'
    },
    verifyTitle () {
      return this.verifyTask ? ('数据校验 - ' + this.verifyTask.taskName) : '数据校验'
    },
    verifyRunning () {
      return !!this.verifyRecord && this.verifyRecord.status === 'RUNNING'
    },
    /**
     * 有待修复差异、且当前既不在校验也不在修复中时, 才允许点「一键同步差异」
     * 修复只能基于已落库的差异明细 —— 差异超过落库上限时, 超出部分只统计不落明细
     */
    canRepair () {
      const v = this.verifyRecord
      if (!v || !v.id) return false
      if (v.status === 'RUNNING' || v.repairStatus === 'RUNNING') return false
      if ((v.missingRows || 0) + (v.mismatchRows || 0) <= 0) return false
      const fixable = Math.min(Number(v.diffRows || 0), Number(v.savedDiffs || 0))
      const done = Number(v.repairedRows || 0) + Number(v.repairFailedRows || 0)
      return done < fixable
    }
  },
  watch: {
    'form.sourceId' () {
      this.fetchTables()
      this.fetchColumns()
    },
    'form.targetId' () {
      this.fetchColumns()
    },
    'form.tableName' () {
      this.fetchColumns()
    },
    // 切到字段映射 tab 时: pane 刚渲染/弹窗尺寸刚稳定, 兜底重算一次连线
    tabActive (val) {
      if (val === 'mapping') this.initMappingStage()
    }
  },
  mounted () {
    this.load()
    this.loadDatasources()
    this.startPolling()
    window.addEventListener('mousemove', this.onDocMouseMove)
    window.addEventListener('mouseup', this.onDocMouseUp)
    window.addEventListener('resize', this.onWinResize)
  },
  beforeDestroy () {
    this.stopPolling()
    this.stopVerifyTimer()
    window.removeEventListener('mousemove', this.onDocMouseMove)
    window.removeEventListener('mouseup', this.onDocMouseUp)
    window.removeEventListener('resize', this.onWinResize)
    document.body.classList.remove('fm-dragging')
  },
  methods: {
    /* ==================== 数据校验 (差异对账 + 一键同步) ==================== */

    /** 打开校验抽屉: 先展示最近一次结果(用户多半只是想看上次差异), 要重跑再点开始校验 */
    onOpenVerify (row) {
      this.verifyTask = row
      this.verifyRecord = null
      this.verifyDiffPage = { rows: [], total: 0 }
      this.verifyDiffQuery = { pageNum: 1, pageSize: 20, diffType: '' }
      this.verifyDrawer = true
      // 抽屉打开期间暂停列表自动刷新: 用户在盯校验结果, 列表不必每 3s 再打一次 /sync/task/page
      this.stopPolling()
      latestVerify(row.id).then(r => {
        if (r.data) {
          this.verifyRecord = r.data
          this.loadVerifyDiffs()
          // 上次还没跑完(或正在修复), 接着轮询
          if (r.data.status === 'RUNNING' || r.data.repairStatus === 'RUNNING') this.startVerifyTimer()
        }
      }).catch(() => {})
    },
    onStartVerify () {
      if (!this.verifyTask) return
      this.verifyStarting = true
      startVerify(this.verifyTask.id).then(r => {
        this.$message.success('已开始校验, 大表需要一些时间')
        this.verifyRecord = { id: r.data, status: 'RUNNING', checkedRows: 0, missingRows: 0, mismatchRows: 0, extraRows: 0 }
        this.verifyDiffPage = { rows: [], total: 0 }
        this.verifyDiffQuery.pageNum = 1
        this.startVerifyTimer()
        this.pollVerify()
      }).catch(err => {
        this.$message.error('校验启动失败:' + (err.message || ''))
      }).finally(() => { this.verifyStarting = false })
    },
    onStopVerify () {
      if (!this.verifyRecord || !this.verifyRecord.id) return
      stopVerify(this.verifyRecord.id).then(() => this.$message.success('已请求中止校验')).catch(() => {})
    },
    /**
     * 一键同步差异: 把「缺失」补上、「不一致」的字段改对。
     * 弹窗里明确写清不删目标库数据 —— 删除是破坏性动作, 不能藏在这个按钮背后。
     */
    onRepair () {
      const v = this.verifyRecord
      if (!v || !v.id) return
      const msg = '将以源库为准修复目标库:\n' +
        '  补缺失 ' + (v.missingRows || 0) + ' 行 (INSERT)\n' +
        '  修不一致 ' + (v.mismatchRows || 0) + ' 行 (UPDATE 差异字段)\n\n' +
        '不会删除目标库的任何数据。确认执行?'
      this.$confirm(msg, '一键同步差异', { type: 'warning', confirmButtonText: '确认同步' }).then(() => {
        return repairVerify(v.id)
      }).then(() => {
        this.$message.success('已开始修复')
        this.startVerifyTimer()
        this.pollVerify()
      }).catch(() => {})
    },
    pollVerify () {
      const id = this.verifyRecord && this.verifyRecord.id
      // 没有校验记录了(抽屉已关闭/切走): 停掉定时器, 别留个空转的
      if (!id) { this.stopVerifyTimer(); return }
      verifyDetail(id).then(r => {
        const data = r && r.data
        // 记录查不到(已被清理/接口不返回数据): 没有可轮询的对象, 停
        if (!data) { this.stopVerifyTimer(); return }
        this.verifyFailCount = 0
        const prev = this.verifyRecord
        this.verifyRecord = data
        // 校验刚跑完 -> 拉一次差异明细
        if (prev && prev.status === 'RUNNING' && data.status !== 'RUNNING') {
          this.verifyDiffQuery.pageNum = 1
          this.loadVerifyDiffs()
        }
        // 修复过程中持续刷新明细的修复状态
        if (prev && prev.repairStatus === 'RUNNING') this.loadVerifyDiffs()
        // 校验完成(含失败/中止)、修复也不在跑 -> 停掉校验轮询
        // 这里不要顺手 refreshTasks(): 校验只读, 不改任务状态, 列表没必要求刷新,
        // 否则每完成一次校验就会多打一次 /sync/task/page
        if (data.status !== 'RUNNING' && data.repairStatus !== 'RUNNING') {
          this.stopVerifyTimer()
        }
      }).catch(() => {
        // 接口连续失败: 直接停, 否则每 2s 空打接口永远停不下来
        this.verifyFailCount = (this.verifyFailCount || 0) + 1
        if (this.verifyFailCount >= 5) this.stopVerifyTimer()
      })
    },
    startVerifyTimer () {
      this.stopVerifyTimer()
      this.verifyFailCount = 0
      this.verifyTimer = setInterval(() => this.pollVerify(), 2000)
    },
    stopVerifyTimer () {
      if (this.verifyTimer) { clearInterval(this.verifyTimer); this.verifyTimer = null }
    },
    onDiffFilterChange () {
      this.verifyDiffQuery.pageNum = 1
      this.loadVerifyDiffs()
    },
    loadVerifyDiffs () {
      const id = this.verifyRecord && this.verifyRecord.id
      if (!id) return
      this.verifyDiffLoading = true
      verifyDiffs(id, this.verifyDiffQuery).then(r => { this.verifyDiffPage = r.data })
        .catch(() => {}).finally(() => { this.verifyDiffLoading = false })
    },
    onVerifyClosed () {
      this.stopVerifyTimer()
      this.verifyTask = null
      this.verifyRecord = null
      // 抽屉关了, 恢复列表自动刷新(仍是有「运行中」的行才真的发请求)
      this.startPolling()
    },
    verifyStatusName (s) {
      return ({ RUNNING: '校验中', COMPLETED: '校验完成', FAILED: '校验失败', STOP: '已中止' })[s] || s || '-'
    },
    verifyStatusTag (s) {
      return ({ RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger', STOP: 'info' })[s] || 'info'
    },
    verifyTypeName (t) {
      return ({ MISSING: '缺失', MISMATCH: '不一致', EXTRA: '多余' })[t] || t
    },
    verifyTypeTag (t) {
      return ({ MISSING: 'danger', MISMATCH: 'warning', EXTRA: 'info' })[t] || 'info'
    },
    repairStatusName (s) {
      return ({ PENDING: '待修复', REPAIRED: '已修复', FAILED: '失败', SKIPPED: '不修复' })[s] || s || '-'
    },
    repairStatusTag (s) {
      return ({ PENDING: 'info', REPAIRED: 'success', FAILED: 'danger', SKIPPED: 'info' })[s] || 'info'
    },

    load () {
      this.loading = true
      return pageTask(this.query)
        .then(r => { this.page = r.data })
        .catch(() => {})
        .finally(() => this.loading = false)
    },
    /**
     * 切每页条数: Element UI 默认会保留当前 pageNum,但 pageNum 越界(比如当前在第 5 页*10 = 第 50 条,切到 100 条/页后第 5 页其实在第 401 条) 会出现「明明有数据却显示空页」。
     * 强制 pageNum=1 避免这个空页坑, 选多少都是从头看,简单可预期。
     */
    onPageSizeChange (size) {
      this.query.pageSize = size
      this.query.pageNum = 1
      this.load()
    },
    loadDatasources () {
      listDataSource().then(r => { this.datasources = r.data || [] }).catch(() => {})
    },
    fetchTables () {
      if (!this.form.sourceId) return
      listTables(this.form.sourceId).then(r => { this.sourceTables = r.data || [] }).catch(() => {})
    },
    /**
     * 拉源/目标两边的列; 拉完后 reconcileMappings 把已配对映射与列集合对齐
     */
    async fetchColumns () {
      if (!this.form.sourceId || !this.form.tableName) { this.sourceFields = []; return }
      if (!this.form.targetId) { this.targetFields = []; return }
      this.fmLoading = true
      try {
        const [src, tgt] = await Promise.all([
          listColumns(this.form.sourceId, this.form.tableName),
          listColumns(this.form.targetId, this.form.tableName)
        ])
        this.sourceFields = (src.data || []).map(c => ({ name: c.columnName, type: c.dataType || c.columnType || '' }))
        this.targetFields = (tgt.data || []).map(c => ({ name: c.columnName, type: c.dataType || c.columnType || '' }))
        this.initMappingStage()
      } catch (e) {
        // 列表可能已变, 连线层尺寸/路径也要重算
        this.initMappingStage()
      } finally {
        this.fmLoading = false
      }
    },
    reloadMapping () { this.fetchColumns() },
    clearMappings () {
      if (!this.mappings.length) return
      this.$confirm('确认清空当前任务的所有字段映射? 清空后回到"按字段名同名"同步 (老行为)。', '提示', { type: 'warning' })
        .then(() => { this.mappings = [] ; this.rebuildMappingPaths() })
        .catch(() => {})
    },

    onAdd (type) {
      // DDL 类型不需要 batchSize / idField / timeField, syncMode 填 'DDL' 占位即可
      const base = { taskType: type, syncMode: type === 'FULL' ? 'ID' : (type === 'DDL' ? 'DDL' : 'BINLOG'), idField: 'id', timeField: 'update_time', overwriteFlag: 0, shardCount: 1 }
      if (type !== 'DDL') base.batchSize = 1000
      this.dialog = true; this.form = base; this.tabActive = 'base'
      this.dmlTypes = ['INSERT', 'UPDATE', 'DELETE']
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
    },
    onEdit (row) {
      this.dialog = true; this.form = Object.assign({}, row); this.tabActive = 'base'
      // binlog DML 过滤: 库里存逗号串, 界面用勾选数组 (空 = 全部, 与后端语义一致)
      const cfg = row.binlogDmlTypes
      this.dmlTypes = cfg ? cfg.split(',').map(s => s.trim().toUpperCase()).filter(s => ['INSERT', 'UPDATE', 'DELETE'].includes(s)) : ['INSERT', 'UPDATE', 'DELETE']
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
      // 拉一次字段 (异步; tab 切到 mapping 时也再拉一次)
      this.fetchColumns()
      // 已配对映射: 已存在任务直接拉
      if (row.id) {
        listFieldMapping(row.id).then(r => {
          const list = r.data || []
          // 已配对映射写入 mappings; 字段列表保持全量渲染 (已配对行置灰), 连线锚点 DOM 一直存在
          this.mappings = list.map((m, i) => ({
            sourceField: m.sourceField, targetField: m.targetField, sortNo: m.sortNo == null ? i : m.sortNo,
            path: ''
          }))
          this.originalMappings = list.map(m => ({ sourceField: m.sourceField, targetField: m.targetField }))
          // 映射已到: 立即重算连线 (fetchColumns 可能已先完成, 其 initMappingStage 时 mappings 还是空)
          this.initMappingStage()
        }).catch(() => {})
      }
    },
    onDialogClosed () {
      this.form = {}
      this.cloneHint = null   // 关闭弹窗时清掉克隆提示, 下次进入编辑页不再误显示
      this.mappings = []; this.sourceFields = []; this.targetFields = []; this.originalMappings = []
      this.tabActive = 'base'
      this.drag = { active: false, srcName: '', startX: 0, startY: 0, curX: 0, curY: 0, hoverName: '', path: '' }
      document.body.classList.remove('fm-dragging')
    },
    /** tab 切换前: 仅校验当前 tab 内已填字段; mapping tab 自动 fetchColumns */
    onBeforeTabLeave (to, from) {
      if (to === 'mapping' && (!this.sourceFields.length || !this.targetFields.length)) {
        this.fetchColumns()
      }
      return true
    },
    onTaskTypeChange (val) {
      if (val === 'DDL') {
        this.tabActive = 'base'
      }
      // 切换任务类型后清空 mapping (老 mapping 不再适用)
      this.mappings = []; this.sourceFields = []; this.targetFields = []
    },
    async onSave () {
      this.$refs.form.validate(ok => {
        if (!ok) { this.tabActive = 'base'; return }
        // DDL 类型不需要 batchSize, 若没填则用 100 占位 (后端不依赖该值)
        if (this.form.taskType === 'DDL' && !this.form.batchSize) this.form.batchSize = 100
        // binlog DML 过滤: 勾选数组拼成逗号串 (全勾/全不勾 = null, 即不过滤)
        if (this.form.taskType === 'INCR') {
          const all = ['INSERT', 'UPDATE', 'DELETE']
          const picked = (this.dmlTypes || []).filter(t => all.includes(t))
          this.form.binlogDmlTypes = (picked.length === 0 || picked.length === all.length) ? null : picked.join(',')
        } else {
          this.form.binlogDmlTypes = null
        }
        this.saving = true
        const api = this.form.id ? updateTask : addTask
        api(this.form).then(r => {
          const newId = this.form.id || (r && r.data)
          // 保存字段映射 (新增任务也会保存, 用后端返回的 id)
          return Promise.resolve(newId).then(id => {
            if (!id) return
            const payload = this.mappings.map((m, i) => ({
              taskId: id, sourceField: m.sourceField, targetField: m.targetField, sortNo: i
            }))
            return saveFieldMapping(id, payload).catch(err => {
              // 映射保存失败: 任务已经保存, 提示但不让用户以为没保存
              this.$message.warning('任务已保存, 但字段映射保存失败: ' + (err.message || ''))
            })
          })
        }).then(() => { this.$message.success('已保存'); this.dialog = false; this.load() })
          .catch(err => { this.$message.error('保存失败:' + (err.message || '未知错误')) })
          .finally(() => { this.saving = false })
      })
    },
    onDel (row) {
      this.$confirm(`确认删除任务 [${row.taskName}]?`, '提示', { type: 'warning' })
        .then(() => deleteTask(row.id))
        .then(() => { this.$message.success('已删除'); this.load() }).catch(() => {})
    },
    onStart (row)  { startTask(row.id).then(() => { this.$message.success('已启动'); this.refreshTasks() }).catch(err => { this.$message.error('启动失败:' + (err.message || '')) }) },
    onPause (row)  { pauseTask(row.id).then(() => { this.$message.success('已暂停'); this.refreshTasks() }).catch(err => { this.$message.error('暂停失败:' + (err.message || '')) }) },
    onResume (row) { resumeTask(row.id).then(() => { this.$message.success('已继续'); this.refreshTasks() }).catch(err => { this.$message.error('继续失败:' + (err.message || '')) }) },
    onStop (row)   { stopTask(row.id).then(() => { this.$message.success('已停止'); this.refreshTasks() }).catch(err => { this.$message.error('停止失败:' + (err.message || '')) }) },
    onReset (row) {
      this.$confirm(`确认重置任务 [${row.taskName}] 的同步进度?\n清空断点后下次启动会从头全量同步,历史日志保留`, '重置确认', { type: 'warning' })
        .then(() => resetTask(row.id))
        .then(() => { this.$message.success('已重置, 下次启动将从头同步'); this.refreshTasks() })
        .catch(err => {
          if (err && err !== 'cancel' && process.env.NODE_ENV !== 'production') {
            // eslint-disable-next-line no-console
            console.error('[reset]', err.message || err)
          }
        })
    },

    /**
     * 操作列「更多」下拉的分发: 低频/危险动作集中在模板里配置, 这里按 command 落到对应方法
     */
    onRowCommand (act, row) {
      const handlers = {
        clone: 'onClone',
        reset: 'onReset',
        log: 'onLog',
        clearLog: 'onClearTaskLog',
        edit: 'onEdit',
        del: 'onDel'
      }
      const fn = handlers[act] && this[handlers[act]]
      if (typeof fn === 'function') fn(row)
    },

    /**
     * 克隆任务: 后端复制全部业务配置, 重置状态/源表名/起始位点, 返回新 ID。
     * 克隆后自动打开编辑弹窗, 强制用户修改表名后再保存 (不清空表名会让新任务指向同一张表, 重复消费 binlog)。
     */
    onClone (row) {
      const name = row.taskName
      const sourceId = row.id
      this.$confirm(`确认克隆任务「${name}」?\n克隆后会复制全部业务配置(数据源/同步模式/批次/Canal 配置/字段映射), 但会重置状态/源表名/起始位点, 之后跳到编辑页请修改「同步表名」后再保存。`, '克隆任务', { type: 'warning' })
        .then(() => cloneTask(row.id))
        .then(async r => {
          const newId = r && r.data
          if (!newId) { this.$message.error('克隆成功但未返回新任务 ID'); return null }
          this.$message.success(`已克隆为「${name}.copy」, 请修改表名后启动`)
          // 刷新列表让新任务可见, 再加载新任务的完整数据进入编辑页 (源/目标数据源、字段映射都依赖 detail 接口)
          await this.load()
          return detailTask(newId)
        }).then(r2 => {
          if (r2 && r2.data) {
            // 设置克隆提示: 弹窗顶部展示「这是从任务 #X 克隆过来, 请修改表名+任务名」的告警条
            this.cloneHint = { sourceId }
            this.onEdit(r2.data)
          }
        }).catch(err => {
          if (err && err !== 'cancel') this.$message.error('克隆失败:' + (err.message || ''))
        })
    },

    /**
     * 自动轮询: 只在有任务处于 RUNNING 时定时拉取最新状态
     */
    startPolling () {
      this.stopPolling()
      this.pollTimer = setInterval(() => {
        const rows = (this.page && this.page.rows) || []
        const hasRunning = rows.some(r => r.status === 'RUNNING')
        if (hasRunning) this.refreshTasks()
      }, 3000)
    },
    stopPolling () {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    refreshTasks () {
      if (this.refreshing) return
      this.refreshing = true
      pageTask(this.query).then(r => {
        const oldStatuses = (this.page.rows || []).reduce((m, r) => (m[r.id] = r.status, m), {})
        this.page = r.data || { rows: [], total: 0 }
        ;(this.page.rows || []).forEach(r => {
          if (oldStatuses[r.id] && oldStatuses[r.id] !== r.status) {
            const map = { COMPLETED: '已完成', FAILED: '失败', RUNNING: '运行中', PAUSE: '已暂停', STOP: '已停止' }
            this.$notify({
              title: '任务状态变化',
              message: `[${r.taskName}] ${map[oldStatuses[r.id]] || oldStatuses[r.id]} → ${map[r.status] || r.status}`,
              type: r.status === 'FAILED' ? 'error' : (r.status === 'COMPLETED' ? 'success' : 'info'),
              duration: 2500
            })
          }
        })
        const stillRunning = (this.page.rows || []).some(r => r.status === 'RUNNING')
        if (!stillRunning) {
          this.stopPolling()
          this.startPolling()
        }
      }).catch(() => {}).finally(() => { this.refreshing = false })
    },

    onLog (row) { this.taskLogId = row.id; this.logDialog = true },
    loadLogs () {
      this.logLoading = true
      pageLog({ ...this.logQuery, taskId: this.taskLogId }).then(r => { this.logPage = r.data }).catch(() => {}).finally(() => this.logLoading = false)
    },

    /* ============ 日志清理 ============ */

    onClearTaskLog (row) { if (row && row.id) this.onClearTaskLogById(row.id) },

    /** 清理单个任务的全部日志 (行内「清日志」按钮 / 日志弹窗底部按钮共用) */
    onClearTaskLogById (taskId) {
      if (!taskId) return
      const row = (this.page.rows || []).find(r => r.id === taskId)
      const name = row ? row.taskName : ('ID=' + taskId)
      const running = row && row.status === 'RUNNING' ? '\n注意: 任务正在运行, 清理后本次运行的新日志会继续写入。' : ''
      this.$confirm(`确认清理任务【${name}】的全部同步日志?\n清理后不可恢复; 任务配置与断点进度不受影响。${running}`, '清理日志', {
        type: 'warning', confirmButtonText: '清理全部', cancelButtonText: '取消'
      })
        .then(() => clearTaskLog(taskId))
        .then(r => this.afterCleared(r, taskId))
        .catch(() => {})
    },

    openClearDialog () {
      this.clearForm = { taskId: '', status: '', beforeDays: 30, all: false }
      this.clearDialog = true
    },

    /** 弹窗确认: 按条件批量清理 (可选任务/状态/保留天数, 或直接全部清空) */
    onClearConfirm () {
      const f = this.clearForm
      const params = {}
      if (f.all) {
        // 选了任务 = 条件清理; 没选任务 = 全表清理, 靠后端 force 开关兜底
        if (f.taskId) params.taskId = f.taskId
        else params.force = true
      } else {
        params.beforeDays = f.beforeDays || 30
        if (f.taskId) params.taskId = f.taskId
        if (f.status) params.status = f.status
      }
      const scope = (f.taskId ? '所选任务' : '全部任务') +
        (f.all ? ' 的所有日志' : ` 中 ${f.beforeDays} 天前(含更早)的日志`)
      const tip = (f.all && !f.taskId)
        ? { title: '高风险操作', type: 'error', confirmButtonText: '我已确认', text: `将清空${scope}, 此操作不可恢复, 确认继续?` }
        : { title: '清理日志', type: 'warning', confirmButtonText: '确认清理', text: `确认清理${scope}? 清理后不可恢复。` }
      this.$confirm(tip.text, tip.title, { type: tip.type, confirmButtonText: tip.confirmButtonText, cancelButtonText: '取消' })
        .then(() => {
          this.clearing = true
          return clearLogByFilter(params)
            .then(r => { this.clearDialog = false; this.afterCleared(r, f.taskId) })
            .catch(() => {})
            .finally(() => { this.clearing = false })
        })
        .catch(() => {})
    },

    /** 清理成功后的统一收尾: 提示条数 + 刷新日志弹窗与任务列表 */
    afterCleared (r, taskId) {
      const n = (r && r.data) || 0
      this.$message.success(`已清理 ${n} 条日志`)
      if (this.logDialog && (!taskId || this.taskLogId === taskId)) this.loadLogs()
      this.refreshTasks()
    },

    onSortChange ({ prop, order }) {
      if (!order) {
        this.query.orderByColumn = 'id'
        this.query.isAsc = 'asc'
      } else {
        this.query.orderByColumn = prop
        this.query.isAsc = order === 'ascending' ? 'asc' : 'desc'
      }
      this.query.pageNum = 1
      this.load()
    },
    fmtTime (t) {
      if (!t) return '-'
      const d = new Date(t)
      if (isNaN(d.getTime())) return '-'
      const p = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },

    resetForm () { this.form = {} },
    modeName (m) { return ({ ID: '按ID', TIME: '按时间', BINLOG: 'Binlog', DDL: '表结构' })[m] || m },
    statusName (s) { return ({ STOP: '未启动', RUNNING: '运行中', PAUSE: '已暂停', COMPLETED: '已完成', FAILED: '失败' })[s] || s },
    statusType (s) { return ({ STOP: 'info', RUNNING: 'success', PAUSE: 'warning', COMPLETED: '', FAILED: 'danger' })[s] || '' },
    taskTypeName (t) { return ({ FULL: '全量', INCR: '增量', DDL: '表结构' })[t] || t },
    taskTypeTag (t) { return ({ FULL: '', INCR: 'success', DDL: 'warning' })[t] || '' },

    /* ============ 字段映射 - kettle 风格拖拽连线 ============ */

    isSrcMapped (n) { return this.mappings.some(m => m.sourceField === n) },
    isTgtMapped (n) { return this.mappings.some(m => m.targetField === n) },

    /**
     * 初始化连线层: 记录 stage 像素尺寸 (SVG viewBox 与 DOM 1:1, 避免百分比换算错位),
     * 并给两栏列表挂滚动监听 (滚动会改变锚点位置, 需重算连线)
     */
    initMappingStage () {
      this.$nextTick(() => {
        const stage = this.$refs.fmStage
        if (!stage || !this.dialog) return
        const r = stage.getBoundingClientRect()
        if (r.width > 0 && r.height > 0) this.fmSize = { w: r.width, h: r.height }
        stage.querySelectorAll('.fm-col-body').forEach(body => {
          body.removeEventListener('scroll', this.onColScroll)
          body.addEventListener('scroll', this.onColScroll)
        })
        this.rebuildMappingPaths()
      })
    },
    onColScroll () { this.rebuildMappingPaths() },
    onWinResize () {
      if (this.dialog && this.tabActive === 'mapping') this.initMappingStage()
    },

    /** 某字段行边缘中点相对 stage 的像素坐标 */
    anchorOf (el, side) {
      const stage = this.$refs.fmStage
      if (!stage || !el) return null
      const s = stage.getBoundingClientRect()
      const e = el.getBoundingClientRect()
      return {
        x: (side === 'right' ? e.right : e.left) - s.left,
        y: e.top + e.height / 2 - s.top
      }
    },
    srcAnchor (name) {
      const stage = this.$refs.fmStage
      if (!stage) return null
      return this.anchorOf(stage.querySelector('.fm-item-src[data-name="' + cssEscape(name) + '"]'), 'right')
    },
    tgtAnchor (name) {
      const stage = this.$refs.fmStage
      if (!stage) return null
      return this.anchorOf(stage.querySelector('.fm-item-tgt[data-name="' + cssEscape(name) + '"]'), 'left')
    },

    /** 贝塞尔连线: 水平控制点, 保证从锚点平滑伸出 */
    bezier (s, t) {
      const dx = Math.max(24, Math.abs(t.x - s.x) * 0.45)
      return `M ${s.x} ${s.y} C ${s.x + dx} ${s.y}, ${t.x - dx} ${t.y}, ${t.x} ${t.y}`
    },

    /** 计算每条已配对 mapping 的 svg 路径 + 两端锚点 (像素) */
    rebuildMappingPaths () {
      this.mappings.forEach(m => {
        const s = this.srcAnchor(m.sourceField)
        const t = this.tgtAnchor(m.targetField)
        if (s && t) {
          m.sx = s.x; m.sy = s.y; m.tx = t.x; m.ty = t.y
          m.path = this.bezier(s, t)
        } else {
          m.path = ''
        }
      })
    },
    midX (m) { return (m.sx != null && m.tx != null) ? (m.sx + m.tx) / 2 : 0 },
    midY (m) { return (m.sy != null && m.ty != null) ? (m.sy + m.ty) / 2 : 0 },

    /**
     * 命中测试: 指针坐标是否落在某个"未配对的目标字段行"上 (带容差).
     * 不依赖元素自身的 mouseup —— 鼠标快速划过/SVG 覆盖层遮挡都不会丢事件
     */
    hitTestTarget (clientX, clientY) {
      const stage = this.$refs.fmStage
      if (!stage) return ''
      const els = stage.querySelectorAll('.fm-item-tgt')
      for (let i = 0; i < els.length; i++) {
        const name = els[i].getAttribute('data-name')
        if (!name || this.isTgtMapped(name)) continue
        const r = els[i].getBoundingClientRect()
        if (clientX >= r.left - 10 && clientX <= r.right + 10 &&
            clientY >= r.top - 5 && clientY <= r.bottom + 5) {
          return name
        }
      }
      return ''
    },

    onSrcMouseDown (ev, field) {
      if (this.isSrcMapped(field.name)) return
      const s = this.srcAnchor(field.name)
      if (!s) return
      ev.preventDefault()
      this.drag = { active: true, srcName: field.name, startX: s.x, startY: s.y,
                    curX: s.x, curY: s.y, hoverName: '', path: this.bezier(s, s) }
      document.body.classList.add('fm-dragging')
    },
    onDocMouseMove (ev) {
      if (!this.drag.active) return
      const stage = this.$refs.fmStage
      if (!stage) return
      const sRect = stage.getBoundingClientRect()
      const x = ev.clientX - sRect.left
      const y = ev.clientY - sRect.top
      this.drag.curX = x; this.drag.curY = y
      this.drag.path = this.bezier({ x: this.drag.startX, y: this.drag.startY }, { x, y })
      this.drag.hoverName = this.hitTestTarget(ev.clientX, ev.clientY)
    },
    onDocMouseUp (ev) {
      if (!this.drag.active) return
      const srcName = this.drag.srcName
      // 松手瞬间再测一次, 以松手坐标为准 (兜底用最后一次 hover)
      const tgtName = this.hitTestTarget(ev.clientX, ev.clientY) || this.drag.hoverName
      this.drag = { active: false, srcName: '', startX: 0, startY: 0, curX: 0, curY: 0, hoverName: '', path: '' }
      document.body.classList.remove('fm-dragging')
      if (!srcName || !tgtName) return
      if (this.isTgtMapped(tgtName) || this.isSrcMapped(srcName)) return
      this.mappings.push({ sourceField: srcName, targetField: tgtName, sortNo: this.mappings.length, path: '' })
      this.$nextTick(() => this.rebuildMappingPaths())
    },
    removeMapping (i) {
      this.mappings.splice(i, 1)
      this.$nextTick(() => this.rebuildMappingPaths())
    }
  }
}

/**
 * CSS attribute selector 转义: data-name="user_name" 不需要转义,
 * 但若字段名含空格/斜杠等特殊字符要转义。本项目字段名通常安全, 提供一个最小版本.
 */
function cssEscape (s) {
  if (s == null) return ''
  return String(s).replace(/(["\\])/g, '\\$1')
}
</script>

<style scoped>
/* 同步任务列表 - 操作列
   坑: .sync-task-table 就是 el-table 根节点本身(同一个元素挂两个类), 根节点的后代里没有 .el-table,
   所以深选择器后面不能再写 .el-table —— 写了永远不匹配, 紧凑样式会整体失效 */
.sync-task-table >>> .cell .el-button--mini {
  padding: 5px 8px;
  font-size: 12px;
}
/* 低频动作已收进「更多」下拉, 这列不再需要平铺撑满, 改成左对齐; 间距统一交给 gap */
.sync-task-table >>> td:last-child .cell {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  white-space: nowrap;
  padding-left: 8px;
  padding-right: 8px;
}
/* 去掉 element-ui 默认的相邻按钮 10px 左边距, 否则会叠加在 gap 上 */
.sync-task-table >>> td:last-child .cell .el-button + .el-button { margin-left: 0; }
/* 「更多」是二级入口: gap 之外再留 6px, 与动作按钮略作区分(合计 14px) */
.sync-task-table >>> td:last-child .cell .el-dropdown { margin-left: 6px; }
/* 「更多」是次要入口, 不抢主按钮的视觉 */
.sync-task-table >>> td:last-child .op-more { color: #606266; }
/* 下拉里的危险动作 */
.op-danger { color: #F56C6C; }

/* ============ 字段映射 - kettle 风格 ============ */
.fm-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px }
.fm-stat    { color: #909399; font-size: 12px; margin-left: auto }
.fm-stat b  { color: #409EFF; font-weight: 600 }
.fm-stat b.ok { color: #67C23A }

.fm-stage {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 80px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
  min-height: 320px;
  padding: 8px;
}
.fm-svg {
  position: absolute;
  left: 0; top: 0; width: 100%; height: 100%;
  pointer-events: none;
  z-index: 2;
  overflow: visible;
}
/* 连线本体不响应鼠标 (防止挡住拖拽/悬停), 只有删除按钮可点 */
.fm-svg .fm-line { pointer-events: none }
.fm-svg .fm-line-close-bg, .fm-svg .fm-line-close-x { pointer-events: auto }
.fm-line-path { stroke: #409EFF; stroke-width: 2; fill: none; opacity: 0.85 }
.fm-line-drag { stroke: #67C23A; stroke-width: 2; fill: none; stroke-dasharray: 5 4 }
.fm-line-dot { fill: #409EFF; stroke: #fff; stroke-width: 1 }
.fm-line-dot.drag { fill: #67C23A }
.fm-line-close-bg { fill: #fff; stroke: #F56C6C; stroke-width: 1; cursor: pointer }
.fm-line-close-x  { fill: #F56C6C; font-size: 13px; cursor: pointer; font-family: Arial }

.fm-col { background: #fff; border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; z-index: 1 }
.fm-col-head {
  background: #f5f7fa;
  padding: 6px 10px;
  font-size: 12px;
  color: #606266;
  border-bottom: 1px solid #ebeef5;
}
.fm-col-body { max-height: 360px; overflow-y: auto; padding: 4px 0 }
.fm-empty { color: #909399; font-size: 12px; padding: 16px; text-align: center }

.fm-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  margin: 2px 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  cursor: grab;
  user-select: none;
  font-size: 13px;
  background: #fff;
}
.fm-item:hover { background: #ecf5ff; border-color: #b3d8ff; }
.fm-item-src:active { cursor: grabbing }
.fm-item-tgt { cursor: default }
.fm-item-dim { opacity: 0.45; cursor: not-allowed; background: #f5f7fa }
/* 拖拽中: 源行高亮 (正在被拖) / 目标行绿色高亮 (可放置) */
.fm-item-picking { background: #ecf5ff; border-color: #409EFF; box-shadow: 0 0 0 2px rgba(64,158,255,.2) }
.fm-item-drop { background: #f0f9eb; border-color: #b3e19d; box-shadow: 0 0 0 2px rgba(103,194,58,.25) }
.fm-item-name { font-weight: 500; color: #303133; font-family: Menlo, Consolas, monospace }
.fm-item-type { color: #909399; font-size: 11px; margin-left: 8px }

.fm-hint-row {
  margin-top: 8px; font-size: 12px; color: #909399;
  display: flex; align-items: center; gap: 4px;
}
.fm-hint-row i { color: #409EFF }
</style>

<style>
/* 拖拽连线时全局十字光标 (非 scoped: body 不在组件树内) */
body.fm-dragging, body.fm-dragging * { cursor: crosshair !important }
</style>