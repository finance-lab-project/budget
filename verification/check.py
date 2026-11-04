#!/usr/bin/env python3
"""Design checks against a freshly Flyway-migrated disposable database.

Uses psql and Python's standard library; no application/ORM implementation.
PGHOST, PGPORT, PGDATABASE, PGUSER and PGPASSWORD select the disposable database.
"""
import os
from pathlib import Path
import subprocess
from uuid import uuid4

ENV = {**os.environ, "PGOPTIONS": "-c timezone=UTC"}
CHECKS = 0
TABLES = None


def run(sql):
    return subprocess.run(
        ["psql", "-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1",
         "-v", "VERBOSITY=verbose"], input=sql, text=True, capture_output=True, env=ENV)


def sql(text):
    result = run(text)
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


def check(condition, label):
    global CHECKS
    if not condition:
        raise AssertionError(label)
    CHECKS += 1
    print(f"PASS {label}")


def q(value):
    return "'" + str(value).replace("'", "''") + "'"


def snapshot():
    global TABLES
    if TABLES is None:
        TABLES = sql("SELECT tablename FROM pg_tables WHERE schemaname='budget' ORDER BY 1").splitlines()
    return sql(" UNION ALL ".join(
        f"SELECT {q(table)}, coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]') "
        f"FROM budget.{table} t" for table in TABLES) + " ORDER BY 1")


def rejected(body, state, label, at_commit=False):
    before = snapshot()
    result = run("BEGIN;\n" + body + "\nSELECT 'reached_commit';\nCOMMIT;")
    check(result.returncode != 0 and state in result.stderr
          and (not at_commit or "reached_commit" in result.stdout), label)
    check(snapshot() == before, label + " rolls back all tables")


OWNER = str(uuid4())
OTHER = str(uuid4())
BUDGET = str(uuid4())
INCOME, INVESTMENT, SPENDING = [str(uuid4()) for _ in range(3)]
CATEGORIES = [str(uuid4()) for _ in range(10)]
NAMES = ["Жильё и коммунальные услуги", "Продукты", "Рестораны и доставка еды",
         "Транспорт", "Здоровье и уход за собой", "Одежда и товары для дома",
         "Подписки и связь", "Образование и хобби", "Кредиты, страхование и налоги",
         "Прочие и нерегулярные расходы"]


def category(category_id, owner, name, position, coordinate="t"):
    return f"""
    INSERT INTO budget.category VALUES ({q(category_id)});
    INSERT INTO budget.category_owner VALUES ({q(category_id)}, {q(owner)});
    INSERT INTO budget.category_name SELECT {q(category_id)}, {coordinate}, {q(name)} FROM stamp;
    INSERT INTO budget.category_position SELECT {q(category_id)}, {coordinate}, {position} FROM stamp;
    INSERT INTO budget.category_status_history SELECT {q(category_id)}, {coordinate}, 'ACTIVE' FROM stamp;
    """


def initialize(owner, categories):
    # Java will branch on INSERT ... RETURNING; the SQL gate models that protocol.
    values = ",".join(f"({q(c)}::uuid, {q(n)}, {i})"
                      for i, (c, n) in enumerate(zip(categories, NAMES), 1))
    return f"""
    CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp()::timestamptz(6) t;
    CREATE TEMP TABLE claimed ON COMMIT DROP AS
      WITH inserted AS (
        INSERT INTO budget.catalog_guard SELECT {q(owner)}, 1, t FROM stamp
        ON CONFLICT (owner_id) DO NOTHING RETURNING owner_id
      ) SELECT owner_id FROM inserted;
    INSERT INTO budget.catalog_revision SELECT owner_id, 1, t FROM claimed CROSS JOIN stamp;
    CREATE TEMP TABLE defaults ON COMMIT DROP AS
      SELECT v.* FROM (VALUES {values}) v(id, name, position) CROSS JOIN claimed;
    INSERT INTO budget.category SELECT id FROM defaults;
    INSERT INTO budget.category_owner SELECT id, {q(owner)} FROM defaults;
    INSERT INTO budget.category_name SELECT id, t, name FROM defaults CROSS JOIN stamp;
    INSERT INTO budget.category_position SELECT id, t, position FROM defaults CROSS JOIN stamp;
    INSERT INTO budget.category_status_history SELECT id, t, 'ACTIVE' FROM defaults CROSS JOIN stamp;
    """


