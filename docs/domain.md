# Budget values and category catalog

Implements [issue #2](https://github.com/finance-lab-project/budget/issues/2), following
the [accepted contract](https://github.com/finance-lab-project/app/blob/main/docs/architecture.md)
and the [frozen schema brief](schema.md). All fixtures are synthetic.

## Values and monthly snapshots

`org.financelab.budget.domain` depends only on the JDK. `BudgetAmount` explicitly
represents `UNSET` or `SET(BigDecimal)`. Every typed constructor validates the
inclusive 0.00–99999999999999999.99 range and rejects scale greater than two,
including trailing zeros, before normalizing to scale two. No doubles or rounding
are used. UNSET contributes zero to totals and remains distinct from SET(0.00).

`Budget` records a UUID, owner UUID, `YearMonth` and explicit `BudgetCurrency.RUB`.
One shared `BudgetLine` model has immutable INCOME/SPENDING/INVESTMENT kind, a UUID,
an amount and a category reference required only for SPENDING. A monthly snapshot
has exactly one income line, one investment line and at most one line per spending
category. Spending totals exclude investment; remaining is income minus spending
minus investment. Derived totals can exceed a single amount's maximum or be negative.

`Budget.create` makes a fresh month from the owner's active categories.
`withIncome`, `withInvestment` and `withSpending` return new snapshots, preserving
Budget/line identity. Updating an archived category's existing line is allowed.
`withCurrentCatalog` prepares a current draft with active missing categories as
UNSET, retaining every existing line (including archived SET(0.00)/UNSET lines).
It performs no writes. A future explicit Save persists new membership; catalog
edits alone do not rewrite stored months.

`Budget.restore` validates exact reconstructed membership against a supplied
catalog and adds no categories. Current presentation uses `spending(currentCatalog)`;
historical presentation must supply the catalog snapshot pinned by that revision.
Unknown category references and foreign owners fail rather than dropping amounts.
Immutable Budget/catalog snapshots preserve the supplied old values and labels;
loading revisions and recording their versions belong to the
[persistence adapter](persistence.md).

## Owner-wide catalog

`CategoryCatalog.initialize(owner, existing)` creates the ten agreed defaults only
when the catalog is absent. An existing catalog is returned unchanged, even if
empty or entirely archived. UUID v4 identities are generated with
`UUID.randomUUID()` before any later persistence. Labels are trimmed and nonempty;
duplicates are allowed. IDs never derive from labels or positions.

Add, rename, reorder and archive return new catalogs. Full reorder includes every
category ID exactly once and assigns contiguous positions starting at one.
Archiving retains the definition and identity permanently. No hard delete, merge,
reassignment or restore operation is exposed. Adding a same-named category creates
a new ID. One catalog is supplied to all of an owner's months; budgets contain
category references, not independent catalog copies.

Initialization here is a pure domain factory. The persistence adapter's
`initializeCatalog` claims the catalog and persists its complete initial revision
atomically after migrations, following [the write protocol](schema.md#initialization-and-write-protocol).
The domain remains independent of database access and transactions; the composition
root must invoke the adapter's initializer after migrations.

## Russian presentation boundary

`adapter.presentation.RussianBudgetFormat` keeps localization outside the domain
and needs no Vaadin dependency. Money is parsed directly from text into BigDecimal:

- ASCII digits, comma or dot decimal separator, one or two optional fractional digits;
- optional space grouping in threes, validated before stripping separators;
- U+0020, U+00A0 and U+202F grouping/trimming, with all other whitespace rejected;
- leading zeros and fractional shorthand such as `,5`;
- empty/all-accepted-space input means UNSET; signs, exponents, currency text,
  malformed grouping, excess precision and incomplete edits such as `1,` fail validation.

An adapter can retain any draft text while editing and call `parseAmount` for
validation; a rejected partial edit is never converted to a savable amount.
Formatting uses explicit ru-RU, a decimal comma, NBSP grouping and two digits,
with NBSP before ₽. Unset displays as empty; `formatMoney` also handles derived
negative and oversized totals. Dates require exact-width `dd.MM.uuuu` and strict
calendar validity. Budget months display independently as `ноябрь 2026`.
History instants display in explicitly labelled `Europe/Moscow` regardless of
machine defaults. Calendar widgets and translations remain UI work.

## Build and focused verification

Use the application's pinned Java 27 and Maven 3.10.0 toolchain:

```sh
scripts/prepare-orm.sh
mvn -B -ntp test -Dsource.revision="$(git rev-parse HEAD)"
```

JUnit 6.1.3 is test-only. The tests cover the accepted money/date examples and
invalid boundaries, typed-value precision, unset/zero, exact/negative totals,
UUID identity, default order, duplicate/blank labels, seeding preservation,
cross-month catalog changes and archive behavior. No database is needed; the
existing [schema harness](../verification/README.md) remains independent. Use
`scripts/verify.sh` for the [Java persistence integration tests](persistence.md#verification).

The parent is not yet published. As in the ORM module, the build uses an exact
vendored copy of `org.financelab:build-parent:0.1.0` so a clean checkout needs no
sibling source tree or uncommitted parent. Source:
[app commit d813f7c](https://github.com/finance-lab-project/app/blob/d813f7c18fccc192c43523fd0f7869ef3d7ab3ce/build-parent/pom.xml).
`build-support/build-parent-0.1.0.pom` SHA-256:
`aa61d398a42e3df231b696532a545872296bba8d039b453cf5ad98f5b284a34f`.
The module overrides source repository metadata and inherits toolchain/plugin
pins. Deployment/publication is skipped. Switching to a separately authorized
immutable registry and integrating the Budget JAR into the WAR remain separate work.
