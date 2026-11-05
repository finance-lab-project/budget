package org.financelab.budget.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BudgetTest {
    private final UUID owner = UUID.randomUUID();
    private final CategoryCatalog catalog = CategoryCatalog.initialize(owner, Optional.empty());
    private final Budget budget = Budget.create(owner, YearMonth.of(2026, 11), catalog);

    private BudgetAmount amount(String amount) { return BudgetAmount.set(new BigDecimal(amount)); }

    @Test void createsOneIncomeAndInvestmentWithVariableSpendingAndExplicitRub() {
        assertEquals(owner, budget.ownerId());
        assertEquals(YearMonth.of(2026, 11), budget.month());
        assertEquals(BudgetCurrency.RUB, budget.currency());
        assertEquals(4, budget.id().version());
        assertEquals(12, budget.lines().size());
        assertEquals(12, budget.lines().stream().map(BudgetLine::id).distinct().count());
        assertEquals(BudgetLine.Kind.INCOME, budget.income().kind());
        assertEquals(BudgetLine.Kind.INVESTMENT, budget.investment().kind());
        assertTrue(budget.income().categoryId().isEmpty());
        assertTrue(budget.investment().categoryId().isEmpty());
        assertTrue(budget.lines().stream().allMatch(line -> line.amount().state() == BudgetAmount.State.UNSET && line.id().version() == 4));
        assertEquals(new BigDecimal("0.00"), budget.remaining());
    }

    @Test void exactTotalsSeparateInvestmentsAndAllowNegativeRemainingAndOversizedTotals() {
        var first = catalog.categories().getFirst().id();
        var second = catalog.categories().get(1).id();
        var changed = budget.withIncome(amount("0.30")).withSpending(first, amount("0.10"))
                .withSpending(second, amount("0.20")).withInvestment(amount("0.01"));
        assertEquals(new BigDecimal("0.30"), changed.totalSpending());
        assertEquals(new BigDecimal("-0.01"), changed.remaining());
        var maximum = BudgetAmount.set(BudgetAmount.MAX_VALUE);
        var large = budget.withIncome(maximum).withSpending(first, maximum).withSpending(second, maximum).withInvestment(maximum);
        assertEquals(new BigDecimal("199999999999999999.98"), large.totalSpending());
        assertEquals(new BigDecimal("-199999999999999999.98"), large.remaining());
        assertEquals(BudgetAmount.UNSET, budget.income().amount());
        assertEquals(budget.id(), changed.id());
        assertEquals(budget.income().id(), changed.income().id());
    }

    @Test void amountsCanBeSetToZeroClearedAndSetAgainWithoutChangingLineIdentity() {
        UUID id = catalog.categories().getFirst().id();
        var set = budget.withSpending(id, amount("7.50"));
        var zero = set.withSpending(id, amount("0"));
        var cleared = zero.withSpending(id, BudgetAmount.UNSET);
        var reset = cleared.withSpending(id, amount("7.50"));
        assertEquals(BudgetAmount.State.SET, zero.spending(catalog).getFirst().line().amount().state());
        assertEquals(BudgetAmount.State.UNSET, cleared.spending(catalog).getFirst().line().amount().state());
        assertEquals(set.spending(catalog).getFirst().line(), reset.spending(catalog).getFirst().line());
        assertEquals(new BigDecimal("7.50"), set.totalSpending());
    }

    @Test void catalogChangesApplyAcrossMonthsWithoutMutatingSnapshotsOrAmounts() {
        UUID id = catalog.categories().getFirst().id();
        var november = budget.withSpending(id, amount("12.50"));
        var december = Budget.create(owner, YearMonth.of(2026, 12), catalog).withSpending(id, amount("3.25"));
        var ids = new ArrayList<>(catalog.categories().stream().map(Category::id).toList());
        Collections.reverse(ids);
        var edited = catalog.rename(id, "Новое название").reorder(ids).add("Новая категория");
        UUID addedId = edited.categories().getLast().id();
        for (var monthly : List.of(november, december)) {
            var draft = monthly.withCurrentCatalog(edited);
            assertEquals(11, draft.spending(edited).size());
            assertEquals(addedId, draft.spending(edited).getLast().category().id());
            assertEquals(BudgetAmount.UNSET, draft.spending(edited).getLast().line().amount());
            assertEquals(monthly.totalSpending(), draft.totalSpending());
            assertEquals("Новое название", draft.spending(edited).get(9).category().label());
            assertEquals(monthly.lines(), draft.lines().subList(0, 12));
            assertEquals(draft.lines(), draft.withCurrentCatalog(edited).lines());
        }
        assertEquals(10, november.spending(catalog).size());
        assertEquals("Жильё и коммунальные услуги", november.spending(catalog).getFirst().category().label());
        assertEquals(new BigDecimal("12.50"), november.totalSpending());
        assertEquals(new BigDecimal("3.25"), december.totalSpending());
        var restored = Budget.restore(november.id(), owner, november.month(), BudgetCurrency.RUB, november.lines(), edited);
        assertEquals(10, restored.spending(edited).size());
    }

    @Test void archivedExistingLinesIncludingZeroAndUnsetStayVisibleEditableAndInTotals() {
        UUID populated = catalog.categories().getFirst().id();
        UUID zero = catalog.categories().get(1).id();
        UUID unset = catalog.categories().get(2).id();
        var saved = budget.withSpending(populated, amount("25")).withSpending(zero, amount("0"));
        var archived = catalog.archive(populated).archive(zero).archive(unset);
        var current = saved.withCurrentCatalog(archived);
        assertEquals(saved.lines(), current.lines());
        assertEquals(new BigDecimal("25.00"), current.totalSpending());
        assertEquals(10, current.spending(archived).size());
        assertEquals(Category.Status.ARCHIVED, current.spending(archived).getFirst().category().status());
        assertEquals(BudgetAmount.State.SET, current.spending(archived).get(1).line().amount().state());
        assertEquals(BudgetAmount.State.UNSET, current.spending(archived).get(2).line().amount().state());
        assertEquals(new BigDecimal("31.00"), current.withSpending(populated, amount("30")).withSpending(unset, amount("1")).totalSpending());
        var nextMonth = Budget.create(owner, YearMonth.of(2026, 12), archived);
        assertEquals(7, nextMonth.spending(archived).size());
        assertThrows(IllegalArgumentException.class, () -> nextMonth.withSpending(populated, amount("1")));
        assertEquals(7, nextMonth.withCurrentCatalog(archived).spending(archived).size());
    }

    @Test void rejectsMissingCategoryForeignOwnerAndInvalidLineMembership() {
        assertThrows(IllegalArgumentException.class, () -> Budget.create(UUID.randomUUID(), budget.month(), catalog));
        assertThrows(IllegalArgumentException.class, () -> budget.withCurrentCatalog(new CategoryCatalog(owner, List.of())));
        assertThrows(IllegalArgumentException.class, () -> budget.withSpending(UUID.randomUUID(), amount("1")));
        var lines = new ArrayList<>(budget.lines());
        lines.add(budget.income());
        assertThrows(IllegalArgumentException.class, () -> restore(lines));
        lines.removeLast();
        lines.remove(budget.income());
        assertThrows(IllegalArgumentException.class, () -> restore(lines));
        lines.add(BudgetLine.create(BudgetLine.Kind.INVESTMENT, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> restore(lines));
        var duplicateCategory = new ArrayList<>(budget.lines());
        duplicateCategory.add(BudgetLine.create(BudgetLine.Kind.SPENDING, Optional.of(catalog.categories().getFirst().id())));
        assertThrows(IllegalArgumentException.class, () -> restore(duplicateCategory));
        assertThrows(IllegalArgumentException.class, () -> BudgetLine.create(BudgetLine.Kind.INCOME, Optional.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> BudgetLine.create(BudgetLine.Kind.SPENDING, Optional.empty()));
        assertThrows(UnsupportedOperationException.class, () -> budget.lines().clear());
    }

    private Budget restore(List<BudgetLine> lines) {
        return Budget.restore(budget.id(), owner, budget.month(), BudgetCurrency.RUB, lines, catalog);
    }
}
