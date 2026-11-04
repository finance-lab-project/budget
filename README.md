# Finance Lab Budget

Monthly RUB planning, ten default spending categories, add/rename/reorder/archive, exact totals, explicit Save and read-only recorded history.

First-release design and delivery work is tracked in this repository.

The canonical [architecture](https://github.com/finance-lab-project/app/blob/main/docs/architecture.md) and [release plan](https://github.com/finance-lab-project/app/blob/main/docs/roadmap.md) live in `app`. Track this module's work in [its issues](https://github.com/finance-lab-project/budget/issues) and its private project board.

Java without Spring. Business modules own their domain, application API, Vaadin adapters, persistence mappings and reviewed SQL migrations. The ORM is a library; business rules and aggregate revisions remain with the business module. Root assembly pins immutable module versions.

The [R1 schema design](docs/schema.md) includes a draft Flyway migration, diagram, write/read contracts and [disposable PostgreSQL verification](verification/README.md). It is design work; no runnable application or deployment is claimed. Separate learning issues are not part of this backlog. Use synthetic examples only; no real financial data, employer material, secrets or local paths.