def line(line_id, kind, category_id=None, budget_id=BUDGET, owner=OWNER, state="UNSET", amount=None):
    tie = (f"INSERT INTO budget.line_category VALUES ({q(line_id)}, {q(category_id)});"
           if category_id else "")
    money = (f"INSERT INTO budget.line_amount(line_id, changed_at, amount) "
             f"SELECT {q(line_id)}, t, {amount} FROM stamp;" if amount is not None else "")
    return f"""
    INSERT INTO budget.budget_line VALUES ({q(line_id)});
    INSERT INTO budget.line_in_budget VALUES ({q(line_id)}, {q(budget_id)});
    INSERT INTO budget.line_kind_attribute VALUES ({q(line_id)}, {q(kind)});
    {tie}
    INSERT INTO budget.line_key VALUES
      ({q(line_id)}, {q(budget_id)}, {q(owner)}, {q(kind)},
       {q(category_id) if category_id else 'NULL'}, (SELECT t FROM stamp));
    INSERT INTO budget.line_amount_state(line_id, changed_at, state_code)
      SELECT {q(line_id)}, t, {q(state)} FROM stamp;
    {money}
    """


def create_budget(budget_id=BUDGET, month="2026-11-01", income=INCOME, investment=INVESTMENT,
                  static_owner=OWNER, key_owner=OWNER, static_month=None):
    return f"""
    CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp()::timestamptz(6) t;
    INSERT INTO budget.budget VALUES ({q(budget_id)});
    INSERT INTO budget.budget_owner VALUES ({q(budget_id)}, {q(static_owner)});
    INSERT INTO budget.budget_month VALUES ({q(budget_id)}, {q(static_month or month)});
    INSERT INTO budget.budget_currency VALUES ({q(budget_id)}, 'RUB');
    INSERT INTO budget.budget_guard
      (budget_id, owner_id, month, income_line_id, investment_line_id, current_version, last_changed_at)
      SELECT {q(budget_id)}, {q(key_owner)}, {q(month)}, {q(income)}, {q(investment)}, 1, t FROM stamp;
    INSERT INTO budget.budget_revision SELECT {q(budget_id)}, 1, t, {q(key_owner)}, 1 FROM stamp;
    """


def advance_budget():
    return f"""
    SELECT owner_id FROM budget.catalog_guard WHERE owner_id={q(OWNER)} FOR SHARE;
    SELECT budget_id FROM budget.budget_guard WHERE budget_id={q(BUDGET)} FOR UPDATE;
    CREATE TEMP TABLE stamp ON COMMIT DROP AS
      SELECT greatest(clock_timestamp(), last_changed_at + interval '1 microsecond')::timestamptz(6) t
      FROM budget.budget_guard WHERE budget_id={q(BUDGET)};
    UPDATE budget.budget_guard SET current_version=current_version+1, last_changed_at=(SELECT t FROM stamp)
      WHERE budget_id={q(BUDGET)};
    INSERT INTO budget.budget_revision
      SELECT budget_id, current_version, last_changed_at, owner_id,
        (SELECT current_version FROM budget.catalog_guard WHERE owner_id={q(OWNER)})
      FROM budget.budget_guard WHERE budget_id={q(BUDGET)};
    """


def advance_catalog():
    return f"""
    SELECT owner_id FROM budget.catalog_guard WHERE owner_id={q(OWNER)} FOR UPDATE;
    CREATE TEMP TABLE stamp ON COMMIT DROP AS
      SELECT greatest(clock_timestamp(), last_changed_at + interval '1 microsecond')::timestamptz(6) t
      FROM budget.catalog_guard WHERE owner_id={q(OWNER)};
    UPDATE budget.catalog_guard SET current_version=current_version+1, last_changed_at=(SELECT t FROM stamp)
      WHERE owner_id={q(OWNER)};
    INSERT INTO budget.catalog_revision SELECT owner_id, current_version, last_changed_at
      FROM budget.catalog_guard WHERE owner_id={q(OWNER)};
    """


