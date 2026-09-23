-- 只读诊断；使用生产截图中可见的表名和 JOIN 条件。
-- 未在生产执行。第 2/3 条统计所有 active D3 联系人；要对齐某次请求，
-- 请补上原请求相同的 org_code 等过滤条件。不要用 DISTINCT 或 Java 去重掩盖扩行。

-- 1. 先看小字典表：生产 JOIN 未限制状态、删除标志或其他作用域。
-- D3 的 matched_dictionary_rows > 1 就意味着第二个 JOIN 可扩行。
SELECT data_type, data_value, COUNT(*) AS matched_dictionary_rows
FROM t_m_announce_data_dictionary
WHERE data_type IN ('lineType', 'dockingType')
GROUP BY data_type, data_value
HAVING COUNT(*) > 1
ORDER BY data_type, data_value;

-- 2. 汇总不被 JOIN 放大的联系人基数，以及按当前 JOIN 规则预期的返回行数。
-- 每个 LEFT JOIN 没匹配时保留 1 行，多次匹配时按匹配数相乘。
SELECT COUNT(*) AS active_d3_contacts,
       SUM(a.id IS NULL) AS source_null_ids,
       COALESCE(SUM(COALESCE(b.matches, 1) * COALESCE(c.matches, 1)), 0)
           AS expected_joined_rows
FROM t_m_announce_address_book_new a
LEFT JOIN (
    SELECT data_value, COUNT(*) AS matches
    FROM t_m_announce_data_dictionary
    WHERE data_type = 'lineType'
    GROUP BY data_value
) b ON a.line = b.data_value
LEFT JOIN (
    SELECT data_value, COUNT(*) AS matches
    FROM t_m_announce_data_dictionary
    WHERE data_type = 'dockingType'
    GROUP BY data_value
) c ON a.docking_type = c.data_value
WHERE a.delete_flag = 1 AND a.docking_type = 'D3';

-- 3. 用与截图相同的 JOIN，定位扩行的 ID（不查询手机号/邮箱）。
SELECT a.id, COUNT(*) AS joined_rows_for_id
FROM t_m_announce_address_book_new a
LEFT JOIN t_m_announce_data_dictionary b
    ON a.line = b.data_value AND b.data_type = 'lineType'
LEFT JOIN t_m_announce_data_dictionary c
    ON a.docking_type = c.data_value AND c.data_type = 'dockingType'
WHERE a.delete_flag = 1 AND a.docking_type = 'D3'
GROUP BY a.id
HAVING COUNT(*) > 1
LIMIT 20;
