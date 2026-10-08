package com.ruoyi.datamove.ai.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 自然语言 → SQL 的结果
 *
 * <p>只生成, 不执行: SQL 会填进工作台让用户自己看一遍再点执行。
 * 后端还会再过一遍只读校验(系统表保护 + 首词白名单), AI 也不能例外。
 */
@Data
public class AiSqlResult {

    /** AI / RULE */
    private String engine;
    private String provider;
    private String model;
    private String fallbackNote;

    /** 生成的 SQL (已去掉代码围栏, 通过只读校验才会返回) */
    private String sql;

    /** 这句 SQL 在干什么 */
    private String explanation;

    /** 用到的表 */
    private List<String> tables = new ArrayList<>();

    /** 风险/注意点: 如"没有 WHERE 条件, 建议加 LIMIT" */
    private List<String> warnings = new ArrayList<>();

    /** 被后端只读校验拦下时的原因 */
    private String rejected;
}
