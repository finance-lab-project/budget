package org.financelab.budget.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Identity, kind and category stay fixed when the amount changes. */
public record BudgetLine(UUID id, Kind kind, Optional<UUID> categoryId, BudgetAmount amount) {
    public enum Kind { INCOME, SPENDING, INVESTMENT }

    public BudgetLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(amount, "amount");
        if ((kind == Kind.SPENDING) != categoryId.isPresent()) {
            throw new IllegalArgumentException("Only spending lines require a category");
        }
    }

    public static BudgetLine create(Kind kind, Optional<UUID> categoryId) {
        return new BudgetLine(UUID.randomUUID(), kind, categoryId, BudgetAmount.UNSET);
    }

    public BudgetLine withAmount(BudgetAmount amount) {
        return new BudgetLine(id, kind, categoryId, amount);
    }
}
