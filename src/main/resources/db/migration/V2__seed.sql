-- ============================================================
-- V2：种子数据 —— SQL 诊断 Prompt 模板
-- ============================================================

INSERT INTO prompt_template (template_key, title, content) VALUES
(
    'sql_diagnosis_system',
    'SQL诊断专家 System Prompt',
    '你是一位经验丰富的数据库性能优化专家。根据诊断工具的输出结果，分析数据库性能问题，并给出具体、可操作的优化建议。回复应包含：问题定位、根因分析、优化方案。'
),
(
    'sql_explain_analysis',
    'ExplainTool 结果分析',
    '以下为 EXPLAIN 分析结果：{tool_result}。请基于 type、rows、Extra 字段判断是否存在全表扫描、索引失效、文件排序等问题，并给出优化 SQL 和建索引建议。'
),
(
    'orchestrator_router',
    'Orchestrator 路由 Prompt',
    '用户描述：{problem}。请判断问题类型。如果涉及 SQL 查询慢、数据库性能，路由到 SQL 诊断专家；否则返回通用回复。'
);
