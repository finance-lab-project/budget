package org.financelab.budget.adapter.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.domain.Budget;
import org.financelab.budget.domain.BudgetAmount;
import org.financelab.budget.domain.Category;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.*;

/** Raw strings stay in the fields, including incomplete/invalid input, until the user decides. */
final class MonthEditor extends VerticalLayout {
    private BudgetStore.BudgetView base;
    private final Map<UUID, TextField> fields = new LinkedHashMap<>();
    private final Map<UUID, String> savedText = new LinkedHashMap<>();
    private final Paragraph totals = new Paragraph();
    private final Paragraph revision = new Paragraph();
    private final Runnable onChange;

    MonthEditor(BudgetStore.BudgetView view, Runnable onChange) {
        this.base = view;
        this.onChange = onChange;
        setPadding(false);
        add(new H2(formatMonth(view.budget().month())), revision);
        updateRevision();
        add(new Paragraph("Пустое поле — сумма не задана. Изменения записываются только кнопкой «Сохранить»."));
        amount("Доход", "income", view.budget().income().id(), view.budget().income().amount());
        add(new H3("Расходы"));
        for (var line : view.budget().spending(view.catalog().catalog())) {
            String label = line.category().label()
                    + (line.category().status() == Category.Status.ARCHIVED ? " (в архиве)" : "");
            amount(label, "spending-" + line.category().id(), line.line().id(), line.line().amount());
        }
        amount("Инвестиции", "investment", view.budget().investment().id(), view.budget().investment().amount());
        totals.setId("totals");
        totals.getElement().setAttribute("aria-live", "polite");
        add(totals);
        updateTotals();
    }

    private void amount(String label, String id, UUID lineId, BudgetAmount value) {
        TextField field = new TextField(label);
        field.setId(id);
        field.setWidthFull();
        field.setSuffixComponent(new Span("₽"));
        field.getElement().setAttribute("inputmode", "decimal");
        field.setValue(input(value));
        field.setValueChangeMode(ValueChangeMode.EAGER);
        fields.put(lineId, field);
        savedText.put(lineId, field.getValue());
        field.addValueChangeListener(event -> { updateTotals(); onChange.run(); });
        add(field);
    }

    private static String input(BudgetAmount value) {
        String formatted = formatAmount(value);
        return formatted.isEmpty() ? "" : formatted.substring(0, formatted.length() - 2);
    }

    boolean dirty() {
        return fields.entrySet().stream().anyMatch(e -> !e.getValue().getValue().equals(savedText.get(e.getKey())));
    }

    Budget draft() {
        Budget result = base.budget();
        boolean invalid = false;
        for (var line : base.budget().lines()) {
            TextField field = fields.get(line.id());
            try {
                BudgetAmount amount = parseAmount(field.getValue());
                field.setInvalid(false);
                result = switch (line.kind()) {
                    case INCOME -> result.withIncome(amount);
                    case INVESTMENT -> result.withInvestment(amount);
                    case SPENDING -> result.withSpending(line.categoryId().orElseThrow(), amount);
                };
            } catch (IllegalArgumentException error) {
                field.setErrorMessage(error.getMessage());
                field.setInvalid(true);
                invalid = true;
            }
        }
        if (invalid) throw new IllegalArgumentException("Проверьте суммы в отмеченных полях.");
        return result;
    }

    private void updateTotals() {
        try {
            Budget draft = draft();
            totals.setText("Расходы: " + formatMoney(draft.totalSpending()) + " · Остаток: " + formatMoney(draft.remaining()));
        } catch (IllegalArgumentException error) {
            totals.setText("Итоги недоступны: исправьте суммы в отмеченных полях.");
        }
    }

    BudgetStore.SaveBudget command(Budget draft) {
        return new BudgetStore.SaveBudget(draft.month(), base.version(), base.catalog().revision().version(),
                draft.income().amount(), draft.spending(base.catalog().catalog()).stream()
                .map(line -> new BudgetStore.SpendingValue(line.category().id(), line.line().amount())).toList(),
                draft.investment().amount());
    }

    void accepted(Budget draft, BudgetStore.BudgetRevision metadata) {
        Budget saved = Budget.restore(metadata.budgetId(), draft.ownerId(), draft.month(), draft.currency(),
                draft.lines(), base.catalog().catalog());
        base = new BudgetStore.BudgetView(saved, base.catalog(), java.util.Optional.of(metadata));
        fields.forEach((id, field) -> savedText.put(id, field.getValue()));
        updateRevision();
    }

    private void updateRevision() {
        revision.setText(base.revision().map(r -> "Версия " + r.version() + " · " + formatHistoryTime(r.changedAt()))
                .orElse("Месяц ещё не сохранён"));
    }
}
