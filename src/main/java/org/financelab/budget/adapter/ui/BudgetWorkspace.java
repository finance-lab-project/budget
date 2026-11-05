package org.financelab.budget.adapter.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import org.financelab.budget.application.BudgetService;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.application.SaveResult;
import org.financelab.budget.domain.BudgetAmount;
import org.financelab.budget.domain.Category;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Objects;

import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.*;

/** Root-owned routes embed this component with a service bound to the authenticated server session. */
public final class BudgetWorkspace extends VerticalLayout implements BeforeLeaveObserver {
    private enum Screen { MONTH, CATEGORIES, HISTORY }
    private final BudgetService service;
    private final Clock clock;
    private final VerticalLayout content = new VerticalLayout();
    private final Paragraph status = new Paragraph();
    private final Button save = new Button("Сохранить", event -> save());
    private final Button reload = new Button("Загрузить сохранённое", event -> reload());
    private final HorizontalLayout actions = new HorizontalLayout(save, reload);
    private Screen screen = Screen.MONTH;
    private YearMonth month;
    private MonthEditor monthEditor;
    private CategoryEditor categoryEditor;
    private boolean needsReload;
    private boolean unconfirmedMonth;
    private Dialog pending;

    public BudgetWorkspace(BudgetService service) { this(service, Clock.system(HISTORY_ZONE)); }

    public BudgetWorkspace(BudgetService service, Clock clock) {
        this.service = Objects.requireNonNull(service);
        this.clock = Objects.requireNonNull(clock).withZone(HISTORY_ZONE);
        month = YearMonth.now(this.clock);
        setWidthFull();
        setMaxWidth("52rem");
        getStyle().set("margin", "0 auto");
        getElement().setAttribute("lang", "ru");
        content.setPadding(false);
        save.setId("save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        reload.setId("reload");
        status.setId("save-status");
        status.getElement().setAttribute("role", "status");
        status.getElement().setAttribute("aria-live", "polite");
        HorizontalLayout navigation = new HorizontalLayout(
                new Button("План месяца", e -> navigate(Screen.MONTH, month)),
                new Button("Настройки", e -> navigate(Screen.CATEGORIES, month)),
                new Button("История", e -> navigate(Screen.HISTORY, month)));
        navigation.setWrap(true);
        HorizontalLayout months = new HorizontalLayout(
                new Button("Предыдущий месяц", e -> navigate(Screen.MONTH, month.minusMonths(1))),
                new Button("Текущий месяц", e -> navigate(Screen.MONTH, YearMonth.now(this.clock))),
                new Button("Следующий месяц", e -> navigate(Screen.MONTH, month.plusMonths(1))));
        months.setWrap(true);
        actions.setWrap(true);
        add(new H1("Бюджет"), navigation, months, status, actions, content);
        show(Screen.MONTH, month);
    }

    @Override protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        event.getUI().setLocale(LOCALE);
        // Native refresh/tab-close dialogs cannot offer custom save/discard/cancel buttons.
        // Mark dirty on input immediately, before the server value-change round trip.
        getElement().executeJs("""
                if (!this.__budgetUnload) {
                  this.__budgetUnload = e => {
                    if (this.isConnected && this.budgetDirty) { e.preventDefault(); e.returnValue = ''; }
                  };
                  window.addEventListener('beforeunload', this.__budgetUnload);
                  this.addEventListener('input', () => { this.budgetDirty = true; });
                }
                """);
        getElement().setProperty("budgetDirty", dirty() || needsReload);
    }

    private boolean dirty() {
        return (monthEditor != null && monthEditor.dirty()) || (categoryEditor != null && categoryEditor.dirty());
    }

    private void changed() {
        getElement().setProperty("budgetDirty", dirty() || needsReload);
        if (!needsReload) feedback(dirty() ? "draft" : "ready", dirty() ? "Есть несохранённые изменения." : "Нет несохранённых изменений.");
    }

    private void feedback(String state, String message) {
        status.getElement().setAttribute("data-state", state);
        status.setText(message);
    }

    private void navigate(Screen target, YearMonth targetMonth) {
        leave(() -> show(target, targetMonth), () -> { });
    }