queries = (Path(__file__).resolve().parents[1] / "docs/queries.sql").read_text()


def projection(version, current=False):
    output = sql(queries + f"EXECUTE budget_lines({q(BUDGET)}, {version}, {'true' if current else 'false'});")
    return [row.split("|") for row in output.splitlines()] if output else []


# Migration creates no owner or category data and seeds only the fixed knots.
check(sql("SELECT count(*) FROM budget.category") == "0", "migration leaves editable catalog empty")
for table, expected in [("line_kind", "INCOME,INVESTMENT,SPENDING"), ("currency", "RUB"),
                        ("amount_state", "SET,UNSET"), ("category_status", "ACTIVE,ARCHIVED")]:
    check(sql(f"SELECT string_agg(code, ',' ORDER BY code) FROM budget.{table}") == expected,
          table + " fixed TEXT seeds")
check(sql("SELECT bool_and(data_type='bigint') FROM information_schema.columns WHERE table_schema='budget' "
          "AND column_name IN ('version','current_version','catalog_version')") == "t", "BIGINT revision storage")
check(sql("SELECT bool_and(data_type='uuid' AND column_default IS NULL) FROM information_schema.columns "
          "WHERE table_schema='budget' AND table_name IN ('budget','category','budget_line')") == "t",
      "UUID anchors require explicit caller-supplied identity")

# A late failure must not leave a claimed owner or partially created defaults.
rejected(initialize(OWNER, CATEGORIES) + "SELECT 1/0;", "22012", "initializer failure")
started = sql("SELECT clock_timestamp()")
sql("BEGIN;" + initialize(OWNER, CATEGORIES) + "COMMIT;")
check(sql(f"SELECT count(*) FROM budget.category_owner WHERE owner_id={q(OWNER)}") == "10", "ten defaults created atomically")
check(sql(f"SELECT last_changed_at >= {q(started)}::timestamptz AND last_changed_at <= clock_timestamp() "
          f"AND current_version=1 FROM budget.catalog_guard WHERE owner_id={q(OWNER)}") == "t",
      "first catalog revision uses database clock and version one")
check(sql(f"SELECT bool_and(n.changed_at=g.last_changed_at AND p.changed_at=g.last_changed_at "
          f"AND s.changed_at=g.last_changed_at) FROM budget.category_owner o JOIN budget.catalog_guard g USING(owner_id) "
          f"JOIN budget.category_name n USING(category_id) JOIN budget.category_position p USING(category_id) "
          f"JOIN budget.category_status_history s USING(category_id) WHERE owner_id={q(OWNER)}") == "t",
      "all initial catalog facts share one recording coordinate")
before = snapshot()
sql("BEGIN;" + initialize(OWNER, [str(uuid4()) for _ in range(10)]) + "COMMIT;")
check(snapshot() == before, "existing catalog initialization is a no-op")
sql("BEGIN;" + initialize(OTHER, [str(uuid4()) for _ in range(10)]) + "COMMIT;")

# Create a complete monthly budget: explicit zero differs from UNSET.
started = sql("SELECT clock_timestamp()")
sql("BEGIN;" + create_budget() + line(INCOME, "INCOME", state="SET", amount="1000.00")
    + line(INVESTMENT, "INVESTMENT")
    + line(SPENDING, "SPENDING", CATEGORIES[0], state="SET", amount="0.00") + "COMMIT;")
check(len(projection(1)) == 3, "complete budget with all three line kinds commits")
check(sql(f"SELECT last_changed_at >= {q(started)}::timestamptz AND last_changed_at<=clock_timestamp() "
          f"FROM budget.budget_guard WHERE budget_id={q(BUDGET)}") == "t", "first Budget revision uses database clock")
check(sql(f"SELECT count(DISTINCT changed_at) FROM budget.line_amount_state s JOIN budget.line_key k USING(line_id) "
          f"WHERE budget_id={q(BUDGET)}") == "1", "initial line states share one coordinate")
