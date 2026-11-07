package org.financelab.budget.adapter.ui;

import org.financelab.budget.application.BudgetService;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.application.SaveResult;
import org.financelab.budget.domain.BudgetAmount;
import org.financelab.budget.domain.Category;
import org.financelab.budget.domain.CategoryCatalog;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;
import java.util.*;
import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.parseAmount;

/** Adapts browser import forms to version-checked, owner-scoped Budget saves. */
public final class PlannerTransfer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PlannerTransfer() { }

    public record Reply(int status, Map<String, ?> body) { }

    public static Reply submit(BudgetService service, Map<String, String[]> form) {
        return "catalog".equals(first(form, "operation")) ? importCatalog(service, form) : importMonth(service, form);
    }

    private record Option(String id, String label) { }
    private record Difference(String label, String local, String cloud) { }

    /** Importing categories is optional and explicitly reviewed; an established account keeps its own settings. */
    private static Reply importCatalog(BudgetService service, Map<String, String[]> form) {
        String source = first(form, "categories");
        if (source == null) throw new IllegalArgumentException();
        var rows = JSON.readValue(source, PlannerCommands.CategoryRow[].class);
        var current = service.catalog();
        var catalog = current.catalog();
        var existing = catalog.categories();
        boolean fresh = current.revision().version() == 1;
        var target = new ArrayList<Category>();
        if (!fresh) target.addAll(existing);
        var mappings = new LinkedHashMap<String, String>();
        var used = new HashSet<UUID>();
        for (var row : rows) {
            if (row == null || row.id() == null || row.label() == null || row.label().isBlank() || mappings.containsKey(row.id()))
                throw new IllegalArgumentException();
            boolean starter = row.id().matches("starter-[0-9]+");
            int index = starter ? Integer.parseInt(row.id().substring(8)) : -1;
            if (index >= CategoryCatalog.starterLabels().size()) throw new IllegalArgumentException();
            UUID guestId = starter ? null : UUID.fromString(row.id());
            var direct = existing.stream().filter(x -> x.id().equals(guestId)).findFirst();
            var names = existing.stream().filter(x -> x.label().equals(row.label())).toList();
            Category match = fresh && starter ? existing.get(index) : direct.orElse(names.size() == 1 ? names.getFirst() : null);
            if (match != null && !used.add(match.id())) match = null;
            UUID id = match != null ? match.id() : starter ? UUID.randomUUID() : guestId;
            if (fresh || match == null)
                target.add(new Category(id, catalog.ownerId(), row.label(), target.size()+1,
                        row.archived() ? Category.Status.ARCHIVED : Category.Status.ACTIVE));
            mappings.put(row.id(), id.toString());
        }
        // A malformed submission cannot omit a server-owned category.
        if (fresh && !used.containsAll(existing.stream().map(Category::id).toList())) throw new IllegalArgumentException();
        long version = current.revision().version();
        if (!Long.toString(version).equals(first(form, "version"))) {
            return new Reply(200, Map.of("version", version, "fresh", fresh, "preview", target.stream()
                    .map(c -> c.label() + (c.status() == Category.Status.ARCHIVED ? " (в архиве)" : "")).toList()));
        }
        var result = service.saveCatalog(version, new CategoryCatalog(catalog.ownerId(), target));
        if (result instanceof SaveResult.Saved<?> || result instanceof SaveResult.Unchanged<?>) {
            return new Reply(200, Map.of("saved", true, "mapping", mappings));
        }
        return new Reply(409, Map.of("message", "Перенос категорий не подтверждён. Откройте перенос заново и проверьте категории."));
    }

    private record EarlierPlan(String income, String investment, List<String> spending) { }
    private record Amounts(BudgetAmount income, BudgetAmount investment, Map<Integer, BudgetAmount> spending) {
        static Amounts from(EarlierPlan plan, int size) {
            if (plan.spending() == null || plan.spending().size() != size) throw new IllegalArgumentException();
            var amounts = new HashMap<Integer, BudgetAmount>();
            for (int i = 0; i < size; i++) {
                // Missing means the category did not exist in this snapshot. An empty string is a saved UNSET.
                String raw = plan.spending().get(i);
                if (raw != null) amounts.put(i, parseAmount(raw));
            }
            return new Amounts(parseAmount(plan.income()), parseAmount(plan.investment()), amounts);
        }
    }

    private static Reply importMonth(BudgetService service, Map<String, String[]> form) {
        String month = required(form, "month");
        if (!month.matches("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
        var period = YearMonth.parse(month);
        var view = service.current(period);
        var current = view.budget();
        var catalog = view.catalog().catalog();
        var spending = new LinkedHashMap<UUID, BudgetAmount>();
        current.spending(catalog).forEach(line -> spending.put(line.category().id(), line.line().amount()));
        var options = catalog.categories().stream().map(c -> new Option(c.id().toString(),
                c.label() + (c.status() == Category.Status.ARCHIVED ? " (в архиве)" : ""))).toList();
        var changes = new ArrayList<Difference>();
        var unresolved = new LinkedHashMap<String, String>();
        var assigned = new HashSet<UUID>();
        var income = amount(form, "income");
        var investment = amount(form, "investment");
        if (income.value().isPresent()) {
            difference(changes, "Доход", current.income().amount(), income);
        } else {
            income = current.income().amount();
        }
        if (investment.value().isPresent()) {
            difference(changes, "Инвестиции", current.investment().amount(), investment);
        } else {
            investment = current.investment().amount();
        }
        String[] labels = form.get("label");
        int size = labels == null ? CategoryCatalog.starterLabels().size() : labels.length;
        var earlier = new ArrayList<Amounts>();
        String sourceHistory = first(form, "earlier");
        if (view.version() == 0 && sourceHistory != null) {
            for (var p : JSON.readValue(sourceHistory, EarlierPlan[].class)) {
                earlier.add(Amounts.from(p, size));
            }
        }
        var destinations = new HashMap<Integer, UUID>();
        var incoming = new HashMap<UUID, BudgetAmount>();
        for (int i = 0; i < size; i++) {
            var value = amount(form, "spend-" + i);
            String label = labels == null ? CategoryCatalog.starterLabels().get(i) : labels[i];
            String target = first(form, "map-" + i);
            var matches = catalog.categories().stream().filter(c -> target == null ? label.equals(c.label())
                    : c.id().toString().equals(target)).toList();
            int index = i;
            boolean hasAmount = value.value().isPresent() || earlier.stream()
                    .anyMatch(p -> p.spending().getOrDefault(index, BudgetAmount.UNSET).value().isPresent());
            boolean savedArchivedLine = view.version() == 0 && labels != null && matches.size() == 1
                    && matches.getFirst().status() == Category.Status.ARCHIVED;
            if (!hasAmount && !savedArchivedLine) continue;
            if (matches.size() != 1 || !assigned.add(matches.getFirst().id())) {
                unresolved.put("map-" + i, label);
                continue;
            }
            var category = matches.getFirst();
            destinations.put(i, category.id());
            if (value.value().isPresent()) {
                difference(changes, category.label(), spending.getOrDefault(category.id(), BudgetAmount.UNSET), value);
            }
            if (value.value().isPresent() || savedArchivedLine) incoming.put(category.id(), value);
        }
        String version = Long.toString(view.version());
        String catalogVersion = Long.toString(view.catalog().revision().version());
        boolean approved = version.equals(first(form, "version")) && catalogVersion.equals(first(form, "catalog"));
        // Catalog edits can rename or reuse a starter label. Let the owner confirm the mapping in that case.
        boolean mappingReview = view.catalog().revision().version() > 1 && !assigned.isEmpty();
        if (!unresolved.isEmpty() || !approved && (view.version() > 0 && !changes.isEmpty() || mappingReview)) {
            return new Reply(409, Map.of("version", version, "catalog", catalogVersion, "changes", changes,
                    "unresolved", unresolved, "options", options));
        }
        // Replay only into an empty month. Never splice unrelated drafts into an existing account history.
        long expected = view.version();
        long catalogRevision = view.catalog().revision().version();
        for (var p : earlier) {
            var snapshot = new LinkedHashMap<>(spending);
            for (var to : destinations.entrySet()) {
                var amount = p.spending().get(to.getKey());
                if (amount == null) continue;
                snapshot.put(to.getValue(), amount);
                // Once a line exists, later versions keep it even if its amount is cleared.
                spending.putIfAbsent(to.getValue(), BudgetAmount.UNSET);
            }
            var saved = service.save(importCommand(period, expected, catalogRevision, p.income(), snapshot, p.investment()));
            if (saved instanceof SaveResult.Saved<BudgetStore.BudgetRevision> s) expected = s.revision().version();
            else if (saved instanceof SaveResult.Unchanged<BudgetStore.BudgetRevision> u) expected = u.revision().version();
            else {
                return new Reply(409, Map.of("message", "Перенос версий остановлен. Проверьте историю аккаунта; оригиналы остались в браузере."));
            }
        }
        spending.putAll(incoming);
        var result = service.save(importCommand(period, expected, catalogRevision, income, spending, investment));
        if (result instanceof SaveResult.Saved<?> || result instanceof SaveResult.Unchanged<?>) {
            return new Reply(200, Map.of("saved", true, "copiedHistory", !earlier.isEmpty()));
        } else if (result instanceof SaveResult.Conflict<?>) {
            return new Reply(409, Map.of("message", "Месяц или категории изменились. Проверьте актуальные данные и повторите перенос."));
        } else if (result instanceof SaveResult.Invalid<?>) {
            return new Reply(400, Map.of("message", "Месяц не прошёл проверку. Данные сохранены в браузере."));
        } else {
            return new Reply(503, Map.of("message", "Сохранение не подтверждено. Откройте бюджет и проверьте историю. Месяц остался в браузере."));
        }
    }

    private static BudgetStore.SaveBudget importCommand(YearMonth month, long version, long catalogVersion,
            BudgetAmount income, Map<UUID, BudgetAmount> spending, BudgetAmount investment) {
        var lines = spending.entrySet().stream().map(e -> new BudgetStore.SpendingValue(e.getKey(), e.getValue())).toList();
        return new BudgetStore.SaveBudget(month, version, catalogVersion, income, lines, investment, true);
    }

    private static void difference(List<Difference> result, String label, BudgetAmount old, BudgetAmount next) {
        if (!old.equals(next)) result.add(new Difference(label, next.value().orElseThrow().toPlainString(),
                old.value().map(java.math.BigDecimal::toPlainString).orElse("")));
    }

    private static String required(Map<String, String[]> form, String name) {
        String value = first(form, name);
        if (value == null || value.length() > 80) throw new IllegalArgumentException();
        return value;
    }
    private static BudgetAmount amount(Map<String, String[]> form, String name) { return parseAmount(required(form, name)); }
    private static String first(Map<String, String[]> form, String name) {
        String[] values = form.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }
}