    private boolean show(Screen target, YearMonth targetMonth) {
        try {
            // Finish every read before replacing the existing draft.
            BudgetStore.BudgetView uncertain = null;
            if (unconfirmedMonth) {
                uncertain = service.current(month);
                if (uncertain.revision().isPresent()) service.revisions(uncertain.budget().id());
            }
            MonthEditor nextMonth = null;
            CategoryEditor nextCatalog = null;
            VerticalLayout next;
            switch (target) {
                case MONTH -> {
                    var current = uncertain != null && month.equals(targetMonth) ? uncertain : service.current(targetMonth);
                    nextMonth = new MonthEditor(current, this::changed);
                    next = nextMonth;
                }
                case CATEGORIES -> {
                    nextCatalog = new CategoryEditor(service.catalog(), this::changed);
                    next = nextCatalog;
                }
                case HISTORY -> next = history(targetMonth);
                default -> throw new IllegalStateException();
            }
            monthEditor = nextMonth;
            categoryEditor = nextCatalog;
            month = targetMonth;
            screen = target;
            content.removeAll();
            content.add(next);
            needsReload = false;
            unconfirmedMonth = false;
            save.setEnabled(true);
            actions.setVisible(target != Screen.HISTORY);
            changed();
            return true;
        } catch (RuntimeException error) {
            save.setEnabled(!needsReload && (monthEditor != null || categoryEditor != null));
            feedback("read-failed", "Не удалось загрузить данные. Текущий черновик сохранён на экране. Повторите загрузку.");
            return false;
        }
    }

    private boolean save() {
        if (needsReload) return false;
        org.financelab.budget.domain.Budget budgetDraft;
        org.financelab.budget.domain.CategoryCatalog catalogDraft;
        try {
            budgetDraft = monthEditor == null ? null : monthEditor.draft();
            catalogDraft = categoryEditor == null ? null : categoryEditor.draft();
        } catch (IllegalArgumentException error) {
            feedback("invalid", error.getMessage());
            return false;
        }
        try {
            SaveResult<?> result;
            if (monthEditor != null) {
                result = service.save(monthEditor.command(budgetDraft));
                if (result instanceof SaveResult.Saved<?> saved) monthEditor.accepted(budgetDraft, (BudgetStore.BudgetRevision) saved.revision());
                if (result instanceof SaveResult.Unchanged<?> unchanged) monthEditor.accepted(budgetDraft, (BudgetStore.BudgetRevision) unchanged.revision());
            } else if (categoryEditor != null) {
                result = service.saveCatalog(categoryEditor.version(), catalogDraft);
                if (result instanceof SaveResult.Saved<?> saved) categoryEditor.accepted(catalogDraft, (BudgetStore.CatalogRevision) saved.revision());
                if (result instanceof SaveResult.Unchanged<?> unchanged) categoryEditor.accepted(catalogDraft, (BudgetStore.CatalogRevision) unchanged.revision());
            } else return false;
            changed();
            return outcome(result);
        } catch (RuntimeException error) {
            // A lost service response cannot establish whether a write committed.
            return outcome(new SaveResult.OutcomeUnknown<>());
        }
    }

    private boolean outcome(SaveResult<?> result) {
        if (result instanceof SaveResult.Saved<?>) {
            feedback("saved", "Сохранено.");
            return true;
        }
        if (result instanceof SaveResult.Unchanged<?>) {
            feedback("unchanged", "Изменений нет. Новая версия не создана.");
            return true;
        }
        if (result instanceof SaveResult.Invalid<?>) feedback("invalid", "Данные не прошли проверку. Проверьте суммы и категории. Черновик сохранён на экране.");
        if (result instanceof SaveResult.Failed<?>) feedback("failed", "Сохранение не выполнено. Черновик сохранён на экране. Можно повторить сохранение вручную.");
        if (result instanceof SaveResult.Conflict<?> conflict) {
            needsReload = true;
            feedback("conflict", (conflict.aggregate() == SaveResult.Aggregate.CATALOG ? "Категории изменены" : "Бюджет изменён")
                    + " в другой вкладке. Черновик сохранён на экране. Загрузите сохранённое состояние перед новым сохранением.");
        }
        if (result instanceof SaveResult.OutcomeUnknown<?>) {
            needsReload = true;
            unconfirmedMonth = monthEditor != null;
            feedback("unknown", "Сохранение не подтверждено: данные могли записаться. Черновик сохранён на экране. "
                    + (unconfirmedMonth ? "Загрузите данные и историю месяца" : "Загрузите каталог")
                    + " перед новым сохранением. Совпадение данных не подтверждает результат этого запроса.");
        }
        save.setEnabled(!needsReload);
        getElement().setProperty("budgetDirty", dirty() || needsReload);
        return false;
    }

