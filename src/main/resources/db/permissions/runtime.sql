-- Apply after migration as the schema owner. Supply the runtime_role psql variable.
GRANT USAGE ON SCHEMA budget TO :"runtime_role";
GRANT SELECT ON ALL TABLES IN SCHEMA budget TO :"runtime_role";
GRANT INSERT ON budget.budget, budget.category, budget.budget_line,
    budget.catalog_guard, budget.catalog_revision, budget.budget_owner, budget.budget_month,
    budget.budget_currency, budget.category_owner, budget.category_name, budget.category_position,
    budget.category_status_history, budget.line_in_budget, budget.line_kind_attribute,
    budget.line_category, budget.line_amount_state, budget.line_amount, budget.budget_guard,
    budget.line_key, budget.budget_revision TO :"runtime_role";
GRANT UPDATE ON budget.catalog_guard, budget.budget_guard TO :"runtime_role";
