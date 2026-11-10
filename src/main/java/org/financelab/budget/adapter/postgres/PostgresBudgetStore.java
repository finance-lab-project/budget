package org.financelab.budget.adapter.postgres;

import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.application.SaveResult;
import org.financelab.budget.domain.Budget;
import org.financelab.budget.domain.BudgetAmount;
import org.financelab.budget.domain.BudgetCurrency;
import org.financelab.budget.domain.BudgetLine;
import org.financelab.budget.domain.Category;
import org.financelab.budget.domain.CategoryCatalog;
import org.financelab.orm.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.financelab.budget.adapter.postgres.BudgetMappings.*;
import static org.financelab.budget.application.SaveResult.Aggregate.CATALOG;

/** One ORM scope per operation. Only this adapter chooses revisions, locks and change boundaries. */
public final class PostgresBudgetStore implements BudgetStore {
    private final Transactions transactions;
    private final Consumer<Throwable> diagnostics;

    /** Diagnostics are for protected operator handling, never user-facing error text. */
    public PostgresBudgetStore(Transactions transactions, Consumer<Throwable> diagnostics) {
        this.transactions = Objects.requireNonNull(transactions);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    /** Invoke once after the composition root has applied the module's Flyway migrations. */
    public void verifyMappings() {
        read(s -> { MappingSchema.verify(s, ALL); return null; });
    }

    @Override public SaveResult<CatalogRevision> initializeCatalog(UUID owner) {
        if (owner == null) return invalid("owner");
        CategoryCatalog initial = CategoryCatalog.initialize(owner, Optional.empty());
        return write(s -> {
            Instant at = coordinate(s, null);
            var claimed = s.query(new Sql("""
                    INSERT INTO budget.catalog_guard(owner_id, current_version, last_changed_at)
                    VALUES (?, 1, ?) ON CONFLICT (owner_id) DO NOTHING RETURNING owner_id
                    """), List.of(uuid(owner), time(at)), r -> required(r, 1, Codecs.UUID));
            if (claimed.isEmpty()) return new SaveResult.Unchanged<>(catalogGuard(s, owner, "").orElseThrow());
            for (Category category : initial.categories()) insertCategory(s, category, at);
            CatalogRevision revision = new CatalogRevision(1, at);
            insertCatalogRevision(s, owner, revision);
            return new SaveResult.Saved<>(revision);
        });
    }

    @Override public SaveResult<CatalogRevision> saveCatalog(UUID owner, long expectedVersion, CategoryCatalog draft) {
        if (owner == null || draft == null || !owner.equals(draft.ownerId())) return invalid("catalog.owner");
        if (expectedVersion < 1) return invalid("expectedVersion");
        return write(s -> {
            var guard = catalogGuard(s, owner, " FOR UPDATE");
            if (guard.isEmpty() || guard.get().version() != expectedVersion) return new SaveResult.Conflict<>(CATALOG);
            CategoryCatalog previous = loadCatalog(s, owner, guard.get()).catalog();
            Map<UUID, Category> old = categories(previous);
            Map<UUID, Category> next = categories(draft);
            if (!next.keySet().containsAll(old.keySet())) return invalid("catalog.deletedCategory");
            for (Category category : draft.categories()) {
                Category before = old.get(category.id());
                if (before != null && before.status() == Category.Status.ARCHIVED
                        && category.status() != Category.Status.ARCHIVED) return invalid("catalog.restore");
                if (before == null && s.exists(CATEGORY, category.id())) return invalid("catalog.categoryId");
            }
            if (previous.equals(draft)) return new SaveResult.Unchanged<>(guard.get());
            long version = Math.incrementExact(expectedVersion);
            Instant at = coordinate(s, guard.get().changedAt());
            for (Category category : draft.categories()) {
                Category before = old.get(category.id());
                if (before == null) insertCategory(s, category, at);
                else {
                    if (!before.label().equals(category.label())) s.append(NAME, category.id(), at, category.label());
                    if (before.position() != category.position()) s.append(POSITION, category.id(), at, category.position());
                    if (before.status() != category.status()) s.append(STATUS, category.id(), at, category.status().name());
                }
            }
            CatalogRevision revision = new CatalogRevision(version, at);
            execute(s, "UPDATE budget.catalog_guard SET current_version = ?, last_changed_at = ? WHERE owner_id = ?",
                    number(version), time(at), uuid(owner));
            insertCatalogRevision(s, owner, revision);
            return new SaveResult.Saved<>(revision);
        });
    }

    @Override public SaveResult<BudgetRevision> save(UUID owner, SaveBudget command) {
        List<String> errors = validate(owner, command);
        if (!errors.isEmpty()) return new SaveResult.Invalid<>(errors);
        Map<UUID, BudgetAmount> values = new HashMap<>();
        command.spending().forEach(v -> values.put(v.categoryId(), v.amount()));
        return write(s -> {
            // FOR SHARE conflicts with catalog updates even when no catalog key changes.
            var catalogRevision = catalogGuard(s, owner, " FOR SHARE");
            if (catalogRevision.isEmpty() || catalogRevision.get().version() != command.expectedCatalogVersion()) {
                return new SaveResult.Conflict<>(CATALOG);
            }
            var guard = budgetGuard(s, owner, command.month(), " FOR UPDATE");
            if (guard.map(Guard::version).orElse(0L) != command.expectedVersion()) return new SaveResult.Conflict<>(SaveResult.Aggregate.BUDGET);
            CatalogView catalog = loadCatalog(s, owner, catalogRevision.get());
            Budget previous = guard.isEmpty() ? null : loadBudget(s, owner, command.month(), guard.get().id(),
                    guard.get().changedAt(), catalog.catalog());
            Budget draft = previous == null ? Budget.create(owner, command.month(), catalog.catalog())
                    : previous.withCurrentCatalog(catalog.catalog());
            if (command.importingSavedLines()) {
                draft = draft.withCurrentCatalog(catalog.catalog(), values.keySet());
            }
            var permitted = new HashSet<UUID>();
            draft.lines().forEach(line -> line.categoryId().ifPresent(permitted::add));
            if (!permitted.equals(values.keySet())) return invalid("spending.categories");
            draft = draft.withIncome(command.income()).withInvestment(command.investment());
            for (var value : values.entrySet()) draft = draft.withSpending(value.getKey(), value.getValue());
            if (previous != null && previous.lines().equals(draft.lines())) {
                return new SaveResult.Unchanged<>(budgetRevision(s, owner, previous.id(), guard.get().version()).orElseThrow());
            }
            long version = Math.incrementExact(command.expectedVersion());
            Instant at = coordinate(s, guard.map(Guard::changedAt).orElse(null));
            if (previous == null) insertBudget(s, draft, at);
            Map<UUID, BudgetLine> old = new HashMap<>();
            if (previous != null) previous.lines().forEach(line -> old.put(line.id(), line));
            for (BudgetLine line : draft.lines()) {
                BudgetLine before = old.get(line.id());
                if (before == null) insertLine(s, draft, line, at);
                else if (!before.amount().equals(line.amount())) appendAmount(s, line, at);
            }
            if (previous != null) {
                execute(s, "UPDATE budget.budget_guard SET current_version = ?, last_changed_at = ? WHERE budget_id = ? AND owner_id = ?",
                        number(version), time(at), uuid(draft.id()), uuid(owner));
            }
            BudgetRevision revision = new BudgetRevision(draft.id(), version, at, catalogRevision.get().version());
            execute(s, """
                    INSERT INTO budget.budget_revision(budget_id, version, changed_at, owner_id, catalog_version)
                    VALUES (?, ?, ?, ?, ?)
                    """, uuid(draft.id()), number(version), time(at), uuid(owner), number(revision.catalogVersion()));
            return new SaveResult.Saved<>(revision);
        });
    }

    @Override public Optional<CatalogView> catalog(UUID owner) {
        Objects.requireNonNull(owner);
        return read(s -> catalogGuard(s, owner, "").map(revision -> loadCatalog(s, owner, revision)));
    }

    @Override public BudgetView current(UUID owner, YearMonth month) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(month);
        return read(s -> {
            CatalogView catalog = loadCatalog(s, owner, catalogGuard(s, owner, "").orElseThrow(
                    () -> new IllegalStateException("Catalog must be initialized before Budget use")));
            var guard = budgetGuard(s, owner, month, "");
            if (guard.isEmpty()) return new BudgetView(Budget.create(owner, month, catalog.catalog()), catalog, Optional.empty());
            Budget budget = loadBudget(s, owner, month, guard.get().id(), guard.get().changedAt(), catalog.catalog());
            return new BudgetView(budget.withCurrentCatalog(catalog.catalog()), catalog,
                    Optional.of(budgetRevision(s, owner, budget.id(), guard.get().version()).orElseThrow()));
        });
    }