check(projection(1)[1][-2:] == ["SET", "0.00"] and projection(1)[2][-2:] == ["UNSET", ""], "zero and UNSET project distinctly")
check(projection(999) == [], "unknown revision has no latest-state fallback")

# All statements succeed until COMMIT for invalid pairing cases.
state_insert = f"INSERT INTO budget.line_amount_state(line_id, changed_at, state_code) SELECT {q(INCOME)}, t, {{state}} FROM stamp;"
amount_insert = f"INSERT INTO budget.line_amount(line_id, changed_at, amount) SELECT {q(INCOME)}, {{time}}, 42 FROM stamp;"
for label, body in [
    ("SET without amount", state_insert.format(state="'SET'")),
    ("orphan amount", amount_insert.format(time="t")),
    ("mismatched coordinates", state_insert.format(state="'SET'") + amount_insert.format(time="t + interval '1 microsecond'")),
    ("UNSET with amount", state_insert.format(state="'UNSET'") + amount_insert.format(time="t")),
]:
    rejected(advance_budget() + body, "23503", label + " fails at commit", at_commit=True)

# SET -> UNSET -> same numeric SET creates new history, with either insert order.
sql("BEGIN;" + advance_budget() + state_insert.format(state="'UNSET'") + "COMMIT;")
check(projection(2)[0][-2:] == ["UNSET", ""] and projection(1)[0][-1] == "1000.00", "clear preserves previous amount history")
sql("BEGIN;" + advance_budget() + amount_insert.format(time="t").replace(", 42 FROM", ", 1000.00 FROM")
    + state_insert.format(state="'SET'") + "COMMIT;")
check(projection(3)[0][-2:] == ["SET", "1000.00"], "amount-first SET pair commits after clear")
check(sql(f"SELECT count(*) FROM budget.line_amount WHERE line_id={q(INCOME)}") == "2", "same amount after clear is a new fact")

# Catalog edits append facts; historical labels/order/status remain pinned.
sql("BEGIN;" + advance_catalog()
    + f"INSERT INTO budget.category_name SELECT {q(CATEGORIES[0])}, t, 'Изменённая категория' FROM stamp;"
    + f"INSERT INTO budget.category_position SELECT {q(CATEGORIES[0])}, t, 20 FROM stamp;"
    + f"INSERT INTO budget.category_status_history SELECT {q(CATEGORIES[0])}, t, 'ARCHIVED' FROM stamp;COMMIT;")
check(projection(3)[1][3:6] == [NAMES[0], "1", "ACTIVE"], "saved revision pins original catalog")
check(projection(3, True)[1][3:6] == ["Изменённая категория", "20", "ARCHIVED"], "current projection retains archived saved line with current label/order")
before = snapshot()
sql("BEGIN;" + initialize(OWNER, [str(uuid4()) for _ in range(10)]) + "COMMIT;")
check(snapshot() == before, "initializer preserves edited and archived category facts and IDs")

# Archiving every category is not an absent catalog; it must not refill defaults.
sql("BEGIN;" + advance_catalog()
    + f"INSERT INTO budget.category_status_history SELECT category_id, t, 'ARCHIVED' "
      f"FROM budget.category_owner CROSS JOIN stamp WHERE owner_id={q(OWNER)};COMMIT;")
before = snapshot()
sql("BEGIN;" + initialize(OWNER, [str(uuid4()) for _ in range(10)]) + "COMMIT;")
check(snapshot() == before, "all-archived catalog is preserved without refilling defaults")

# New categories enter the editor, and only acquire a monthly line on Save.
NEW_CATEGORY, NEW_LINE = str(uuid4()), str(uuid4())
sql("BEGIN;" + advance_catalog() + category(NEW_CATEGORY, OWNER, "Новая категория", 21) + "COMMIT;")
check(NEW_CATEGORY in sql(queries + f"EXECUTE catalog_current({q(OWNER)});")
      and len(projection(3, True)) == 3, "new active category is available without creating a monthly line")
sql("BEGIN;" + advance_budget() + line(NEW_LINE, "SPENDING", NEW_CATEGORY) + "COMMIT;")
check(len(projection(3)) == 3 and len(projection(4)) == 4, "new line is absent before its first saved state")
check(projection(4)[1][3:6] == ["Изменённая категория", "20", "ARCHIVED"], "new Budget revision pins the checked newer catalog")

