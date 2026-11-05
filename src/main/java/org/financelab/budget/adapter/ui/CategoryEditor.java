package org.financelab.budget.adapter.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.domain.Category;
import org.financelab.budget.domain.CategoryCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class CategoryEditor extends VerticalLayout {
    private BudgetStore.CatalogView base;
    private final List<Row> rows = new ArrayList<>();
    private final VerticalLayout list = new VerticalLayout();
    private final Runnable onChange;

    CategoryEditor(BudgetStore.CatalogView base, Runnable onChange) {
        this.base = base;
        this.onChange = onChange;
        setPadding(false);
        list.setPadding(false);
        add(new H2("Настройки · Категории"), new Paragraph(
                "Названия и порядок общие для всех месяцев. Архивные строки остаются в сохранённых месяцах и итогах. Изменения применяются после сохранения."));
        for (Category category : base.catalog().categories()) rows.add(new Row(category));
        Button add = new Button("Добавить категорию", event -> {
            Row row = new Row(new Category(UUID.randomUUID(), base.catalog().ownerId(), "Новая категория",
                    rows.size() + 1, Category.Status.ACTIVE));
            row.label.setValue("");
            rows.add(row);
            render();
            row.label.focus();
            onChange.run();
        });
        add.setId("add-category");
        add(list, add);
        render();
    }

    private final class Row extends VerticalLayout {
        final UUID id;
        final TextField label = new TextField("Название категории");
        Category.Status status;
        final Button up = new Button("Выше");
        final Button down = new Button("Ниже");
        final Button archive = new Button("В архив");

        Row(Category category) {
            id = category.id();
            status = category.status();
            setPadding(false);
            setId("category-" + id);
            label.setId("label-" + id);
            label.setWidthFull();
            label.setValue(category.label());
            label.setValueChangeMode(ValueChangeMode.EAGER);
            label.addValueChangeListener(event -> { label.setInvalid(false); onChange.run(); });
            up.addClickListener(event -> move(this, -1));
            down.addClickListener(event -> move(this, 1));
            archive.addClickListener(event -> {
                status = Category.Status.ARCHIVED;
                render();
                label.focus();
                onChange.run();
            });
            HorizontalLayout controls = new HorizontalLayout(up, down, archive);
            controls.setWrap(true);
            add(label, controls);
        }
    }

    private void move(Row row, int delta) {
        int index = rows.indexOf(row);
        Collections.swap(rows, index, index + delta);
        render();
        row.label.focus();
        onChange.run();
    }

    private void render() {
        list.removeAll();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.up.setEnabled(i > 0);
            row.down.setEnabled(i < rows.size() - 1);
            row.archive.setEnabled(row.status == Category.Status.ACTIVE);
            row.archive.setText(row.status == Category.Status.ARCHIVED ? "В архиве" : "В архив");
            row.label.setLabel("Категория " + (i + 1) + (row.status == Category.Status.ARCHIVED ? " (в архиве)" : ""));
            row.up.getElement().setAttribute("aria-label", "Переместить категорию " + (i + 1) + " выше");
            row.down.getElement().setAttribute("aria-label", "Переместить категорию " + (i + 1) + " ниже");
            row.archive.getElement().setAttribute("aria-label", "Архивировать категорию " + (i + 1));
            list.add(row);
        }
    }

    CategoryCatalog draft() {
        List<Category> categories = new ArrayList<>();
        boolean invalid = false;
        for (Row row : rows) {
            try {
                categories.add(new Category(row.id, base.catalog().ownerId(), row.label.getValue(),
                        categories.size() + 1, row.status));
                row.label.setInvalid(false);
            } catch (IllegalArgumentException error) {
                row.label.setErrorMessage("Введите непустое название категории.");
                row.label.setInvalid(true);
                invalid = true;
            }
        }
        if (invalid) throw new IllegalArgumentException("Проверьте названия категорий.");
        return new CategoryCatalog(base.catalog().ownerId(), categories);
    }

    boolean dirty() {
        if (rows.size() != base.catalog().categories().size()) return true;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Category original = base.catalog().categories().get(i);
            if (!row.id.equals(original.id()) || !row.label.getValue().equals(original.label())
                    || row.status != original.status()) return true;
        }
        return false;
    }

    long version() { return base.revision().version(); }
    void accepted(CategoryCatalog draft, BudgetStore.CatalogRevision revision) {
        base = new BudgetStore.CatalogView(draft, revision);
        for (Row row : rows) row.label.setValue(draft.category(row.id).label());
    }
}
