package org.financelab.budget.adapter.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.financelab.budget.application.BudgetStore.*;
import org.financelab.budget.application.SaveResult;
import org.financelab.budget.domain.*;
import org.financelab.orm.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PostgresBudgetStoreIT {
    static HikariDataSource pool;
    static JdbcTransactions tx;
    static PostgresBudgetStore store;
    static final YearMonth MONTH = YearMonth.of(2026, 11);

    @BeforeAll static void database() {
        String url = Objects.requireNonNull(System.getenv("BUDGET_TEST_URL"), "Run scripts/verify.sh with disposable PostgreSQL");
        String user = Objects.requireNonNull(System.getenv("BUDGET_TEST_USER"));
        var flyway = Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setMaximumPoolSize(6);
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("socketTimeout", "15");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        pool = new HikariDataSource(config);
        tx = new JdbcTransactions(pool);
        store = new PostgresBudgetStore(tx, failure -> { throw new AssertionError("Unexpected persistence failure", failure); });
        store.verifyMappings();
        String version = scalar("SELECT current_setting('server_version')", Codecs.TEXT);
        assertTrue(version.startsWith("18.6"), version);
    }

    @AfterEach void noBorrowedConnections() { assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections()); }
    @AfterAll static void shutdown() { if (pool != null) pool.close(); }

    @Test void initializationIsAtomicOnceOnlyAndPreservesEditedArchivedDefaults() {
        UUID owner = UUID.randomUUID();
        Instant before = scalar("SELECT clock_timestamp()", Codecs.INSTANT);
        CatalogRevision initial = saved(store.initializeCatalog(owner));
        Instant after = scalar("SELECT clock_timestamp()", Codecs.INSTANT);
        assertFalse(initial.changedAt().isBefore(before));
        assertFalse(initial.changedAt().isAfter(after));
        CatalogView first = store.catalog(owner).orElseThrow();
        assertEquals(10, first.catalog().categories().size());
        assertEquals(1, first.revision().version());
        for (Category category : first.catalog().categories()) {
            tx.snapshot(s -> {
                assertEquals(initial.changedAt(), s.current(BudgetMappings.NAME, category.id()).orElseThrow().changedAt());
                assertEquals(initial.changedAt(), s.current(BudgetMappings.POSITION, category.id()).orElseThrow().changedAt());
                assertEquals(initial.changedAt(), s.current(BudgetMappings.STATUS, category.id()).orElseThrow().changedAt());
                return null;
            });
        }
        CategoryCatalog edited = first.catalog().rename(first.catalog().categories().getFirst().id(), "  Changed  ");
        for (Category category : edited.categories()) edited = edited.archive(category.id());
        saved(store.saveCatalog(owner, 1, edited));
        var beforeNoop = counts();
        assertInstanceOf(SaveResult.Unchanged.class, store.initializeCatalog(owner));
        assertEquals(edited, store.catalog(owner).orElseThrow().catalog());
        assertEquals(beforeNoop, counts());
    }

    @Test void failedInitializationLeavesNoClaimOrPartialCategories() {
        UUID owner = UUID.randomUUID();
        var failures = new ArrayList<Throwable>();
        AtomicInteger appends = new AtomicInteger();
        var failing = new PostgresBudgetStore(hooked((method, args, call) -> {
            Object result = call.run();
            if (method.getName().equals("append") && appends.incrementAndGet() == 5) throw new IllegalStateException("Injected write failure");
            return result;
        }), failures::add);
        var before = counts();
        assertInstanceOf(SaveResult.Failed.class, failing.initializeCatalog(owner));
        assertEquals(before, counts());
        assertTrue(store.catalog(owner).isEmpty());
        assertEquals(1, failures.size());
        saved(store.initializeCatalog(owner));
    }

    @Test void competingInitializersCreateOnlyOneCompleteCatalog() throws Exception {
        UUID owner = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        var concurrent = new PostgresBudgetStore(hooked((method, args, call) -> {
            if (sql(args).startsWith("INSERT INTO budget.catalog_guard")) barrier.await(5, TimeUnit.SECONDS);
            return call.run();
        }), failure -> fail(failure));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> concurrent.initializeCatalog(owner));
            var b = executor.submit(() -> concurrent.initializeCatalog(owner));
            var outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(SaveResult.Saved.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(SaveResult.Unchanged.class::isInstance).count());
        }
        assertEquals(10, store.catalog(owner).orElseThrow().catalog().categories().size());
        assertEquals(1L, scalar("SELECT count(*) FROM budget.catalog_revision WHERE owner_id = ?", Codecs.BIGINT, id(owner)));
    }

    @Test void firstSaveWritesOnlyFinalInitialStatesAndNoopDoesNotWrite() {
        UUID owner = owner();
        var beforeRead = counts();
        BudgetView editor = store.current(owner, MONTH);
        assertEquals(0, editor.version());
        assertEquals(beforeRead, counts());
        UUID category = editor.catalog().catalog().categories().getFirst().id();
        Budget draft = editor.budget().withIncome(amount("100.10")).withInvestment(amount("20.00"))
                .withSpending(category, amount("0.00"));
        Instant beforeSave = scalar("SELECT clock_timestamp()", Codecs.INSTANT);
        BudgetRevision revision = saved(store.save(owner, command(editor, draft)));
        Instant afterSave = scalar("SELECT clock_timestamp()", Codecs.INSTANT);
        assertEquals(1, revision.version());
        assertFalse(revision.changedAt().isBefore(beforeSave));
        assertFalse(revision.changedAt().isAfter(afterSave));
        BudgetView current = store.current(owner, MONTH);
        assertEquals(new BigDecimal("80.10"), current.budget().remaining());
        for (BudgetLine line : current.budget().lines()) {
            tx.snapshot(s -> {
                assertEquals(1, s.history(BudgetMappings.STATE, line.id()).size());
                assertEquals(revision.changedAt(), s.current(BudgetMappings.STATE, line.id()).orElseThrow().changedAt());
                var amounts = s.history(BudgetMappings.AMOUNT, line.id());
                assertEquals(line.amount().value().isPresent() ? 1 : 0, amounts.size());
                amounts.forEach(f -> assertEquals(revision.changedAt(), f.changedAt()));
                return null;
            });
        }
        var beforeNoop = counts();
        assertEquals(new SaveResult.Unchanged<>(revision), store.save(owner, command(current)));
        assertEquals(beforeNoop, counts());
        assertEquals(revision, store.current(owner, MONTH).revision().orElseThrow());
        assertEquals(new SaveResult.Conflict<>(SaveResult.Aggregate.BUDGET), store.save(owner, command(editor, draft)));
    }

    @Test void changedSaveIsOneRevisionAndClearThenSameAmountRemainsInHistory() {
        UUID owner = owner();
        BudgetView first = store.current(owner, MONTH);
        UUID category = first.catalog().catalog().categories().getFirst().id();
        saved(store.save(owner, command(first, first.budget().withIncome(amount("99999999999999999.99"))
                .withSpending(category, amount("99999999999999999.99")).withInvestment(amount("1")))));
        BudgetView populated = store.current(owner, MONTH);
        assertEquals(new BigDecimal("-1.00"), populated.budget().remaining());
        saved(store.save(owner, command(populated, populated.budget().withIncome(BudgetAmount.UNSET)
                .withInvestment(BudgetAmount.UNSET))));
        BudgetView cleared = store.current(owner, MONTH);
        saved(store.save(owner, command(cleared, cleared.budget().withIncome(amount("99999999999999999.99")))));
        UUID budget = populated.budget().id();
        var revisions = store.revisions(owner, budget);
        assertEquals(List.of(3L, 2L, 1L), revisions.stream().map(BudgetRevision::version).toList());
        assertEquals(BudgetAmount.UNSET, store.revision(owner, budget, 2).orElseThrow().budget().income().amount());
        assertEquals(populated.budget().income().amount(), store.revision(owner, budget, 3).orElseThrow().budget().income().amount());
        assertTrue(store.revision(owner, budget, 99).isEmpty());
        assertTrue(store.revision(owner, budget, 0).isEmpty());
        tx.snapshot(s -> {
            assertEquals(List.of("SET", "UNSET", "SET"), s.history(BudgetMappings.STATE, populated.budget().income().id()).stream().map(Fact::value).toList());
            assertEquals(2, s.history(BudgetMappings.AMOUNT, populated.budget().income().id()).size());
            assertEquals(1, s.history(BudgetMappings.STATE, populated.budget().spending(populated.catalog().catalog()).getFirst().line().id()).size());
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void categoryEditsPinOldLabelsAndOrderAndAddLinesOnlyOnExplicitSave(boolean enteredZero) {
        UUID owner = owner();
        BudgetView initial = store.current(owner, MONTH);
        UUID archived = initial.catalog().catalog().categories().getFirst().id();
        BudgetAmount archivedAmount = enteredZero ? amount("0") : BudgetAmount.UNSET;
        saved(store.save(owner, command(initial, initial.budget().withSpending(archived, archivedAmount))));
        BudgetView saved = store.current(owner, MONTH);
        var original = saved.catalog().catalog();
        var ids = new ArrayList<>(original.categories().stream().map(Category::id).toList());
        Collections.reverse(ids);
        CategoryCatalog edited = original.rename(archived, "Renamed").reorder(ids).archive(archived).add("Added");
        UUID added = edited.categories().getLast().id();
        saved(store.saveCatalog(owner, 1, edited));
        assertEquals(1, store.revisions(owner, saved.budget().id()).size());
        BudgetView current = store.current(owner, MONTH);
        assertEquals(13, current.budget().lines().size());
        assertEquals(Category.Status.ARCHIVED, current.budget().spending(edited).stream().filter(v -> v.category().id().equals(archived)).findFirst().orElseThrow().category().status());
        assertEquals(archivedAmount, current.budget().spending(edited).stream().filter(v -> v.category().id().equals(archived)).findFirst().orElseThrow().line().amount());
        assertEquals(12L, scalar("SELECT count(*) FROM budget.line_key WHERE budget_id = ?", Codecs.BIGINT, id(saved.budget().id())));
        var old = store.revision(owner, saved.budget().id(), 1).orElseThrow();
        assertEquals(original, old.catalog().catalog());
        assertEquals(12, old.budget().lines().size());
        assertFalse(old.budget().lines().stream().anyMatch(v -> v.categoryId().filter(added::equals).isPresent()));
        BudgetRevision second = saved(store.save(owner, command(current, current.budget().withSpending(added, amount("42.50")))));
        assertEquals(2, second.catalogVersion());
        BudgetView nextMonth = store.current(owner, MONTH.plusMonths(1));
        assertFalse(nextMonth.budget().lines().stream().anyMatch(v -> v.categoryId().filter(archived::equals).isPresent()));
        saved(store.save(owner, command(nextMonth)));
        BudgetView reload = store.current(owner, MONTH);
        saved(store.save(owner, command(reload, reload.budget().withSpending(archived, amount("3")))));
        assertEquals(new BigDecimal("45.50"), store.current(owner, MONTH).budget().totalSpending());
        tx.snapshot(s -> {
            UUID line = reload.budget().lines().stream().filter(v -> v.categoryId().filter(added::equals).isPresent()).findFirst().orElseThrow().id();
            assertEquals(List.of(new Fact<>(second.changedAt(), "SET")), s.history(BudgetMappings.STATE, line));
            return null;
        });
    }

    @Test void catalogOnlyEditsDoNotMakeBudgetNoopARevisionAndStaleCatalogStillConflicts() {
        UUID owner = owner();
        saved(store.save(owner, command(store.current(owner, MONTH))));
        BudgetView old = store.current(owner, MONTH);
        CategoryCatalog catalog = old.catalog().catalog();
        saved(store.saveCatalog(owner, 1, catalog.rename(catalog.categories().getFirst().id(), "Updated")));
        assertEquals(new SaveResult.Conflict<>(SaveResult.Aggregate.CATALOG), store.save(owner, command(old)));
        var current = store.current(owner, MONTH);
        assertEquals(2, current.catalog().revision().version());
        assertEquals(new SaveResult.Unchanged<>(old.revision().orElseThrow()), store.save(owner, command(current)));
        assertEquals(1, store.revisions(owner, current.budget().id()).size());
        assertEquals(catalog, store.revision(owner, current.budget().id(), 1).orElseThrow().catalog().catalog());
        var counts = counts();
        assertInstanceOf(SaveResult.Unchanged.class, store.saveCatalog(owner, 2, current.catalog().catalog()));
        assertEquals(new SaveResult.Conflict<>(SaveResult.Aggregate.CATALOG), store.saveCatalog(owner, 1, current.catalog().catalog()));
        assertEquals(counts, counts());
    }

    @Test void invalidShapesAndForeignOwnersNeverChangeFacts() {
        UUID owner = owner();
        UUID other = owner();
        BudgetView editor = store.current(owner, MONTH);
        SaveBudget valid = command(editor);
        List<SpendingValue> missing = valid.spending().subList(1, valid.spending().size());
        List<SpendingValue> duplicate = new ArrayList<>(valid.spending());
        duplicate.add(valid.spending().getFirst());
        List<SpendingValue> foreign = new ArrayList<>(valid.spending());
        foreign.set(0, new SpendingValue(store.catalog(other).orElseThrow().catalog().categories().getFirst().id(), BudgetAmount.UNSET));
        var before = counts();
        for (List<SpendingValue> values : List.of(missing, duplicate, foreign)) {
            assertInstanceOf(SaveResult.Invalid.class, store.save(owner, new SaveBudget(MONTH, 0, 1, valid.income(), values, valid.investment())));
        }
        assertInstanceOf(SaveResult.Invalid.class, store.save(owner, new SaveBudget(MONTH, 0, 1, null, valid.spending(), valid.investment())));
        assertInstanceOf(SaveResult.Invalid.class, store.save(owner, new SaveBudget(MONTH, 0, 1, valid.income(), valid.spending(), null)));
        assertInstanceOf(SaveResult.Invalid.class, store.save(owner, new SaveBudget(YearMonth.of(999999999, 1), 0, 1, valid.income(), valid.spending(), valid.investment())));
        assertInstanceOf(SaveResult.Invalid.class, store.saveCatalog(owner, 1, store.catalog(other).orElseThrow().catalog()));
        assertEquals(before, counts());
        BudgetRevision revision = saved(store.save(owner, valid));
        assertTrue(store.revisions(other, revision.budgetId()).isEmpty());
        assertTrue(store.revision(other, revision.budgetId(), 1).isEmpty());
        assertEquals(0, store.current(other, MONTH).version());
        assertInstanceOf(SaveResult.Invalid.class, store.save(other, valid));
        assertEquals(1, store.revisions(owner, revision.budgetId()).size());
    }

    @Test void catalogCannotDeleteRestoreOrReuseAnotherOwnersCategoryIdentity() {
        UUID owner = owner();
        UUID other = owner();
        CatalogView initial = store.catalog(owner).orElseThrow();
        var archived = initial.catalog();
        for (Category category : archived.categories()) archived = archived.archive(category.id());
        saved(store.saveCatalog(owner, 1, archived));
        assertInstanceOf(SaveResult.Invalid.class, store.saveCatalog(owner, 2, initial.catalog()));
        assertInstanceOf(SaveResult.Invalid.class, store.saveCatalog(owner, 2, new CategoryCatalog(owner, List.of())));
        var categories = new ArrayList<>(archived.categories());
        categories.add(new Category(store.catalog(other).orElseThrow().catalog().categories().getFirst().id(), owner, "Foreign", 11, Category.Status.ACTIVE));
        assertInstanceOf(SaveResult.Invalid.class, store.saveCatalog(owner, 2, new CategoryCatalog(owner, categories)));
        var emptyMonth = store.current(owner, MONTH);
        assertEquals(2, emptyMonth.budget().lines().size());
        var extra = new SaveBudget(MONTH, 0, 2, BudgetAmount.UNSET,
                List.of(new SpendingValue(archived.categories().getFirst().id(), BudgetAmount.UNSET)), BudgetAmount.UNSET);
        assertInstanceOf(SaveResult.Invalid.class, store.save(owner, extra));
        saved(store.save(owner, command(emptyMonth)));
    }

    @Test void partialBudgetWriteRollsBackFactsGuardAndRevision() {
        UUID owner = owner();
        saved(store.save(owner, command(store.current(owner, MONTH))));
        BudgetView current = store.current(owner, MONTH);
        var before = counts();
        var failures = new ArrayList<Throwable>();
        var failing = new PostgresBudgetStore(hooked((method, args, call) -> {
            Object result = call.run();
            if (sql(args).startsWith("INSERT INTO budget.budget_revision")) throw new IllegalStateException("Injected after revision write");
            return result;
        }), failures::add);
        assertInstanceOf(SaveResult.Failed.class, failing.save(owner, command(current, current.budget().withIncome(amount("5")).withInvestment(amount("1")))));
        assertEquals(before, counts());
        assertEquals(current.revision(), store.current(owner, MONTH).revision());
        assertEquals(BudgetAmount.UNSET, store.current(owner, MONTH).budget().income().amount());
        assertEquals(1, failures.size());
    }

    @ParameterizedTest @ValueSource(strings = {"missingAmount", "missingState", "mismatchedTime", "unsetWithAmount"})
    void realDeferredPairingFailureRollsBackWholeSave(String fault) {
        UUID owner = owner();
        saved(store.save(owner, command(store.current(owner, MONTH))));
        BudgetView current = store.current(owner, MONTH);
        var before = counts();
        var failures = new ArrayList<Throwable>();
        var broken = new PostgresBudgetStore(hooked((method, args, call) -> {
            if (method.getName().equals("append")) {
                if (args[0] == BudgetMappings.AMOUNT && fault.equals("missingAmount")) return null;
                if (args[0] == BudgetMappings.STATE && fault.equals("missingState")) return null;
                if (args[0] == BudgetMappings.AMOUNT && fault.equals("mismatchedTime")) args[2] = ((Instant) args[2]).plusNanos(1000);
                if (args[0] == BudgetMappings.STATE && fault.equals("unsetWithAmount")) args[3] = "UNSET";
            }
            return call.run();
        }), failures::add);
        assertInstanceOf(SaveResult.Failed.class, broken.save(owner, command(current, current.budget().withIncome(amount("12.34")))));
        assertEquals("23503", assertInstanceOf(PersistenceFailure.class, failures.getFirst()).sqlState().orElseThrow());
        assertEquals(before, counts());
        assertEquals(current.revision(), store.current(owner, MONTH).revision());
    }

    @Test void partialCatalogCommandRollsBackFactsGuardAndRevision() {
        UUID owner = owner();
        CatalogView current = store.catalog(owner).orElseThrow();
        UUID category = current.catalog().categories().getFirst().id();
        CategoryCatalog draft = current.catalog().rename(category, "Changed").archive(category).add("New");
        var before = counts();
        var failures = new ArrayList<Throwable>();
        var failing = new PostgresBudgetStore(hooked((method, args, call) -> {
            Object result = call.run();
            if (sql(args).startsWith("INSERT INTO budget.catalog_revision")) throw new IllegalStateException("Injected after revision write");
            return result;
        }), failures::add);
        assertInstanceOf(SaveResult.Failed.class, failing.saveCatalog(owner, 1, draft));
        assertEquals(before, counts());
        assertEquals(current, store.catalog(owner).orElseThrow());
        assertEquals(1, failures.size());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void competingFirstSavesAndStaleTabsHaveOneWinner(boolean existing) throws Exception {
        UUID owner = owner();
        if (existing) saved(store.save(owner, command(store.current(owner, MONTH))));
        BudgetView current = store.current(owner, MONTH);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var concurrent = new PostgresBudgetStore(hooked((method, args, call) -> {
            boolean lock = sql(args).contains("FROM budget.budget_guard") && sql(args).contains("FOR UPDATE");
            if (lock && existing) barrier.await(5, TimeUnit.SECONDS);
            Object result = call.run();
            if (lock && !existing) barrier.await(5, TimeUnit.SECONDS); // Both saw absence before either claims creation.
            return result;
        }), failure -> fail(failure));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> concurrent.save(owner, command(current, current.budget().withIncome(amount("10")))));
            var b = executor.submit(() -> concurrent.save(owner, command(current, current.budget().withIncome(amount("20")))));
            var outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(SaveResult.Saved.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(new SaveResult.Conflict<>(SaveResult.Aggregate.BUDGET)::equals).count());
        }
        BudgetView winner = store.current(owner, MONTH);
        assertEquals(existing ? 2 : 1, winner.version());
        assertEquals(existing ? 2 : 1, store.revisions(owner, winner.budget().id()).size());
        assertEquals(1L, scalar("SELECT count(*) FROM budget.budget_guard WHERE owner_id = ?", Codecs.BIGINT, id(owner)));
        assertEquals(0L, scalar("SELECT count(*) FROM budget.budget b LEFT JOIN budget.budget_guard g USING (budget_id) WHERE g.budget_id IS NULL", Codecs.BIGINT));
        assertTrue(Set.of(amount("10"), amount("20")).contains(winner.budget().income().amount()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void catalogAndBudgetRacesHoldCatalogLockUntilCompletion(boolean catalogFirst) throws Exception {
        UUID owner = owner();
        BudgetView draft = store.current(owner, MONTH);
        CategoryCatalog changed = draft.catalog().catalog().rename(draft.catalog().catalog().categories().getFirst().id(), "Raced name");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String lockMode = catalogFirst ? "FOR UPDATE" : "FOR SHARE";
        var holding = new PostgresBudgetStore(hooked((method, args, call) -> {
            Object result = call.run();
            if (sql(args).contains("FROM budget.catalog_guard") && sql(args).contains(lockMode)) {
                locked.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return result;
        }), failure -> fail(failure));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<? extends SaveResult<?>> first = catalogFirst
                    ? executor.submit(() -> holding.saveCatalog(owner, 1, changed))
                    : executor.submit(() -> holding.save(owner, command(draft)));
            Future<? extends SaveResult<?>> second;
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                second = catalogFirst ? executor.submit(() -> store.save(owner, command(draft)))
                        : executor.submit(() -> store.saveCatalog(owner, 1, changed));
                awaitCatalogLock();
                assertFalse(second.isDone());
            } finally { release.countDown(); }
            assertInstanceOf(SaveResult.Saved.class, first.get(10, TimeUnit.SECONDS));
            if (catalogFirst) {
                assertEquals(new SaveResult.Conflict<>(SaveResult.Aggregate.CATALOG), second.get(10, TimeUnit.SECONDS));
                assertEquals(0, store.current(owner, MONTH).version());
            } else {
                assertInstanceOf(SaveResult.Saved.class, second.get(10, TimeUnit.SECONDS));
                var current = store.current(owner, MONTH);
                assertEquals(1, current.version());
                assertEquals(1, current.revision().orElseThrow().catalogVersion());
                assertEquals(draft.catalog().catalog(), store.revision(owner, current.budget().id(), 1).orElseThrow().catalog().catalog());
            }
        }
    }

    @Test void multiQueryCurrentReadUsesOneSnapshotWithoutBlockingWriters() throws Exception {
        UUID owner = owner();
        saved(store.save(owner, command(store.current(owner, MONTH))));
        BudgetView original = store.current(owner, MONTH);
        CountDownLatch snapshot = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        var reading = new PostgresBudgetStore(hooked((method, args, call) -> {
            queries.incrementAndGet();
            Object result = call.run();
            if (sql(args).contains("FROM budget.catalog_guard")) {
                snapshot.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return result;
        }), failure -> fail(failure));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> reading.current(owner, MONTH));
            try {
                assertTrue(snapshot.await(5, TimeUnit.SECONDS));
                CategoryCatalog renamed = original.catalog().catalog().rename(original.catalog().catalog().categories().getFirst().id(), "After snapshot");
                saved(store.saveCatalog(owner, 1, renamed));
                BudgetView newest = store.current(owner, MONTH);
                saved(store.save(owner, command(newest, newest.budget().withIncome(amount("500")))));
            } finally { release.countDown(); }
            BudgetView observed = pending.get(10, TimeUnit.SECONDS);
            assertEquals(original.revision(), observed.revision());
            assertEquals(original.catalog(), observed.catalog());
            assertEquals(BudgetAmount.UNSET, observed.budget().income().amount());
            assertEquals(60, queries.get()); // Explicit mapper primitives, excluding ORM transaction setup/cleanup.
        }
        assertEquals(amount("500"), store.current(owner, MONTH).budget().income().amount());
    }

    @Test void databaseClockCoordinatesAreSharedAndMonotonicWithEqualAndBackwardReadings() {
        UUID owner = UUID.randomUUID();
        Instant future = Instant.parse("2100-01-01T00:00:00.123456Z");
        var futureClock = clockStore(future);
        CatalogRevision cat1 = saved(futureClock.initializeCatalog(owner));
        assertEquals(future, cat1.changedAt());
        BudgetRevision first = saved(futureClock.save(owner, command(store.current(owner, MONTH))));
        assertEquals(future, first.changedAt());
        var current = store.current(owner, MONTH);
        BudgetRevision second = saved(futureClock.save(owner, command(current, current.budget().withIncome(amount("1")))));
        assertEquals(future.plusNanos(1000), second.changedAt());
        var backward = clockStore(Instant.parse("2000-01-01T00:00:00Z"));
        current = store.current(owner, MONTH);
        BudgetRevision third = saved(backward.save(owner, command(current, current.budget().withIncome(amount("2")).withInvestment(amount("1")))));
        assertEquals(second.changedAt().plusNanos(1000), third.changedAt());
        var catalog = store.catalog(owner).orElseThrow();
        UUID category = catalog.catalog().categories().getFirst().id();
        CatalogRevision cat2 = saved(backward.saveCatalog(owner, 1, catalog.catalog().rename(category, "Backwards clock")));
        assertEquals(future.plusNanos(1000), cat2.changedAt());
        BudgetView saved = store.current(owner, MONTH);
        tx.snapshot(s -> {
            for (UUID line : List.of(saved.budget().income().id(), saved.budget().investment().id())) {
                assertEquals(third.changedAt(), s.current(BudgetMappings.STATE, line).orElseThrow().changedAt());
                assertEquals(third.changedAt(), s.current(BudgetMappings.AMOUNT, line).orElseThrow().changedAt());
            }
            return null;
        });
        assertEquals(third.changedAt(), scalar("SELECT last_changed_at FROM budget.budget_guard WHERE budget_id = ?", Codecs.INSTANT, id(first.budgetId())));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostCommitAcknowledgementIsUnknownAndNeverRetried(boolean commitReachedDatabase) {
        UUID owner = owner();
        BudgetView current = store.current(owner, MONTH);
        var failures = new ArrayList<Throwable>();
        var faultPool = new FaultDataSource(pool, commitReachedDatabase ? "afterCommit" : "beforeCommit");
        var faulty = new PostgresBudgetStore(new JdbcTransactions(faultPool), failures::add);
        assertInstanceOf(SaveResult.OutcomeUnknown.class, faulty.save(owner, command(current, current.budget().withIncome(amount("50")))));
        assertEquals(1, faultPool.commits.get());
        assertEquals(1, failures.size());
        assertEquals(commitReachedDatabase ? 1 : 0, store.current(owner, MONTH).version());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void catalogCommandsAlsoReportUnknownWithoutRetries(boolean commitReachedDatabase) {
        UUID owner = owner();
        CatalogView current = store.catalog(owner).orElseThrow();
        var failures = new ArrayList<Throwable>();
        var faultPool = new FaultDataSource(pool, commitReachedDatabase ? "afterCommit" : "beforeCommit");
        var faulty = new PostgresBudgetStore(new JdbcTransactions(faultPool), failures::add);
        assertInstanceOf(SaveResult.OutcomeUnknown.class, faulty.saveCatalog(owner, 1, current.catalog().add("Unconfirmed")));
        assertEquals(1, faultPool.commits.get());
        assertEquals(1, failures.size());
        assertEquals(commitReachedDatabase ? 2 : 1, store.catalog(owner).orElseThrow().revision().version());
    }

    @Test void confirmedCommitWithCleanupFailureStaysSavedEvenIfDiagnosticsSinkThrows() {
        UUID owner = owner();
        BudgetView current = store.current(owner, MONTH);
        var faultPool = new FaultDataSource(pool, "release");
        AtomicInteger diagnostics = new AtomicInteger();
        var faulty = new PostgresBudgetStore(new JdbcTransactions(faultPool), failure -> {
            diagnostics.incrementAndGet(); throw new IllegalStateException("Injected sink failure");
        });
        BudgetRevision revision = saved(faulty.save(owner, command(current)));
        assertEquals(1, faultPool.commits.get());
        assertEquals(1, diagnostics.get());
        assertEquals(revision, store.current(owner, MONTH).revision().orElseThrow());
    }

    static void awaitCatalogLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long waiting = scalar("""
                    SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%FROM budget.catalog_guard%'
                    """, Codecs.BIGINT);
            if (waiting > 0) return;
            Thread.sleep(10);
        }
        fail("The competing command never waited on the catalog lock");
    }

    static PostgresBudgetStore clockStore(Instant clock) {
        return new PostgresBudgetStore(hooked((method, args, call) -> {
            String sql = sql(args);
            if (sql.startsWith("SELECT") && sql.contains("clock_timestamp()")) {
                args[0] = new Sql(sql.replace("clock_timestamp()", "TIMESTAMPTZ '" + clock + "'"));
            }
            return call.run();
        }), failure -> fail(failure));
    }

    /** Faults wrap real pooled JDBC connections; no application outcome is mocked. */
    static final class FaultDataSource extends HikariDataSource {
        private final HikariDataSource delegate;
        private final String fault;
        private final Map<Connection, Connection> originals = new IdentityHashMap<>();
        final AtomicInteger commits = new AtomicInteger();
        private boolean releaseFailed;
        FaultDataSource(HikariDataSource delegate, String fault) { this.delegate = delegate; this.fault = fault; }
        @Override public Connection getConnection() throws SQLException {
            Connection original = delegate.getConnection();
            Connection wrapped = proxy(Connection.class, original, (method, args, call) -> {
                if (method.getName().equals("commit")) {
                    commits.incrementAndGet();
                    if (fault.equals("beforeCommit")) throw new SQLException("Injected lost commit response", "08006");
                    Object result = call.run();
                    if (fault.equals("afterCommit")) throw new SQLException("Injected lost commit response", "08006");
                    return result;
                }
                Object result = call.run();
                if (method.getName().equals("close") && fault.equals("release") && !releaseFailed) {
                    releaseFailed = true;
                    throw new SQLException("Injected release failure");
                }
                return result;
            });
            originals.put(wrapped, original);
            return wrapped;
        }
        @Override public void evictConnection(Connection connection) { delegate.evictConnection(originals.remove(connection)); }
    }

    static UUID owner() {
        UUID owner = UUID.randomUUID();
        saved(store.initializeCatalog(owner));
        return owner;
    }
    static BudgetAmount amount(String value) { return BudgetAmount.set(new BigDecimal(value)); }
    static SaveBudget command(BudgetView view) { return command(view, view.budget()); }
    static SaveBudget command(BudgetView view, Budget draft) {
        return new SaveBudget(draft.month(), view.version(), view.catalog().revision().version(), draft.income().amount(),
                draft.lines().stream().filter(line -> line.kind() == BudgetLine.Kind.SPENDING)
                        .map(line -> new SpendingValue(line.categoryId().orElseThrow(), line.amount())).toList(), draft.investment().amount());
    }
    static <T> T saved(SaveResult<T> result) {
        assertInstanceOf(SaveResult.Saved.class, result);
        return ((SaveResult.Saved<T>) result).revision();
    }
    static Param<UUID> id(UUID value) { return Param.of(Codecs.UUID, value); }
    static <T> T scalar(String sql, Codec<T> codec, Param<?>... params) {
        return tx.snapshot(s -> s.query(new Sql(sql), List.of(params), r -> r.get(1, codec).orElseThrow()).getFirst()).value();
    }
    static Map<String, Long> counts() {
        return tx.snapshot(s -> {
            var tables = s.query(new Sql("SELECT tablename::text FROM pg_tables WHERE schemaname = 'budget' ORDER BY tablename"),
                    List.of(), r -> r.get(1, Codecs.TEXT).orElseThrow());
            var counts = new LinkedHashMap<String, Long>();
            for (String table : tables) counts.put(table, s.query(new Sql("SELECT count(*) FROM budget." + new Identifier(table).value()),
                    List.of(), r -> r.get(1, Codecs.BIGINT).orElseThrow()).getFirst());
            return counts;
        }).value();
    }

    @FunctionalInterface interface Call { Object run() throws Throwable; }
    @FunctionalInterface interface Hook { Object invoke(Method method, Object[] args, Call call) throws Throwable; }
    static String sql(Object[] args) { return args != null && args.length > 0 && args[0] instanceof Sql sql ? sql.text().strip() : ""; }
    static Transactions hooked(Hook hook) {
        return new Transactions() {
            public <R> Completed<R> write(Work<WriteSession, R> work) { return tx.write(s -> work.run(proxy(WriteSession.class, s, hook))); }
            public <R> Completed<R> snapshot(Work<ReadSession, R> work) { return tx.snapshot(s -> work.run(proxy(ReadSession.class, s, hook))); }
        };
    }
    static <S> S proxy(Class<S> type, S delegate, Hook hook) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                hook.invoke(method, args, () -> {
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                })));
    }
}
