-- Read-only examples, prepared on the caller's connection.
-- Use one read-only REPEATABLE READ transaction when combining these queries.
-- true: current editor catalog; false: catalog pinned by the Budget revision.
PREPARE budget_lines(uuid, bigint, boolean) AS
WITH coordinates AS (
    SELECT r.budget_id, r.changed_at AS budget_time, c.changed_at AS catalog_time
    FROM budget.budget_revision r
    JOIN budget.catalog_guard g ON g.owner_id = r.owner_id
    JOIN budget.catalog_revision c ON c.owner_id = r.owner_id
        AND c.version = CASE WHEN $3 THEN g.current_version ELSE r.catalog_version END
    WHERE r.budget_id = $1 AND r.version = $2
)
SELECT k.line_id, k.kind_code, k.category_id,
       n.name, p.position, h.status_code, s.state_code, a.amount
FROM coordinates r
JOIN budget.line_key k ON k.budget_id = r.budget_id AND k.created_at <= r.budget_time
JOIN LATERAL (
    SELECT state_code, changed_at FROM budget.line_amount_state
    WHERE line_id = k.line_id AND changed_at <= r.budget_time
    ORDER BY changed_at DESC LIMIT 1
) s ON true
LEFT JOIN budget.line_amount a ON s.state_code = 'SET'
    AND a.line_id = k.line_id AND a.changed_at = s.changed_at
LEFT JOIN LATERAL (
    SELECT name FROM budget.category_name
    WHERE category_id = k.category_id AND changed_at <= r.catalog_time
    ORDER BY changed_at DESC LIMIT 1
) n ON true
LEFT JOIN LATERAL (
    SELECT position FROM budget.category_position
    WHERE category_id = k.category_id AND changed_at <= r.catalog_time
    ORDER BY changed_at DESC LIMIT 1
) p ON true
LEFT JOIN LATERAL (
    SELECT status_code FROM budget.category_status_history
    WHERE category_id = k.category_id AND changed_at <= r.catalog_time
    ORDER BY changed_at DESC LIMIT 1
) h ON true
ORDER BY CASE k.kind_code WHEN 'INCOME' THEN 0 WHEN 'SPENDING' THEN 1 ELSE 2 END,
         p.position, k.category_id;

-- Current editor overlays active categories absent from saved lines as UNSET.
-- Never use this overlay for revision details. Archived saved lines remain above.
PREPARE catalog_current(uuid) AS
SELECT o.category_id, n.name, p.position, s.status_code
FROM budget.catalog_guard g
JOIN budget.category_owner o ON o.owner_id = g.owner_id
JOIN LATERAL (
    SELECT name FROM budget.category_name
    WHERE category_id = o.category_id AND changed_at <= g.last_changed_at
    ORDER BY changed_at DESC LIMIT 1
) n ON true
JOIN LATERAL (
    SELECT position FROM budget.category_position
    WHERE category_id = o.category_id AND changed_at <= g.last_changed_at
    ORDER BY changed_at DESC LIMIT 1
) p ON true
JOIN LATERAL (
    SELECT status_code FROM budget.category_status_history
    WHERE category_id = o.category_id AND changed_at <= g.last_changed_at
    ORDER BY changed_at DESC LIMIT 1
) s ON true
WHERE g.owner_id = $1
ORDER BY p.position, o.category_id;
