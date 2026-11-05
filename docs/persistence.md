# Atomic Budget/catalog saves and recorded history

Implements [issue #3](https://github.com/finance-lab-project/budget/issues/3) using
the [accepted schema](schema.md) and the custom ORM. Domain classes remain free
of JDBC, ORM, Vaadin and Spring dependencies. The existing V1 migration is used
unchanged; no new schema, retry receipts or database triggers are needed.

## Application boundary

`application.BudgetStore` exposes initialization, catalog saves, monthly saves,
current-editor reads, revision lists and revision details. Its records contain
domain values and revision metadata, with no persistence exceptions. Construct
`adapter.postgres.PostgresBudgetStore` with the root-owned ORM `Transactions` and
an operator diagnostics sink. The root must apply Flyway migrations first, call
`verifyMappings()` once, then call `initializeCatalog(owner)` before Budget use.
The adapter does not create schema, run migrations, own a pool or install a UI.

Every owner argument comes from authenticated server context. Catalog draft
ownership is checked against it; submitted Budget values contain no owner or
Budget/line identity. The adapter creates UUIDs and preserves stored identities.
A monthly command carries the month, expected Budget/catalog versions, explicit
income/investment amounts and a list of category-ID/amount pairs. Missing or
duplicate values are invalid, including missing income/investment companions.
Money is already validated by `BudgetAmount`; presentation parsing happens
before constructing a typed command.

The permitted spending set is exactly today's active categories plus archived
categories already represented in that month. An archived existing line remains
editable, visible and included in totals, including SET(0.00) and UNSET. Newly
added active categories appear as virtual UNSET lines during reads and become
stored lines only on explicit Save. Current reads never write a draft to storage.

Catalog saves accept the domain's complete immutable snapshot with an expected
version. They allow add, rename, reorder and archive, reject deletion, restoration
and reuse of another category's identity, and never rewrite monthly amounts.
Initialization claims the owner with `ON CONFLICT DO NOTHING`, atomically records
all ten defaults and catalog revision 1, and leaves any existing catalog intact.

## Transactions and results

Each write uses one ORM READ COMMITTED transaction. Budget Save obtains
`catalog_guard FOR SHARE` before `budget_guard FOR UPDATE` and checks both
expected versions. Catalog saves obtain `catalog_guard FOR UPDATE`. These locks
last through commit or rollback. A competing first creation claims the unique
owner/month guard; a losing claim aborts the entire attempt, including its newly
inserted identities/static facts, and returns Conflict.

After version checks, the adapter compares normalized amounts and membership.
A no-op leaves all facts, guards, timestamps and revisions unchanged. A changed
save chooses one coordinate with `greatest(clock_timestamp(), previous + 1 us)`;
the first revision uses the database clock alone. Changed facts, guard and
revision reuse that coordinate. New lines record only their final initial state.
SET appends the amount and state at the same coordinate; clearing appends UNSET.
The existing deferred foreign keys reject missing or mismatched companions at
real commit. All operational writes assert their affected-row count.

`SaveResult` distinguishes:

- `Saved`: commit confirmed, with the new revision.
- `Unchanged`: valid/current command with the existing revision. A newer catalog
  alone does not revise a Budget or replace its original catalog-version pin.
- `Invalid`: field/domain errors, with no writes.
- `Conflict`: stale Budget/catalog version or competing first creation.
- `Failed`: the ORM knows that the transaction did not commit.
- `OutcomeUnknown`: the commit response was lost or otherwise inconclusive.

No command or SQL statement is automatically retried. After Unknown, the caller
retains the draft and rereads current state/history before a user-directed save;
matching values do not prove which request committed. Confirmed commits stay
Saved if cleanup or the diagnostics sink fails. Raw failure/cleanup diagnostics
go only to the supplied operator sink; public outcomes contain no SQL or values.
Reads fail with a generic exception after reporting diagnostics, never fall back
to a partial or latest projection. Authentication/UI feedback and root wiring
remain the corresponding application/UI issues.

## Reads and mappings

`BudgetMappings` owns the explicit anchor, static attribute, knot, recorded
attribute and tie descriptors. Writes use the ORM's generated parameterized
operations; reviewed guard/key/revision SQL runs on the same session. Mapping
verification checks descriptors against the already migrated schema.

All multi-query reads use one read-only REPEATABLE READ snapshot. Owner-scoped
queries select the identities before mapped facts are read. `current` combines
current catalog facts, saved monthly amounts and virtual active lines. An absent
Budget has version zero. Revision lists contain committed entries in descending
version order. Unknown or foreign-owned revision IDs return absence.

A revision detail reads monthly membership/amounts at its Budget coordinate and
catalog labels/order/status at its separately pinned catalog revision. Later
categories do not appear in old details. Required category facts must be complete;
SET must have an amount at exactly its state's coordinate. The adapter recalculates
totals with the domain model. There is no restore, history editing or backdating API.

The initial mapper deliberately assembles explicit per-identity projections.
The ten-category, twelve-line saved current view uses 60 projection statements
on one borrowed connection, excluding transaction setup/cleanup. The integration
test asserts that count and snapshot consistency. This is a measured baseline,
not a performance claim; query batching is not part of this issue.

## Verification

Use Java 27.0.1.0.3, Maven 3.10.0 and an already running Docker engine:

```sh
scripts/verify.sh
git diff --check
```

The approved artifact registry is not yet provisioned. `scripts/prepare-orm.sh`
builds and installs the accepted ORM from immutable commit
[`8daaf91d07d2e30b563322b22a0c6da7545578e5`](https://github.com/finance-lab-project/orm/commit/8daaf91d07d2e30b563322b22a0c6da7545578e5)
and its exact vendored parent into the local Maven cache. It runs the ORM unit
tests and explicitly skips that dependency's separate integration harness.
No artifact is published. The Budget verifier then creates a disposable
loopback-only PostgreSQL 18.6 container, applies/validates V1 with Flyway 13.12.0,
checks repeat migration, runs `mvn -B -ntp clean verify` with the checkout's source
revision, and removes the container on exit. Integration tests run with a
non-UTC JVM and fail rather than silently skip when database settings are absent.

The real database suite covers initialization/races/rollback, final initial
states, stale tabs and competing creation, catalog-before-Budget locking in both
race orders, exact decimal boundaries, no-ops, archived zero/UNSET lines, added
monthly membership, separate catalog revisions, original historical labels/order,
owner isolation, clear/set recurrence, controlled equal/backward clocks, partial
writes and actual deferred pairing rejection. JDBC proxies lose commit responses
before and after forwarding commit, and inject post-commit cleanup failure.
Assertions verify stored state, one commit attempt and no leaked pool borrows.

For domain-only checks after preparing the dependency:

```sh
scripts/prepare-orm.sh
mvn -B -ntp test -Dsource.revision="$(git rev-parse HEAD)"
```

Observed check counts/results belong in the implementing PR. UI/browser checks,
authentication, WAR assembly integration and deployment are outside this issue.
