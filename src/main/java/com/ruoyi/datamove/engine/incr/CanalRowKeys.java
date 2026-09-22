package com.ruoyi.datamove.engine.incr;

import com.alibaba.otter.canal.protocol.CanalEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Canal 行 key 列工具 (纯函数)
 *
 * <p>从 {@link CanalEntry.Column#getIsKey()} 标记里收集**全部** key 列。
 * Canal 的 isKey 对主键与唯一键都会置位, 因此绝不能"取第一个就 break":
 * 复合主键 (order_id, item_id) 只按 order_id 拼 WHERE, 会连带删掉 / 改掉
 * 该 order_id 下的所有明细行 —— 属静默数据损坏 (SQL 语法正确、执行成功、不报错)。
 *
 * <p>本类不产生副作用, 便于单测覆盖复合主键等边界。
 */
public final class CanalRowKeys {

    private CanalRowKeys() {
    }

    /**
     * 收集全部 key 列, 保持事件里的原始顺序。
     *
     * @param cols 一行数据 (before 或 after), 允许为 null
     * @return key 列列表; 无 key 列时返回空列表 (调用方需自行决定跳过还是降级)
     */
    public static List<CanalEntry.Column> keyColumns(List<CanalEntry.Column> cols) {
        List<CanalEntry.Column> keys = new ArrayList<>();
        if (cols == null) return keys;
        for (CanalEntry.Column c : cols) {
            if (c.getIsKey()) keys.add(c);
        }
        return keys;
    }

    /**
     * 拼 WHERE 子句, 形如 {@code `k1` = ? AND `k2` = ?}。
     *
     * @param keys key 列, 非空
     * @return WHERE 子句; keys 为空时返回空串 (调用方应先校验)
     */
    public static String buildWhere(List<CanalEntry.Column> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append("`").append(keys.get(i).getName()).append("` = ?");
        }
        return sb.toString();
    }

    /**
     * 列名列表, 仅用于日志输出, 例如 {@code order_id,item_id}。
     */
    public static String names(List<CanalEntry.Column> cols) {
        if (cols == null || cols.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(cols.get(i).getName());
        }
        return sb.toString();
    }
}