    private void reload() {
        if (!dirty() && !needsReload) { show(screen, month); return; }
        Dialog dialog = dialog("Загрузить сохранённое состояние?",
                "Текущий черновик будет заменён данными с сервера."
                        + (unconfirmedMonth ? " Также будет прочитана история месяца." : ""));
        dialog.getFooter().add(new Button("Отмена", e -> closeDialog()),
                new Button("Заменить черновик", e -> { if (show(screen, month)) closeDialog(); }));
        dialog.open();
    }

    private void leave(Runnable proceed, Runnable cancel) {
        if (!dirty() && !needsReload) { proceed.run(); return; }
        if (pending != null) { cancel.run(); return; }
        Dialog dialog = dialog("Несохранённые изменения", "Сохранить изменения перед выходом, отказаться от них или остаться?");
        Button saveAndLeave = new Button("Сохранить и перейти", e -> {
            if (save()) { closeDialog(); proceed.run(); }
            else { closeDialog(); cancel.run(); }
        });
        saveAndLeave.setEnabled(!needsReload);
        dialog.getFooter().add(new Button("Отмена", e -> { closeDialog(); cancel.run(); }),
                new Button("Не сохранять", e -> { closeDialog(); proceed.run(); }), saveAndLeave);
        dialog.addDialogCloseActionListener(e -> { closeDialog(); cancel.run(); });
        dialog.open();
    }

    private Dialog dialog(String title, String text) {
        if (pending != null) closeDialog();
        pending = new Dialog();
        pending.setHeaderTitle(title);
        pending.setCloseOnOutsideClick(false);
        pending.add(new Paragraph(text));
        // Esc is an explicit cancellation; leave() also cancels postponed route navigation.
        pending.addDialogCloseActionListener(e -> closeDialog());
        return pending;
    }

    private void closeDialog() {
        if (pending != null) { pending.close(); pending = null; }
    }

    @Override public void beforeLeave(BeforeLeaveEvent event) {
        if (!dirty() && !needsReload) return;
        var continuation = event.postpone();
        leave(continuation::proceed, continuation::cancel);
    }

    private VerticalLayout history(YearMonth requestedMonth) {
        var current = service.current(requestedMonth);
        var view = new VerticalLayout();
        view.setPadding(false);
        view.add(new H2("История · " + formatMonth(requestedMonth)), new Paragraph("Сохранённые версии доступны только для чтения."));
        if (current.revision().isEmpty()) {
            view.add(new Paragraph("Сохранённых версий пока нет."));
            return view;
        }
        var details = new VerticalLayout();
        details.setPadding(false);
        details.setId("revision-detail");
        for (var revision : service.revisions(current.budget().id())) {
            Button open = new Button("Версия " + revision.version() + " · " + formatHistoryTime(revision.changedAt()), e -> {
                try {
                    var snapshot = service.revision(revision.budgetId(), revision.version());
                    details.removeAll();
                    if (snapshot.isEmpty()) { details.add(new Paragraph("Версия не найдена.")); return; }
                    var budget = snapshot.get().budget();
                    details.add(new H3("Версия " + revision.version()), new Paragraph(formatHistoryTime(revision.changedAt())),
                            value("Доход", budget.income().amount()));
                    for (var line : budget.spending(snapshot.get().catalog().catalog())) {
                        details.add(value(line.category().label()
                                + (line.category().status() == Category.Status.ARCHIVED ? " (в архиве)" : ""), line.line().amount()));
                    }
                    details.add(value("Инвестиции", budget.investment().amount()),
                            new Paragraph("Расходы: " + formatMoney(budget.totalSpending()) + " · Остаток: " + formatMoney(budget.remaining())));
                } catch (RuntimeException error) {
                    feedback("read-failed", "Не удалось загрузить версию. Повторите запрос.");
                }
            });
            open.setId("revision-" + revision.version());
            view.add(open);
        }
        view.add(details);
        return view;
    }

    private static Paragraph value(String label, BudgetAmount amount) {
        return new Paragraph(label + ": " + (amount.value().isPresent() ? formatAmount(amount) : "Не задано"));
    }
}
