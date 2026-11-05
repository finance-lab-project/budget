package org.financelab.budget.application;

import org.financelab.budget.domain.CategoryCatalog;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Per authenticated UI/session. The root supplies identity from its trusted server context. */
public final class BudgetService {
    private final BudgetStore store;
    private final Supplier<UUID> authenticatedOwner;
    private final UUID owner;

    public BudgetService(BudgetStore store, Supplier<UUID> authenticatedOwner) {
        this.store = Objects.requireNonNull(store);
        this.authenticatedOwner = Objects.requireNonNull(authenticatedOwner);
        this.owner = requireIdentity();
    }

    private UUID requireIdentity() {
        UUID current = authenticatedOwner.get();
        if (current == null) throw new SecurityException("Authentication required");
        return current;
    }

    private UUID owner() {
        if (!owner.equals(requireIdentity())) throw new SecurityException("Session identity changed");
        return owner;
    }

    public BudgetStore.BudgetView current(YearMonth month) { return store.current(owner(), month); }
    public BudgetStore.CatalogView catalog() { return store.catalog(owner()).orElseThrow(); }
    public List<BudgetStore.BudgetRevision> revisions(UUID budgetId) { return store.revisions(owner(), budgetId); }
    public Optional<BudgetStore.BudgetView> revision(UUID budgetId, long version) {
        return store.revision(owner(), budgetId, version);
    }
    public SaveResult<BudgetStore.BudgetRevision> save(BudgetStore.SaveBudget command) {
        return store.save(owner(), command);
    }
    public SaveResult<BudgetStore.CatalogRevision> saveCatalog(long expectedVersion, CategoryCatalog draft) {
        return store.saveCatalog(owner(), expectedVersion, draft);
    }
}
