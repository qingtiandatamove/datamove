package com.ruoyi.datamove.engine.incr;

import com.alibaba.otter.canal.protocol.CanalEntry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CanalRowKeys 单测 — 纯函数, 无 Spring 无 DB
 *
 * <p>重点覆盖复合主键: 修复前 CanalSyncEngine 用 "取第一个 isKey 列就 break" 的简化,
 * 会把 (order_id, item_id) 的 DELETE 退化成 WHERE order_id = ?, 静默删掉一整个订单的明细。
 * 这里把该场景钉死在测试里, 防止回归。
 */
class CanalRowKeysTest {

    private static CanalEntry.Column col(String name, String value, boolean isKey) {
        return CanalEntry.Column.newBuilder()
                .setName(name)
                .setValue(value)
                .setIsKey(isKey)
                .build();
    }

    /* ==================== keyColumns ==================== */

    @Test
    void singlePrimaryKeyIsCollected() {
        List<CanalEntry.Column> keys = CanalRowKeys.keyColumns(Arrays.asList(
                col("id", "7", true),
                col("name", "alice", false)));
        assertEquals(1, keys.size());
        assertEquals("id", keys.get(0).getName());
        assertEquals("7", keys.get(0).getValue());
    }

    @Test
    void compositePrimaryKeyCollectsEveryKeyColumn() {
        // 修复前只留 order_id, 导致 WHERE 不唯一 —— 这是本次修复的核心回归点
        List<CanalEntry.Column> keys = CanalRowKeys.keyColumns(Arrays.asList(
                col("order_id", "1001", true),
                col("item_id", "5", true),
                col("qty", "3", false)));
        assertEquals(2, keys.size(), "复合主键必须全部收集, 不能只取第一个");
        assertEquals("order_id", keys.get(0).getName());
        assertEquals("item_id", keys.get(1).getName());
    }

    @Test
    void uniqueKeyMarkedByCanalIsAlsoCollected() {
        // Canal 的 isKey 对主键与唯一键都会置位; 漏掉任一列 WHERE 都会命中多行
        List<CanalEntry.Column> keys = CanalRowKeys.keyColumns(Arrays.asList(
                col("id", "7", true),
                col("email", "a@b.c", true)));
        assertEquals(2, keys.size());
    }

    @Test
    void preservesEventOrder() {
        List<CanalEntry.Column> keys = CanalRowKeys.keyColumns(Arrays.asList(
                col("b", "2", true),
                col("x", "9", false),
                col("a", "1", true)));
        assertEquals(Arrays.asList("b", "a"),
                Arrays.asList(keys.get(0).getName(), keys.get(1).getName()));
    }

    @Test
    void noKeyColumnReturnsEmpty() {
        assertTrue(CanalRowKeys.keyColumns(Arrays.asList(
                col("a", "1", false),
                col("b", "2", false))).isEmpty());
    }

    @Test
    void nullOrEmptyInputReturnsEmpty() {
        assertTrue(CanalRowKeys.keyColumns(null).isEmpty());
        assertTrue(CanalRowKeys.keyColumns(Collections.emptyList()).isEmpty());
    }

    /* ==================== buildWhere ==================== */

    @Test
    void buildWhereForSingleKey() {
        String where = CanalRowKeys.buildWhere(
                Collections.singletonList(col("id", "7", true)));
        assertEquals("`id` = ?", where);
    }

    @Test
    void buildWhereForCompositeKeyJoinsWithAnd() {
        String where = CanalRowKeys.buildWhere(Arrays.asList(
                col("order_id", "1001", true),
                col("item_id", "5", true)));
        assertEquals("`order_id` = ? AND `item_id` = ?", where);
    }

    @Test
    void buildWherePlaceholderCountMatchesKeyCount() {
        String where = CanalRowKeys.buildWhere(Arrays.asList(
                col("a", "1", true), col("b", "2", true), col("c", "3", true)));
        // 每个 key 一个占位符, 供 PreparedStatement 按序绑定
        assertEquals(3, where.split("\\?", -1).length - 1);
        assertEquals("`a` = ? AND `b` = ? AND `c` = ?", where);
    }

    /* ==================== names ==================== */

    @Test
    void namesJoinsWithCommaForLogging() {
        assertEquals("order_id,item_id", CanalRowKeys.names(Arrays.asList(
                col("order_id", "1001", true),
                col("item_id", "5", true))));
    }

    @Test
    void namesFallsBackToDash() {
        assertEquals("-", CanalRowKeys.names(null));
        assertEquals("-", CanalRowKeys.names(Collections.emptyList()));
    }
}
