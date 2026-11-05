package org.financelab.budget.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A monthly value snapshot. All edits return a new snapshot; this class performs no persistence. */
public final class Budget {
    private final UUID id;
    private final UUID ownerId;
    private final YearMonth month;
    private final BudgetCurrency currency;
    private final List<BudgetLine> lines;

    private Budget(UUID id, UUID ownerId, YearMonth month, BudgetCurrency currency, List<BudgetLine> lines) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.month = Objects.requireNonNull(month, "month");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.lines = List.copyOf(lines);
        var ids = new HashSet<UUID>();
        var categories = new HashSet<UUID>();
        for (BudgetLine line : this.lines) {
            if (!ids.add(line.id()) || (line.categoryId().isPresent() && !categories.add(line.categoryId().get()))) {
                throw new IllegalArgumentException("Duplicate line or spending category");
            }
        }
        for (BudgetLine.Kind kind : List.of(BudgetLine.Kind.INCOME, BudgetLine.Kind.INVESTMENT)) {
            if (this.lines.stream().filter(line -> line.kind() == kind).count() != 1) {
                throw new IllegalArgumentException("A budget requires exactly one income and one investment line");
            }
        }
    }

    public static Budget create(UUID ownerId, YearMonth month, CategoryCatalog catalog) {
        catalog.requireOwner(ownerId);
        var lines = new ArrayList<BudgetLine>();
        lines.add(BudgetLine.create(BudgetLine.Kind.INCOME, Optional.empty()));
        lines.add(BudgetLine.create(BudgetLine.Kind.INVESTMENT, Optional.empty()));
        return new Budget(UUID.randomUUID(), ownerId, month, BudgetCurrency.RUB, lines).withCurrentCatalog(catalog);
    }

    /** Reconstitute exact membership, including archived/UNSET lines; do not add today's categories to history. */
    public static Budget restore(UUID id, UUID ownerId, YearMonth month, BudgetCurrency currency,
                                 List<BudgetLine> lines, CategoryCatalog catalog) {
        Budget budget = new Budget(id, ownerId, month, currency, lines);
        budget.validateCatalog(catalog);
        return budget;
    }

    private void validateCatalog(CategoryCatalog catalog) {
        catalog.requireOwner(ownerId);
        lines.forEach(line -> line.categoryId().ifPresent(catalog::category));
    }

    /** Prepare a current draft: add active missing categories as UNSET, retaining all existing lines. */
    public Budget withCurrentCatalog(CategoryCatalog catalog) {
        validateCatalog(catalog);
        var updated = new ArrayList<>(lines);
        var represented = new HashSet<UUID>();
        lines.forEach(line -> line.categoryId().ifPresent(represented::add));
        for (Category category : catalog.categories()) {
            if (category.status() == Category.Status.ACTIVE && !represented.contains(category.id())) {
                updated.add(BudgetLine.create(BudgetLine.Kind.SPENDING, Optional.of(category.id())));
            }
        }
        return new Budget(id, ownerId, month, currency, updated);
    }

    public Budget withIncome(BudgetAmount amount) {
        return withAmount(income().id(), amount);
    }

    public Budget withInvestment(BudgetAmount amount) {
        return withAmount(investment().id(), amount);
    }

    public Budget withSpending(UUID categoryId, BudgetAmount amount) {
        BudgetLine line = lines.stream().filter(item -> item.categoryId().filter(categoryId::equals).isPresent())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Category is not represented in this budget"));
        return withAmount(line.id(), amount);
    }

    private Budget withAmount(UUID lineId, BudgetAmount amount) {
        return new Budget(id, ownerId, month, currency,
                lines.stream().map(line -> line.id().equals(lineId) ? line.withAmount(amount) : line).toList());
    }

    public record SpendingLine(Category category, BudgetLine line) { }

    public List<SpendingLine> spending(CategoryCatalog catalog) {
        validateCatalog(catalog);
        return lines.stream().filter(line -> line.kind() == BudgetLine.Kind.SPENDING)
                .map(line -> new SpendingLine(catalog.category(line.categoryId().orElseThrow()), line))
                .sorted(Comparator.comparingInt(entry -> entry.category().position())).toList();
    }

    public BigDecimal totalSpending() {
        return lines.stream().filter(line -> line.kind() == BudgetLine.Kind.SPENDING)
                .map(line -> line.amount().contribution()).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    public BigDecimal remaining() {
        return income().amount().contribution().subtract(totalSpending()).subtract(investment().amount().contribution());
    }

    public BudgetLine income() { return line(BudgetLine.Kind.INCOME); }
    public BudgetLine investment() { return line(BudgetLine.Kind.INVESTMENT); }
    private BudgetLine line(BudgetLine.Kind kind) {
        return lines.stream().filter(line -> line.kind() == kind).findFirst().orElseThrow();
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public YearMonth month() { return month; }
    public BudgetCurrency currency() { return currency; }
    public List<BudgetLine> lines() { return lines; }
}
