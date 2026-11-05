# Finance Lab Budget

Monthly RUB planning, ten default spending categories, add/rename/reorder/archive, exact totals, explicit Save and read-only recorded history.

First-release design and delivery work is tracked in this repository.

The canonical [architecture](https://github.com/finance-lab-project/app/blob/main/docs/architecture.md) and [release plan](https://github.com/finance-lab-project/app/blob/main/docs/roadmap.md) live in `app`. Track this module's work in [its issues](https://github.com/finance-lab-project/budget/issues) and its private project board.

Java without Spring. Business modules own their domain, application API, Vaadin adapters, persistence mappings and reviewed SQL migrations. The ORM is a library; business rules and aggregate revisions remain with the business module. Root assembly pins immutable module versions.

The [Budget domain](docs/domain.md) implements RUB amounts, exact monthly totals,
the owner-wide category catalog and Russian input/presentation for [issue #2](https://github.com/finance-lab-project/budget/issues/2).
The [persistence adapter](docs/persistence.md) implements atomic catalog/Budget
saves, initialization, current reads and recorded history for [issue #3](https://github.com/finance-lab-project/budget/issues/3).
Run `scripts/verify.sh` with the pinned Java 27 toolchain, Maven 3.10.0 and Docker
already running. It builds the exact ORM dependency and verifies the module on
disposable PostgreSQL 18.6. UI and application assembly integration remain separate work.

The [R1 schema design](docs/schema.md) includes the original Flyway migration,
diagram, write/read contracts and [SQL design verification](verification/README.md).
The adapter uses that migration unchanged. No runnable UI or deployment is claimed.
Separate learning issues are not part of this backlog. Use synthetic examples only;
no real financial data, employer material, secrets or local paths.
