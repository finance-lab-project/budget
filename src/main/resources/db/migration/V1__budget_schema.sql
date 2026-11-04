-- R1 design draft: empty database only. IDs are supplied by the Java caller.
CREATE SCHEMA budget;

CREATE TABLE budget.line_kind (code text PRIMARY KEY);
CREATE TABLE budget.currency (code text PRIMARY KEY);
CREATE TABLE budget.amount_state (code text PRIMARY KEY);
CREATE TABLE budget.category_status (code text PRIMARY KEY);
INSERT INTO budget.line_kind VALUES ('INCOME'), ('SPENDING'), ('INVESTMENT');
INSERT INTO budget.currency VALUES ('RUB');
INSERT INTO budget.amount_state VALUES ('SET'), ('UNSET');
INSERT INTO budget.category_status VALUES ('ACTIVE'), ('ARCHIVED');

CREATE TABLE budget.budget (budget_id uuid PRIMARY KEY);
CREATE TABLE budget.category (category_id uuid PRIMARY KEY);
CREATE TABLE budget.budget_line (line_id uuid PRIMARY KEY);

CREATE TABLE budget.catalog_guard (
    owner_id uuid PRIMARY KEY,
    current_version bigint NOT NULL CHECK (current_version > 0),
    last_changed_at timestamptz(6) NOT NULL CHECK (isfinite(last_changed_at))
);
CREATE TABLE budget.catalog_revision (
    owner_id uuid NOT NULL REFERENCES budget.catalog_guard,
    version bigint NOT NULL CHECK (version > 0),
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    PRIMARY KEY (owner_id, version),
    UNIQUE (owner_id, changed_at),
    UNIQUE (owner_id, version, changed_at)
);
ALTER TABLE budget.catalog_guard ADD CONSTRAINT catalog_current_revision_fk
    FOREIGN KEY (owner_id, current_version, last_changed_at)
    REFERENCES budget.catalog_revision (owner_id, version, changed_at)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget.budget_owner (
    budget_id uuid PRIMARY KEY REFERENCES budget.budget,
    owner_id uuid NOT NULL REFERENCES budget.catalog_guard,
    UNIQUE (budget_id, owner_id)
);
CREATE TABLE budget.budget_month (
    budget_id uuid PRIMARY KEY REFERENCES budget.budget,
    month date NOT NULL CHECK (isfinite(month) AND extract(day FROM month) = 1),
    UNIQUE (budget_id, month)
);
CREATE TABLE budget.budget_currency (
    budget_id uuid PRIMARY KEY REFERENCES budget.budget,
    currency_code text NOT NULL REFERENCES budget.currency
);
CREATE TABLE budget.category_owner (
    category_id uuid PRIMARY KEY REFERENCES budget.category,
    owner_id uuid NOT NULL REFERENCES budget.catalog_guard,
    UNIQUE (category_id, owner_id)
);
ALTER TABLE budget.category ADD CONSTRAINT category_owner_required_fk
    FOREIGN KEY (category_id) REFERENCES budget.category_owner
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget.category_name (
    category_id uuid NOT NULL REFERENCES budget.category,
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    name text NOT NULL CHECK (length(btrim(name)) > 0 AND name = btrim(name)),
    PRIMARY KEY (category_id, changed_at)
);
CREATE TABLE budget.category_position (
    category_id uuid NOT NULL REFERENCES budget.category,
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    position integer NOT NULL CHECK (position > 0),
    PRIMARY KEY (category_id, changed_at)
);
CREATE TABLE budget.category_status_history (
    category_id uuid NOT NULL REFERENCES budget.category,
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    status_code text NOT NULL REFERENCES budget.category_status,
    PRIMARY KEY (category_id, changed_at)
);

CREATE TABLE budget.line_in_budget (
    line_id uuid PRIMARY KEY REFERENCES budget.budget_line,
    budget_id uuid NOT NULL REFERENCES budget.budget,
    UNIQUE (line_id, budget_id)
);
CREATE TABLE budget.line_kind_attribute (
    line_id uuid PRIMARY KEY REFERENCES budget.budget_line,
    kind_code text NOT NULL REFERENCES budget.line_kind,
    UNIQUE (line_id, kind_code)
);
CREATE TABLE budget.line_category (
    line_id uuid PRIMARY KEY REFERENCES budget.budget_line,
    category_id uuid NOT NULL REFERENCES budget.category,
    UNIQUE (line_id, category_id)
);