    @Override public List<BudgetRevision> revisions(UUID owner, UUID budgetId) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(budgetId);
        return read(s -> s.query(new Sql("""
                SELECT budget_id, version, changed_at, catalog_version FROM budget.budget_revision
                WHERE owner_id = ? AND budget_id = ? ORDER BY version DESC
                """), List.of(uuid(owner), uuid(budgetId)), PostgresBudgetStore::mapRevision));
    }

    @Override public Optional<BudgetView> revision(UUID owner, UUID budgetId, long version) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(budgetId);
        if (version < 1) return Optional.empty();
        return read(s -> budgetRevision(s, owner, budgetId, version).map(revision -> {
            CatalogRevision catalogRevision = one(s.query(new Sql("""
                    SELECT version, changed_at FROM budget.catalog_revision WHERE owner_id = ? AND version = ?
                    """), List.of(uuid(owner), number(revision.catalogVersion())), PostgresBudgetStore::mapCatalogRevision)).orElseThrow();
            CatalogView catalog = loadCatalog(s, owner, catalogRevision);
            YearMonth month = YearMonth.from(s.readStatic(MONTH, budgetId).orElseThrow());
            Budget budget = loadBudget(s, owner, month, budgetId, revision.changedAt(), catalog.catalog());
            return new BudgetView(budget, catalog, Optional.of(revision));
        }));
    }

    private static List<String> validate(UUID owner, SaveBudget command) {
        var errors = new ArrayList<String>();
        if (owner == null) errors.add("owner");
        if (command == null) { errors.add("command"); return errors; }
        if (command.month() == null || command.month().getYear() < -4712 || command.month().getYear() > 5874897) errors.add("month");
        if (command.expectedVersion() < 0) errors.add("expectedVersion");
        if (command.expectedCatalogVersion() < 1) errors.add("expectedCatalogVersion");
        if (command.income() == null) errors.add("income");
        if (command.investment() == null) errors.add("investment");
        var ids = new HashSet<UUID>();
        if (command.spending() == null) errors.add("spending");
        else for (SpendingValue value : command.spending()) {
            if (value == null || value.categoryId() == null || value.amount() == null || !ids.add(value.categoryId())) {
                errors.add("spending.values"); break;
            }
        }
        return errors;
    }

    private static Map<UUID, Category> categories(CategoryCatalog catalog) {
        var result = new HashMap<UUID, Category>();
        catalog.categories().forEach(category -> result.put(category.id(), category));
        return result;
    }

    private static void insertCategory(WriteSession s, Category category, Instant at) {
        s.insertAnchor(CATEGORY, category.id());
        s.insertStatic(OWNER, category.id(), category.ownerId());
        s.append(NAME, category.id(), at, category.label());
        s.append(POSITION, category.id(), at, category.position());
        s.append(STATUS, category.id(), at, category.status().name());
    }

    private static void insertCatalogRevision(WriteSession s, UUID owner, CatalogRevision revision) {
        execute(s, "INSERT INTO budget.catalog_revision(owner_id, version, changed_at) VALUES (?, ?, ?)",
                uuid(owner), number(revision.version()), time(revision.changedAt()));
    }

    private static void insertBudget(WriteSession s, Budget budget, Instant at) {
        s.insertAnchor(BudgetMappings.BUDGET, budget.id());
        s.insertStatic(BUDGET_OWNER, budget.id(), budget.ownerId());
        s.insertStatic(MONTH, budget.id(), budget.month().atDay(1));
        s.insertStatic(CURRENCY, budget.id(), budget.currency().name());
        var claimed = s.query(new Sql("""
                INSERT INTO budget.budget_guard(budget_id, owner_id, month, income_line_id, investment_line_id,
                    current_version, last_changed_at) VALUES (?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (owner_id, month) DO NOTHING RETURNING budget_id
                """), List.of(uuid(budget.id()), uuid(budget.ownerId()), Param.of(Codecs.DATE, budget.month().atDay(1)),
                uuid(budget.income().id()), uuid(budget.investment().id()), time(at)), r -> required(r, 1, Codecs.UUID));
        // Roll back the losing attempt's anchors/static facts as well as its absent guard claim.
        if (claimed.isEmpty()) throw new CreationConflict();
    }

    private static void insertLine(WriteSession s, Budget budget, BudgetLine line, Instant at) {
        s.insertAnchor(LINE, line.id());
        s.insertTie(IN_BUDGET, line.id(), budget.id());
        s.insertStatic(KIND, line.id(), line.kind().name());
        line.categoryId().ifPresent(id -> s.insertTie(LINE_CATEGORY, line.id(), id));
        execute(s, """
                INSERT INTO budget.line_key(line_id, budget_id, owner_id, kind_code, category_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, uuid(line.id()), uuid(budget.id()), uuid(budget.ownerId()), Param.of(Codecs.TEXT, line.kind().name()),
                new Param<>(Codecs.UUID, line.categoryId()), time(at));
        appendAmount(s, line, at);
    }

    private static void appendAmount(WriteSession s, BudgetLine line, Instant at) {
        s.append(STATE, line.id(), at, line.amount().state().name());
        line.amount().value().ifPresent(amount -> s.append(AMOUNT, line.id(), at, amount));
    }

    private static CatalogView loadCatalog(ReadSession s, UUID owner, CatalogRevision revision) {
        record CategoryStart(UUID id, Instant at) { }
        var starts = s.query(new Sql("""
                SELECT owned.category_id,
                    (SELECT min(changed_at) FROM budget.category_name WHERE category_id = owned.category_id),
                    (SELECT min(changed_at) FROM budget.category_position WHERE category_id = owned.category_id),
                    (SELECT min(changed_at) FROM budget.category_status_history WHERE category_id = owned.category_id)
                FROM budget.category_owner owned WHERE owned.owner_id = ?
                """), List.of(uuid(owner)), r -> {
            Instant at = required(r, 2, Codecs.INSTANT);
            if (!at.equals(required(r, 3, Codecs.INSTANT)) || !at.equals(required(r, 4, Codecs.INSTANT))) {
                throw new IllegalStateException("Inconsistent category creation facts");
            }
            return new CategoryStart(required(r, 1, Codecs.UUID), at);
        });
        var categories = new ArrayList<Category>();
        for (CategoryStart start : starts) {
            if (revision.changedAt().isBefore(start.at())) continue;
            UUID id = start.id();
            var name = s.asOf(NAME, id, revision.changedAt());
            var position = s.asOf(POSITION, id, revision.changedAt());
            var status = s.asOf(STATUS, id, revision.changedAt());
            categories.add(new Category(id, owner, name.orElseThrow().value(), position.orElseThrow().value(),
                    Category.Status.valueOf(status.orElseThrow().value())));
        }
        categories.sort(Comparator.comparingInt(Category::position));
        return new CatalogView(new CategoryCatalog(owner, categories), revision);
    }

    private static Budget loadBudget(ReadSession s, UUID owner, YearMonth month, UUID id, Instant at, CategoryCatalog catalog) {
        record LineKey(UUID id, BudgetLine.Kind kind, Optional<UUID> categoryId) { }
        var keys = s.query(new Sql("""
                SELECT line_id, kind_code, category_id FROM budget.line_key
                WHERE budget_id = ? AND owner_id = ? AND created_at <= ? ORDER BY line_id
                """), List.of(uuid(id), uuid(owner), time(at)), r -> new LineKey(required(r, 1, Codecs.UUID),
                BudgetLine.Kind.valueOf(required(r, 2, Codecs.TEXT)), r.get(3, Codecs.UUID)));
        var lines = new ArrayList<BudgetLine>();
        for (LineKey key : keys) {
            var state = s.asOf(STATE, key.id(), at).orElseThrow();
            var amount = s.asOf(AMOUNT, key.id(), state.changedAt());
            BudgetAmount value = switch (state.value()) {
                case "UNSET" -> {
                    if (amount.filter(f -> f.changedAt().equals(state.changedAt())).isPresent()) {
                        throw new IllegalStateException("UNSET has a companion amount");
                    }
                    yield BudgetAmount.UNSET;
                }
                case "SET" -> {
                    var companion = amount.filter(f -> f.changedAt().equals(state.changedAt())).orElseThrow(
                            () -> new IllegalStateException("SET lacks its exact companion amount"));
                    yield BudgetAmount.set(companion.value());
                }
                default -> throw new IllegalStateException("Unknown amount state");
            };
            lines.add(new BudgetLine(key.id(), key.kind(), key.categoryId(), value));
        }
        return Budget.restore(id, owner, month, BudgetCurrency.valueOf(s.readStatic(CURRENCY, id).orElseThrow()), lines, catalog);
    }

    private record Guard(UUID id, long version, Instant changedAt) { }

    private static Optional<Guard> budgetGuard(ReadSession s, UUID owner, YearMonth month, String lock) {
        return one(s.query(new Sql("""
                SELECT budget_id, current_version, last_changed_at FROM budget.budget_guard
                WHERE owner_id = ? AND month = ?
                """ + lock), List.of(uuid(owner), Param.of(Codecs.DATE, month.atDay(1))),
                r -> new Guard(required(r, 1, Codecs.UUID), required(r, 2, Codecs.BIGINT), required(r, 3, Codecs.INSTANT))));
    }

    private static Optional<CatalogRevision> catalogGuard(ReadSession s, UUID owner, String lock) {
        return one(s.query(new Sql("SELECT current_version, last_changed_at FROM budget.catalog_guard WHERE owner_id = ?" + lock),
                List.of(uuid(owner)), PostgresBudgetStore::mapCatalogRevision));
    }

    private static Optional<BudgetRevision> budgetRevision(ReadSession s, UUID owner, UUID id, long version) {
        return one(s.query(new Sql("""
                SELECT budget_id, version, changed_at, catalog_version FROM budget.budget_revision
                WHERE owner_id = ? AND budget_id = ? AND version = ?
                """), List.of(uuid(owner), uuid(id), number(version)), PostgresBudgetStore::mapRevision));
    }

    private static BudgetRevision mapRevision(Row r) {
        return new BudgetRevision(required(r, 1, Codecs.UUID), required(r, 2, Codecs.BIGINT),
                required(r, 3, Codecs.INSTANT), required(r, 4, Codecs.BIGINT));
    }

    private static CatalogRevision mapCatalogRevision(Row r) {
        return new CatalogRevision(required(r, 1, Codecs.BIGINT), required(r, 2, Codecs.INSTANT));
    }

    private static Instant coordinate(ReadSession s, Instant previous) {
        return s.query(new Sql(previous == null ? "SELECT clock_timestamp()" :
                        "SELECT greatest(clock_timestamp(), CAST(? AS timestamptz) + interval '1 microsecond')"),
                previous == null ? List.of() : List.of(time(previous)), r -> required(r, 1, Codecs.INSTANT)).getFirst();
    }

    private <T> SaveResult<T> write(Work<WriteSession, SaveResult<T>> work) {
        try {
            Completed<SaveResult<T>> completed = transactions.write(work);
            completed.cleanupFailures().forEach(this::report);
            return completed.value(); // Only a confirmed commit reaches here.
        } catch (PersistenceFailure failure) {
            if (failure.outcome() == WriteOutcome.UNKNOWN) { report(failure); return new SaveResult.OutcomeUnknown<>(); }
            if (failure.getCause() instanceof CreationConflict) return new SaveResult.Conflict<>(SaveResult.Aggregate.BUDGET);
            report(failure);
            return new SaveResult.Failed<>();
        }
    }

    private <T> T read(Work<ReadSession, T> work) {
        try { return transactions.snapshot(work).value(); }
        catch (PersistenceFailure failure) {
            report(failure);
            throw new IllegalStateException("Budget read failed");
        }
    }

    private void report(Throwable failure) {
        // A diagnostics sink cannot change a confirmed commit into a failure.
        try { diagnostics.accept(failure); }
        catch (RuntimeException ignored) { /* The composition root owns the diagnostics sink. */ }
    }

    private static final class CreationConflict extends RuntimeException { }
    private static <T> SaveResult<T> invalid(String field) { return new SaveResult.Invalid<>(List.of(field)); }
    private static <T> Optional<T> one(List<T> rows) {
        if (rows.size() > 1) throw new IllegalStateException("Expected at most one row");
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
    private static <T> T required(Row row, int index, Codec<T> codec) { return row.get(index, codec).orElseThrow(); }
    private static Param<UUID> uuid(UUID value) { return Param.of(Codecs.UUID, value); }
    private static Param<Long> number(long value) { return Param.of(Codecs.BIGINT, value); }
    private static Param<Instant> time(Instant value) { return Param.of(Codecs.INSTANT, value); }
    private static void execute(WriteSession s, String sql, Param<?>... params) {
        if (s.execute(new Sql(sql), List.of(params)) != 1) throw new IllegalStateException("Expected one changed row");
    }
}
