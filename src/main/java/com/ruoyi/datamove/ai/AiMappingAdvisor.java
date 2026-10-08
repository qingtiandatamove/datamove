package com.ruoyi.datamove.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.datamove.ai.domain.AiMappingSuggestion;
import com.ruoyi.datamove.datasource.domain.SyncDatasource;
import com.ruoyi.datamove.datasource.service.ISyncDatasourceService;
import com.ruoyi.datamove.util.JdbcUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 字段映射推荐
 *
 * <p>场景: 源表和目标表字段名不一样(驼峰 vs 下划线、改名、去掉前缀), 手点很烦还容易漏。
 * 这里把两边的真实表结构(字段名/类型/注释)交给模型, 让它给出配对 + 置信度 + 理由。
 *
 * <p>三条硬规则:
 * <ol>
 *   <li>模型给的字段名必须真实存在于两边表结构里, 瞎编的一律丢弃 —— 宁可少推荐, 也不能推荐一个不存在的列;</li>
 *   <li>本地规则匹配永远会跑一遍: AI 漏掉的字段用规则补, AI 不可用时直接作为结果;</li>
 *   <li>只输出建议, 落库必须用户在界面上确认(走原有的替换式保存接口)。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMappingAdvisor {

    private final AiProperties props;
    private final AiChatClient chatClient;
    private final ISyncDatasourceService datasourceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiMappingSuggestion suggest(Long sourceId, String sourceTable,
                                       Long targetId, String targetTable) {
        if (sourceId == null || targetId == null) throw new RuntimeException("请选择源数据源与目标数据源");
        if (isBlank(sourceTable) || isBlank(targetTable)) throw new RuntimeException("请填写源表与目标表");

        SyncDatasource src = datasourceService.getById(sourceId);
        SyncDatasource tgt = datasourceService.getById(targetId);
        if (src == null || tgt == null) throw new RuntimeException("数据源不存在");

        String srcTable = sourceTable.trim();
        String tgtTable = targetTable.trim();
        List<Map<String, String>> srcCols = JdbcUtils.listColumns(src, srcTable);
        List<Map<String, String>> tgtCols = JdbcUtils.listColumns(tgt, tgtTable);
        if (srcCols.isEmpty()) throw new RuntimeException("读不到源表结构: " + srcTable + " (表不存在或账号无权限)");
        if (tgtCols.isEmpty()) throw new RuntimeException("读不到目标表结构: " + tgtTable + " (表不存在或账号无权限)");

        AiMappingSuggestion res = new AiMappingSuggestion();
        res.setSourceTable(srcTable);
        res.setTargetTable(tgtTable);

        // 规则匹配永远算一遍: AI 结果之上做兜底与补全
        List<AiMappingSuggestion.Item> ruleItems = ruleMatch(srcCols, tgtCols);

        List<AiMappingSuggestion.Item> aiItems = null;
        String aiSummary = null;
        if (props.usable()) {
            try {
                String content = chatClient.chat(systemPrompt(),
                        userPrompt(src, tgt, srcTable, tgtTable, srcCols, tgtCols));
                JsonNode root = objectMapper.readTree(AiChatClient.stripFence(content));
                aiSummary = root.path("summary").asText(null);
                aiItems = readItems(root.path("mappings"), srcCols, tgtCols);
                res.setEngine("AI");
                res.setProvider(props.providerLabel());
                res.setModel(props.getModel());
            } catch (Exception e) {
                log.warn("[AI] 字段映射推荐失败, 降级本地规则: {}", e.getMessage());
                res.setFallbackNote("AI 调用失败(" + e.getMessage() + "), 已改用本地规则匹配");
            }
        } else {
            res.setFallbackNote("未配置 sync.ai.api-key, 当前使用本地规则匹配(同名 → 忽略大小写/下划线 → 类型校验)");
        }

        List<AiMappingSuggestion.Item> finalItems;
        if (aiItems == null || aiItems.isEmpty()) {
            finalItems = ruleItems;
            res.setEngine("RULE");
        } else {
            finalItems = merge(aiItems, ruleItems);
        }

        res.setMappings(finalItems);
        res.setUnmappedSource(remaining(srcCols, finalItems, true));
        res.setUnmappedTarget(remaining(tgtCols, finalItems, false));
        res.setSummary(aiSummary != null && !aiSummary.trim().isEmpty()
                ? aiSummary.trim()
                : defaultSummary(srcTable, tgtTable, srcCols, tgtCols, finalItems));
        return res;
    }

    /* ==================== 本地规则匹配 ==================== */

    private List<AiMappingSuggestion.Item> ruleMatch(List<Map<String, String>> srcCols,
                                                     List<Map<String, String>> tgtCols) {
        Map<String, String> byExact = new HashMap<>();
        Map<String, String> byNorm = new HashMap<>();
        for (Map<String, String> c : tgtCols) {
            String name = c.get("columnName");
            byExact.put(name, name);
            byNorm.putIfAbsent(normalize(name), name);
        }

        List<AiMappingSuggestion.Item> items = new ArrayList<>();
        Set<String> usedTarget = new LinkedHashSet<>();
        for (Map<String, String> c : srcCols) {
            String s = c.get("columnName");
            String t = byExact.get(s);
            String confidence = "high";
            String reason = "字段名完全相同";
            if (t == null) {
                t = byNorm.get(normalize(s));
                if (t != null) {
                    confidence = "mid";
                    reason = "忽略大小写/下划线后匹配";
                }
            }
            if (t == null || usedTarget.contains(t)) continue;

            String st = c.get("dataType");
            String tt = typeOf(tgtCols, t);
            if (!sameCategory(st, tt)) {
                confidence = "low";
                reason = "名字匹配但类型差异较大(源 " + st + " → 目标 " + tt + "), 同步可能报错或截断";
            }
            usedTarget.add(t);
            items.add(new AiMappingSuggestion.Item(s, t, confidence, reason));
        }
        return items;
    }

    /** AI 结果在前, 规则补 AI 没覆盖到的源字段 */
    private List<AiMappingSuggestion.Item> merge(List<AiMappingSuggestion.Item> aiItems,
                                                 List<AiMappingSuggestion.Item> ruleItems) {
        List<AiMappingSuggestion.Item> out = new ArrayList<>(aiItems);
        Set<String> covered = new LinkedHashSet<>();
        Set<String> usedTarget = new LinkedHashSet<>();
        for (AiMappingSuggestion.Item it : aiItems) {
            covered.add(lower(it.getSourceField()));
            usedTarget.add(lower(it.getTargetField()));
        }
        for (AiMappingSuggestion.Item it : ruleItems) {
            if (covered.contains(lower(it.getSourceField()))) continue;
            if (usedTarget.contains(lower(it.getTargetField()))) continue;
            out.add(it);
            covered.add(lower(it.getSourceField()));
            usedTarget.add(lower(it.getTargetField()));
        }
        return out;
    }

    /** 模型回复的字段名必须真实存在, 否则丢弃 */
    private List<AiMappingSuggestion.Item> readItems(JsonNode arr,
                                                     List<Map<String, String>> srcCols,
                                                     List<Map<String, String>> tgtCols) {
        List<AiMappingSuggestion.Item> items = new ArrayList<>();
        if (arr == null || !arr.isArray()) return items;
        Set<String> usedTarget = new LinkedHashSet<>();
        Map<String, String> srcIndex = new HashMap<>();
        for (Map<String, String> c : srcCols) srcIndex.put(lower(c.get("columnName")), c.get("columnName"));
        Map<String, String> tgtIndex = new HashMap<>();
        for (Map<String, String> c : tgtCols) tgtIndex.put(lower(c.get("columnName")), c.get("columnName"));

        for (JsonNode n : arr) {
            String s = srcIndex.get(lower(n.path("sourceField").asText("").trim()));
            String t = tgtIndex.get(lower(n.path("targetField").asText("").trim()));
            if (s == null || t == null || usedTarget.contains(t)) continue;
            usedTarget.add(t);
            String confidence = n.path("confidence").asText("mid");
            if (!confidence.matches("(?i)high|mid|low")) confidence = "mid";
            String reason = n.path("reason").asText("").trim();
            items.add(new AiMappingSuggestion.Item(s, t, confidence.toLowerCase(Locale.ROOT),
                    reason.isEmpty() ? "AI 依据字段名语义/类型判断" : reason));
        }
        return items;
    }

    private List<String> remaining(List<Map<String, String>> cols,
                                   List<AiMappingSuggestion.Item> items, boolean source) {
        Set<String> used = new LinkedHashSet<>();
        for (AiMappingSuggestion.Item it : items) {
            used.add(lower(source ? it.getSourceField() : it.getTargetField()));
        }
        List<String> rest = new ArrayList<>();
        for (Map<String, String> c : cols) {
            if (!used.contains(lower(c.get("columnName")))) rest.add(c.get("columnName"));
        }
        return rest;
    }

    /* ==================== Prompt ==================== */

    private String systemPrompt() {
        return "你是一个 MySQL 数据同步工具的字段映射助手。\n"
                + "用户会给你源表和目标表的真实字段结构, 你要给出字段的一对一映射建议。\n"
                + "只能输出 JSON, 不要任何解释性文字, 结构如下:\n"
                + "{\"summary\":\"一句话说明匹配情况\", \"mappings\":[{\"sourceField\":\"源字段\",\"targetField\":\"目标字段\",\"confidence\":\"high|mid|low\",\"reason\":\"为什么这么匹配\"}]}\n"
                + "规则:\n"
                + "1. 只能使用我给你的字段名, 禁止编造、禁止修改拼写;\n"
                + "2. 一个源字段最多配一个目标字段, 一个目标字段也只能被配一次;\n"
                + "3. 名字相同优先配; 名字不同但语义相同(如 create_time 与 createTime)也可以配, confidence 给 mid;\n"
                + "4. 类型差异大(int 配 datetime)时 confidence 给 low 并在 reason 里说明风险;\n"
                + "5. 明显不相关的字段(如源表有 password 目标表没有)不要硬配, 直接不出现在 mappings 里。";
    }

    private String userPrompt(SyncDatasource src, SyncDatasource tgt, String srcTable, String tgtTable,
                              List<Map<String, String>> srcCols, List<Map<String, String>> tgtCols) {
        StringBuilder sb = new StringBuilder();
        sb.append("源库: ").append(src.getDatasourceName()).append('(').append(src.getDbName()).append(")\n");
        sb.append("目标库: ").append(tgt.getDatasourceName()).append('(').append(tgt.getDbName()).append(")\n\n");
        sb.append("源表 ").append(srcTable).append(" 字段:\n").append(colsText(srcCols)).append('\n');
        sb.append("目标表 ").append(tgtTable).append(" 字段:\n").append(colsText(tgtCols)).append('\n');
        sb.append("\n请给出映射建议(只输出 JSON)。");
        return sb.toString();
    }

    private String colsText(List<Map<String, String>> cols) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> c : cols) {
            sb.append("- ").append(c.get("columnName"))
                    .append(" | ").append(c.get("columnType"))
                    .append(" | ").append(c.get("columnKey") == null ? "" : c.get("columnKey"))
                    .append(" | ").append(c.get("columnComment") == null ? "" : c.get("columnComment"))
                    .append('\n');
        }
        return sb.toString();
    }

    /* ==================== 小工具 ==================== */

    private String defaultSummary(String srcTable, String tgtTable,
                                  List<Map<String, String>> srcCols, List<Map<String, String>> tgtCols,
                                  List<AiMappingSuggestion.Item> items) {
        long risky = items.stream().filter(i -> "low".equals(i.getConfidence())).count();
        return "源表 " + srcTable + "(" + srcCols.size() + " 列) → 目标表 " + tgtTable + "(" + tgtCols.size()
                + " 列): 匹配出 " + items.size() + " 组" + (risky > 0 ? ", 其中 " + risky + " 组类型差异较大需重点确认" : "");
    }

    private String typeOf(List<Map<String, String>> cols, String name) {
        for (Map<String, String> c : cols) {
            if (name.equalsIgnoreCase(c.get("columnName"))) return c.get("dataType");
        }
        return "";
    }

    /** 只看大类是否相同: 数值 / 字符串 / 时间 / 其它 */
    private boolean sameCategory(String a, String b) {
        return category(a).equals(category(b));
    }

    private String category(String type) {
        if (type == null) return "OTHER";
        String t = type.toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("decimal") || t.contains("numeric")
                || t.contains("float") || t.contains("double") || t.contains("real") || t.contains("bit")) {
            return "NUM";
        }
        if (t.contains("char") || t.contains("text") || t.contains("blob") || t.contains("binary")
                || t.contains("enum") || t.contains("set") || t.contains("json")) {
            return "STR";
        }
        if (t.contains("date") || t.contains("time") || t.contains("year")) return "TIME";
        return "OTHER";
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
    }

    private String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