# Unchanged command exits after version checks, before allocating a coordinate.
before = snapshot()
sql(f"BEGIN; SELECT owner_id FROM budget.catalog_guard WHERE owner_id={q(OWNER)} FOR SHARE;"
    f"SELECT budget_id FROM budget.budget_guard WHERE budget_id={q(BUDGET)} FOR UPDATE; COMMIT;")
check(snapshot() == before, "no-op transaction leaves versions, coordinates and facts unchanged")

rejected(create_budget(str(uuid4())), "23505", "duplicate owner/month")
for bad_month in ["2026-11-02", "infinity", "-infinity"]:
    rejected(create_budget(str(uuid4()), month=bad_month), "23514", "invalid month " + bad_month)
rejected(create_budget(str(uuid4()), month="2026-12-01", static_month="2027-01-01"), "23503", "key month contradicts static fact")
rejected(create_budget(str(uuid4()), month="2026-12-01", static_owner=OTHER), "23503", "key owner contradicts static fact")
new_budget = str(uuid4())
rejected(create_budget(new_budget, "2026-12-01", str(uuid4()), str(uuid4())), "23503", "missing fixed lines fails at commit", at_commit=True)
for present_kind in ["INCOME", "INVESTMENT"]:
    b, i, v = [str(uuid4()) for _ in range(3)]
    rejected(create_budget(b, "2026-12-01", i, v)
             + line(i if present_kind == "INCOME" else v, present_kind, budget_id=b),
             "23503", "only " + present_kind + " is insufficient at commit", at_commit=True)
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "INCOME"), "23505", "duplicate INCOME line")
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "INVESTMENT"), "23505", "duplicate INVESTMENT line")
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "SPENDING", CATEGORIES[0]), "23505", "duplicate category in month")
foreign_category = sql(f"SELECT category_id FROM budget.category_owner WHERE owner_id={q(OTHER)} LIMIT 1")
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "SPENDING", foreign_category), "23503", "foreign-owner category")
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "SPENDING"), "23514", "SPENDING requires category")
rejected("CREATE TEMP TABLE stamp ON COMMIT DROP AS SELECT clock_timestamp() t;" + line(str(uuid4()), "INCOME", CATEGORIES[1]), "23514", "INCOME forbids category")
rejected(f"INSERT INTO budget.line_category VALUES ({q(INCOME)}, {q(CATEGORIES[1])});", "23503", "extra non-spending category tie fails at commit", at_commit=True)
rejected(f"UPDATE budget.line_key SET kind_code='INVESTMENT' WHERE line_id={q(INCOME)};", "23505", "line kind key cannot change into duplicate")
rejected(f"UPDATE budget.line_key SET category_id={q(CATEGORIES[1])} WHERE line_id={q(SPENDING)};", "23503", "line key contradicts category tie")
rejected(f"UPDATE budget.line_kind_attribute SET kind_code='INCOME' WHERE line_id={q(SPENDING)};", "23503", "line kind fact contradicts key")
rejected(f"UPDATE budget.line_key SET owner_id={q(OTHER)} WHERE line_id={q(INCOME)};", "23503", "line key contradicts Budget owner")
rejected(f"INSERT INTO budget.budget_line VALUES ({q(uuid4())});", "23503", "bare line anchor fails at commit", at_commit=True)
second_budget, second_income, second_investment = [str(uuid4()) for _ in range(3)]
sql("BEGIN;" + create_budget(second_budget, "2026-12-01", second_income, second_investment)
    + line(second_income, "INCOME", budget_id=second_budget)
    + line(second_investment, "INVESTMENT", budget_id=second_budget) + "COMMIT;")
check(sql(f"SELECT count(*) FROM budget.budget_guard WHERE owner_id={q(OWNER)}") == "2", "different month has its own required fixed lines")
rejected(f"UPDATE budget.line_in_budget SET budget_id={q(second_budget)} WHERE line_id={q(SPENDING)};",
         "23503", "line budget tie contradicts key")
