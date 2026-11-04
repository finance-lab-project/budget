# Disposable PostgreSQL verification

This harness verifies the SQL design for issue #1. Run against a fresh disposable
database after Flyway; it intentionally writes synthetic fixtures. It contains
no production connection configuration and never runs Flyway clean.

Prerequisites: Docker, Java, Maven, `psql`, Python 3. The harness pins Flyway
11.14.1 and PostgreSQL JDBC 42.7.7 in `pom.xml`. These are verification versions;
production/bootstrap dependency selection remains separate work.

From the repository root, start the isolated database:

```sh
docker run --detach --rm --name budget-issue-1-check \
  --publish 127.0.0.1:55439:5432 \
  --env POSTGRES_USER=budget_check \
  --env POSTGRES_PASSWORD=disposable-check-only \
  --env POSTGRES_DB=budget_check \
  --tmpfs /var/lib/postgresql postgres:18.6
```

Wait until `docker exec budget-issue-1-check pg_isready -U budget_check` succeeds.
The password above is a public disposable fixture, never a deployment credential.
Apply and validate the migration:

```sh
mvn -B -f verification/pom.xml \
  -Dflyway.url=jdbc:postgresql://127.0.0.1:55439/budget_check \
  -Dflyway.user=budget_check \
  -Dflyway.password=disposable-check-only \
  flyway:migrate flyway:validate
```

Run the checks:

```sh
PGHOST=127.0.0.1 PGPORT=55439 PGDATABASE=budget_check \
PGUSER=budget_check PGPASSWORD=disposable-check-only \
python3 -u verification/check.py
```

Rerunning Flyway migrate/validate on the same database should report version 1
already applied and validate its checksum. The Python checks require a fresh
database, so start another disposable container/database for a full repeat.
When finished, remove the ephemeral container and data:

```sh
docker stop budget-issue-1-check
```

## Observed results — 2026-11-04

Environment: PostgreSQL server/client 18.6, Flyway 11.14.1, JDBC 42.7.7,
Java 24.0.1, Maven 3.10.0, Python 3.9.6. These are the actual verification tools,
not a claim that the application's stable-version baseline has been selected.

- The Maven command above successfully applied V1 to an empty database and
  validated one migration. An initial offline attempt lacked a cached Jackson
  dependency; the online command fetched it successfully. The final fresh run
  used the identical command with `-o` added and reported BUILD SUCCESS.
- The Python command above completed with **All 104 checks passed**. Negative
  cases check the expected SQLSTATE and compare every Budget table before/after.
  Pairing cases additionally require a marker emitted after all DML and before
  actual COMMIT, proving that rejection occurred at commit, not during assembly.
- The final migration SQL is the same SQL used in these runs. No production
  database was read or changed.
- A repeat of the offline Maven migrate/validate command reported schema version
  1 already up to date, no migration necessary, and BUILD SUCCESS.

Coverage includes fixed TEXT seeds and empty editable catalog; explicit UUID
storage; BIGINT revisions; atomic ten-category initialization and rollback;
existing/edited/all-archived catalog preservation; complete Budget creation;
each missing fixed line; duplicate owner/month, fixed-line and category keys;
same-owner references and contradictions between keys and Anchor facts; valid
SET/amount insertion in either order and UNSET-only commits; missing companion,
orphan amount, mismatched coordinate and UNSET-with-amount commit failures;
rollback of facts, guards and revisions; zero/maximum/range/non-finite amounts;
first-day dates; catalog-pinned historical labels/order/status; retained archived
lines; line absence before creation; new active categories without monthly rows;
set/clear/set history; unknown revisions; first-revision clocks; equal/backward
clock arithmetic and a real write after a future recording coordinate; shared
microsecond coordinates; UTC-to-Moscow conversion; no-write/no-op transactions.

The checks exercise representative SQL transactions, not a Java implementation.
They do not test user-input parsing, Russian UI formatting, Java UUID generation,
normalization-based no-op detection, actual Java startup initialization,
multi-session races, connection failure/unknown commit recovery or application
authorization. Those need application/ORM integration tests in the relevant
delivery issues. The SQL read examples are executed directly from
[`docs/queries.sql`](../docs/queries.sql).
