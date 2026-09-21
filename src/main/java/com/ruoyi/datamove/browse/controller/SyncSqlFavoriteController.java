package com.ruoyi.datamove.browse.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.datamove.browse.domain.SyncSqlFavorite;
import com.ruoyi.datamove.browse.mapper.SyncSqlFavoriteMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

/**
 * SQL 工作台收藏: 用户可保存常用 SQL, 支持团队共享
 *
 *  - 当前用户的私有收藏 + 所有用户的共享收藏共同可见
 *  - 私有收藏仅本人可编辑/删除; 共享收藏不可修改
 */
@Api(tags = "SQL 收藏")
@RestController
@RequestMapping("/sync/sql/favorite")
public class SyncSqlFavoriteController {

    @Autowired
    private SyncSqlFavoriteMapper favoriteMapper;

    @ApiOperation("分页查询 (本人私有 + 所有人共享)")
    @GetMapping("/page")
    public R<PageResult<SyncSqlFavorite>> page(@RequestParam(defaultValue = "1") int pageNum,
                                               @RequestParam(defaultValue = "20") int pageSize,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long dsId) {
        String currentUser = currentUserName();
        QueryWrapper<SyncSqlFavorite> w = new QueryWrapper<>();
        // (本人私有 OR 全员共享) AND (匹配数据源 OR 通用)
        w.and(q -> q.and(iq -> iq.eq("shared", 0).eq("user_name", currentUser))
                   .or(iq -> iq.eq("shared", 1)));
        if (dsId != null) {
            w.and(q -> q.eq("ds_id", dsId).or().isNull("ds_id"));
        }
        if (StrUtil.isNotBlank(keyword)) {
            w.and(q -> q.like("title", keyword).or().like("sql_text", keyword).or().like("tags", keyword));
        }
        Page<SyncSqlFavorite> page = new Page<>(pageNum, pageSize);
        Page<SyncSqlFavorite> result = favoriteMapper.selectPage(page, w.orderByDesc("update_time"));
        return R.ok(PageResult.of(result.getRecords(), result.getTotal()));
    }

    @ApiOperation("详情")
    @GetMapping("/{id}")
    public R<SyncSqlFavorite> detail(@PathVariable Long id) {
        return R.ok(favoriteMapper.selectById(id));
    }

    @ApiOperation("新增收藏")
    @PostMapping
    public R<Long> add(@RequestBody SyncSqlFavorite fav) {
        if (StrUtil.isBlank(fav.getTitle())) return R.fail("标题不能为空");
        if (StrUtil.isBlank(fav.getSqlText())) return R.fail("SQL 不能为空");
        fav.setUserId(currentUserId());
        fav.setUserName(currentUserName());
        if (fav.getUseCount() == null) fav.setUseCount(0);
        if (fav.getShared() == null) fav.setShared(0);
        Date now = new Date();
        fav.setCreateTime(now);
        fav.setUpdateTime(now);
        // 标题截断保护
        fav.setTitle(StrUtil.maxLength(fav.getTitle(), 64));
        fav.setTags(StrUtil.maxLength(fav.getTags(), 255));
        favoriteMapper.insert(fav);
        return R.ok(fav.getId());
    }

    @ApiOperation("修改收藏 (本人私有)")
    @PutMapping
    public R<Void> update(@RequestBody SyncSqlFavorite fav) {
        if (fav.getId() == null) return R.fail("ID 必填");
        SyncSqlFavorite old = favoriteMapper.selectById(fav.getId());
        if (old == null) return R.fail("收藏不存在");
        if (old.getShared() != null && old.getShared() == 1) {
            return R.fail("共享收藏不可修改, 请先取消共享");
        }
        if (!currentUserName().equals(old.getUserName())) {
            return R.fail("无权修改他人收藏");
        }
        old.setTitle(StrUtil.maxLength(fav.getTitle(), 64));
        old.setTags(StrUtil.maxLength(fav.getTags(), 255));
        old.setSqlText(fav.getSqlText());
        if (fav.getShared() != null) old.setShared(fav.getShared());
        old.setUpdateTime(new Date());
        favoriteMapper.updateById(old);
        return R.ok();
    }

    @ApiOperation("删除收藏 (仅创建者)")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        SyncSqlFavorite old = favoriteMapper.selectById(id);
        if (old == null) return R.fail("收藏不存在");
        if (!currentUserName().equals(old.getUserName())) {
            return R.fail("无权删除他人收藏");
        }
        favoriteMapper.deleteById(id);
        return R.ok();
    }

    @ApiOperation("使用一次 (use_count + 1)")
    @PostMapping("/{id}/use")
    public R<Void> use(@PathVariable Long id) {
        SyncSqlFavorite old = favoriteMapper.selectById(id);
        if (old == null) return R.fail("收藏不存在");
        old.setUseCount((old.getUseCount() == null ? 0 : old.getUseCount()) + 1);
        old.setUpdateTime(new Date());
        favoriteMapper.updateById(old);
        return R.ok();
    }

    /* 当前登录用户名 */
    private String currentUserName() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof LoginUser) {
                String name = ((LoginUser) auth.getPrincipal()).getUserName();
                return StrUtil.isBlank(name) ? "anonymous" : name;
            }
        } catch (Exception ignored) {}
        return "anonymous";
    }

    /* 当前登录用户ID */
    private Long currentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof LoginUser) {
                return ((LoginUser) auth.getPrincipal()).getUserId();
            }
        } catch (Exception ignored) {}
        return null;
    }
}