CREATE TABLE budget.line_amount_state (
    line_id uuid NOT NULL REFERENCES budget.budget_line,
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    state_code text NOT NULL REFERENCES budget.amount_state,
    -- Constraint helper: NULL bypasses the companion FK only for UNSET.
    set_line_id uuid GENERATED ALWAYS AS
        (CASE WHEN state_code = 'SET' THEN line_id END) STORED,
    PRIMARY KEY (line_id, changed_at),
    UNIQUE (line_id, changed_at, state_code)
);
CREATE TABLE budget.line_amount (
    line_id uuid NOT NULL REFERENCES budget.budget_line,
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    -- Validate input scale before assignment: NUMERIC itself rounds excess scale.
    amount numeric(19,2) NOT NULL
        CHECK (amount >= 0.00 AND amount <= 99999999999999999.99),
    set_code text GENERATED ALWAYS AS ('SET'::text) STORED,
    PRIMARY KEY (line_id, changed_at),
    CONSTRAINT amount_requires_set_fk
        FOREIGN KEY (line_id, changed_at, set_code)
        REFERENCES budget.line_amount_state (line_id, changed_at, state_code)
        DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE budget.line_amount_state ADD CONSTRAINT set_requires_amount_fk
    FOREIGN KEY (set_line_id, changed_at)
    REFERENCES budget.line_amount (line_id, changed_at)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget.budget_guard (
    budget_id uuid PRIMARY KEY REFERENCES budget.budget,
    owner_id uuid NOT NULL,
    month date NOT NULL CHECK (isfinite(month) AND extract(day FROM month) = 1),
    income_line_id uuid NOT NULL,
    investment_line_id uuid NOT NULL,
    income_kind text GENERATED ALWAYS AS ('INCOME'::text) STORED,
    investment_kind text GENERATED ALWAYS AS ('INVESTMENT'::text) STORED,
    current_version bigint NOT NULL CHECK (current_version > 0),
    last_changed_at timestamptz(6) NOT NULL CHECK (isfinite(last_changed_at)),
    UNIQUE (owner_id, month),
    UNIQUE (budget_id, owner_id),
    FOREIGN KEY (budget_id, owner_id)
        REFERENCES budget.budget_owner (budget_id, owner_id),
    FOREIGN KEY (budget_id, month)
        REFERENCES budget.budget_month (budget_id, month),
    FOREIGN KEY (budget_id) REFERENCES budget.budget_currency
);
-- A committed anchor must describe a complete saved budget.
ALTER TABLE budget.budget ADD CONSTRAINT budget_guard_required_fk
    FOREIGN KEY (budget_id) REFERENCES budget.budget_guard
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget.line_key (
    line_id uuid PRIMARY KEY REFERENCES budget.budget_line,
    budget_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    kind_code text NOT NULL,
    category_id uuid,
    created_at timestamptz(6) NOT NULL CHECK (isfinite(created_at)),
    CHECK ((kind_code = 'SPENDING' AND category_id IS NOT NULL)
        OR (kind_code IN ('INCOME', 'INVESTMENT') AND category_id IS NULL)),
    UNIQUE (line_id, budget_id, kind_code),
    UNIQUE (line_id, category_id),
    FOREIGN KEY (budget_id, owner_id)
        REFERENCES budget.budget_guard (budget_id, owner_id),
    FOREIGN KEY (line_id, budget_id)
        REFERENCES budget.line_in_budget (line_id, budget_id),
    FOREIGN KEY (line_id, kind_code)
        REFERENCES budget.line_kind_attribute (line_id, kind_code),
    FOREIGN KEY (category_id, owner_id)
        REFERENCES budget.category_owner (category_id, owner_id),
    FOREIGN KEY (line_id, category_id)
        REFERENCES budget.line_category (line_id, category_id),
    CONSTRAINT line_initial_state_fk FOREIGN KEY (line_id, created_at)
        REFERENCES budget.line_amount_state (line_id, changed_at)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE UNIQUE INDEX one_spending_line_per_category
    ON budget.line_key (budget_id, category_id) WHERE kind_code = 'SPENDING';
CREATE UNIQUE INDEX one_fixed_line_per_kind
    ON budget.line_key (budget_id, kind_code) WHERE kind_code IN ('INCOME', 'INVESTMENT');
ALTER TABLE budget.budget_line ADD CONSTRAINT line_key_required_fk
    FOREIGN KEY (line_id) REFERENCES budget.line_key
    DEFERRABLE INITIALLY DEFERRED;
-- Reverse FK prevents a category tie on a non-spending line or a different category.
ALTER TABLE budget.line_category ADD CONSTRAINT category_tie_matches_key_fk
    FOREIGN KEY (line_id, category_id) REFERENCES budget.line_key (line_id, category_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE budget.budget_guard ADD CONSTRAINT income_line_required_fk
    FOREIGN KEY (income_line_id, budget_id, income_kind)
    REFERENCES budget.line_key (line_id, budget_id, kind_code)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE budget.budget_guard ADD CONSTRAINT investment_line_required_fk
    FOREIGN KEY (investment_line_id, budget_id, investment_kind)
    REFERENCES budget.line_key (line_id, budget_id, kind_code)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget.budget_revision (
    budget_id uuid NOT NULL REFERENCES budget.budget,
    version bigint NOT NULL CHECK (version > 0),
    changed_at timestamptz(6) NOT NULL CHECK (isfinite(changed_at)),
    owner_id uuid NOT NULL,
    catalog_version bigint NOT NULL CHECK (catalog_version > 0),
    PRIMARY KEY (budget_id, version),
    UNIQUE (budget_id, changed_at),
    UNIQUE (budget_id, version, changed_at),
    FOREIGN KEY (budget_id, owner_id)
        REFERENCES budget.budget_owner (budget_id, owner_id),
    FOREIGN KEY (owner_id, catalog_version)
        REFERENCES budget.catalog_revision (owner_id, version)
);
ALTER TABLE budget.budget_guard ADD CONSTRAINT budget_current_revision_fk
    FOREIGN KEY (budget_id, current_version, last_changed_at)
    REFERENCES budget.budget_revision (budget_id, version, changed_at)
    DEFERRABLE INITIALLY DEFERRED;
