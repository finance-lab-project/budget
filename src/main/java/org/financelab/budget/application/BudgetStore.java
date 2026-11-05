package org.financelab.budget.application;

import org.financelab.budget.domain.Budget;
import org.financelab.budget.domain.BudgetAmount;
import org.financelab.budget.domain.CategoryCatalog;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The owner argument must come from authenticated server context, never the submitted draft. */
public interface BudgetStore {
    record SpendingValue(UUID categoryId, BudgetAmount amount) { }
    record SaveBudget(YearMonth month, long expectedVersion, long expectedCatalogVersion,
                      BudgetAmount income, List<SpendingValue> spending, BudgetAmount investment) {
        public SaveBudget {
            // Keep malformed/missing fields representable so save can return Invalid.
            if (spending != null) spending = Collections.unmodifiableList(new ArrayList<>(spending));
        }
    }
    record CatalogRevision(long version, Instant changedAt) { }
    record BudgetRevision(UUID budgetId, long version, Instant changedAt, long catalogVersion) { }
    record CatalogView(CategoryCatalog catalog, CatalogRevision revision) { }
    /** Current views use today's catalog; historical views use the revision's pinned catalog. */
    record BudgetView(Budget budget, CatalogView catalog, Optional<BudgetRevision> revision) {
        public long version() { return revision.map(BudgetRevision::version).orElse(0L); }
    }

    SaveResult<CatalogRevision> initializeCatalog(UUID owner);
    SaveResult<CatalogRevision> saveCatalog(UUID owner, long expectedVersion, CategoryCatalog draft);
    SaveResult<BudgetRevision> save(UUID owner, SaveBudget command);
    Optional<CatalogView> catalog(UUID owner);
    BudgetView current(UUID owner, YearMonth month);
    List<BudgetRevision> revisions(UUID owner, UUID budgetId);
    Optional<BudgetView> revision(UUID owner, UUID budgetId, long version);
}