rejected(f"UPDATE budget.budget_guard SET income_line_id={q(second_income)} WHERE budget_id={q(BUDGET)};",
         "23503", "required income must belong to this Budget at commit", at_commit=True)

# Accepted extrema and rejected non-finite/negative/out-of-range values.
for amount in ["0.00", "99999999999999999.99"]:
    sql("BEGIN;" + advance_budget() + state_insert.format(state="'SET'")
        + amount_insert.format(time="t").replace(", 42 FROM", f", {amount} FROM") + "COMMIT;")
    check(sql(f"SELECT amount FROM budget.line_amount WHERE line_id={q(INCOME)} ORDER BY changed_at DESC LIMIT 1") == amount,
          "amount boundary " + amount)
for amount, state in [("-0.01", "23514"), ("'NaN'", "23514"), ("'Infinity'", "22003"),
                      ("100000000000000000.00", "22003")]:
    rejected(advance_budget() + state_insert.format(state="'SET'")
             + amount_insert.format(time="t").replace(", 42 FROM", f", {amount} FROM"), state, "invalid amount " + amount)
rejected(f"UPDATE budget.budget_guard SET current_version=0 WHERE budget_id={q(BUDGET)};", "23514", "stored version zero")
rejected(f"UPDATE budget.budget_guard SET current_version=current_version+1 WHERE budget_id={q(BUDGET)};", "23503", "guard without revision fails at commit", at_commit=True)
rejected(f"UPDATE budget.budget_revision SET catalog_version=999 WHERE budget_id={q(BUDGET)} AND version=1;", "23503", "missing pinned catalog revision")

# Test equal/backward readings explicitly, without changing the host clock.
check(sql("WITH readings(previous, observed) AS (VALUES "
          "('2030-01-01 00:00:00+00'::timestamptz, '2030-01-01 00:00:00+00'::timestamptz),"
          "('2030-01-01 00:00:00+00'::timestamptz, '2029-12-31 23:59:59+00'::timestamptz)) "
          "SELECT bool_and(greatest(observed, previous+interval '1 microsecond') "
          "=previous+interval '1 microsecond') FROM readings") == "t", "equal/backward clock selects previous plus one microsecond")
# Force a previous coordinate ahead of wall clock in the disposable fixture only.
sql("BEGIN;" + advance_budget()
    + "UPDATE stamp SET t=clock_timestamp()+interval '1 day';"
    + f"UPDATE budget.budget_revision SET changed_at=(SELECT t FROM stamp) WHERE budget_id={q(BUDGET)} "
      f"AND version=(SELECT current_version FROM budget.budget_guard WHERE budget_id={q(BUDGET)});"
    + f"UPDATE budget.budget_guard SET last_changed_at=(SELECT t FROM stamp) WHERE budget_id={q(BUDGET)};"
    + state_insert.format(state="'SET'") + amount_insert.format(time="t") + "COMMIT;")
previous = sql(f"SELECT last_changed_at FROM budget.budget_guard WHERE budget_id={q(BUDGET)}")
sql("BEGIN;" + advance_budget() + state_insert.format(state="'UNSET'") + "COMMIT;")
check(sql(f"SELECT last_changed_at={q(previous)}::timestamptz+interval '1 microsecond' "
          f"FROM budget.budget_guard WHERE budget_id={q(BUDGET)}") == "t", "real write after a future coordinate advances exactly one microsecond")
check(sql("SELECT to_char('2026-11-04 12:00:00+00'::timestamptz AT TIME ZONE 'Europe/Moscow', 'DD.MM.YYYY HH24:MI:SS')")
      == "04.11.2026 15:00:00", "UTC instant converts to Moscow display")
check(sql(f"SELECT bool_and(r.budget_id IS NOT NULL AND s.changed_at=date_trunc('microseconds',s.changed_at)) "
          f"FROM budget.line_amount_state s JOIN budget.line_key k USING(line_id) "
          f"LEFT JOIN budget.budget_revision r ON r.budget_id=k.budget_id AND r.changed_at=s.changed_at "
          f"WHERE k.budget_id={q(BUDGET)}") == "t", "changed facts use revision coordinates at microsecond precision")
print(f"All {CHECKS} checks passed.")
