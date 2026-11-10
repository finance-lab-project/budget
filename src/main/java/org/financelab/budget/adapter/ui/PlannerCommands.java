package org.financelab.budget.adapter.ui;

import org.financelab.budget.application.BudgetService;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.application.SaveResult;
import org.financelab.budget.domain.Category;
import org.financelab.budget.domain.CategoryCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.formatAmount;
import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.parseAmount;

/** Maps the shared planner's controls to the existing owner-scoped Budget operations. */
public final class PlannerCommands {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PlannerCommands() { }
    record CategoryRow(String id, String label, boolean archived) { }
    record Line(String id, String label, boolean archived, String amount) { }
    record Month(long version, long catalogVersion, String income, String investment, List<Line> lines) { }
    record Catalog(long version, List<CategoryRow> categories) { }

    public static String execute(BudgetService service, String action, String payload) {
        try {
            return JSON.writeValueAsString(run(service, action, JSON.readTree(payload)));
        } catch (IllegalArgumentException | java.time.DateTimeException | tools.jackson.core.JacksonException invalid) {
            return "{\"error\":\"invalid\"}";
        } catch (RuntimeException failure) {
            // A disconnected client must not assume a write rolled back or retry it automatically.
            return "{\"error\":\"unknown\"}";
        }
    }

    private static Object run(BudgetService service, String action, JsonNode data) {
        return switch (action) {
            case "catalog" -> {
                var v = service.catalog();
                yield new Catalog(v.revision().version(), v.catalog().categories().stream()
                        .map(c -> new CategoryRow(c.id().toString(), c.label(), c.status() == Category.Status.ARCHIVED)).toList());
            }
            case "saveCatalog" -> {
                var owner = service.catalog().catalog().ownerId();
                var rows = array(data, "categories");
                var categories = new java.util.ArrayList<Category>();
                for (JsonNode c : rows) {
                    if (!c.path("archived").isBoolean()) throw new IllegalArgumentException();
                    categories.add(new Category(uuid(c), owner, string(c, "label"), categories.size() + 1,
                            c.get("archived").asBoolean() ? Category.Status.ARCHIVED : Category.Status.ACTIVE));
                }
                yield result(service.saveCatalog(number(data, "version"), new CategoryCatalog(owner, categories)));
            }
            case "month" -> month(service.current(period(data)));
            case "history", "revision" -> {
                var v = service.current(period(data));
                if (action.equals("history")) yield v.revision().isEmpty() ? List.of() :
                        service.revisions(v.budget().id()).stream().map(r -> Map.of("version", r.version(), "at", r.changedAt().toString())).toList();
                yield month(service.revision(v.budget().id(), number(data, "version")).orElseThrow(IllegalArgumentException::new));
            }
            case "save" -> {
                var lines = array(data, "lines").stream().map(l -> new BudgetStore.SpendingValue(uuid(l), parseAmount(string(l, "amount")))).toList();
                yield result(service.save(new BudgetStore.SaveBudget(period(data), number(data, "version"),
                        number(data, "catalogVersion"), parseAmount(string(data, "income")), lines, parseAmount(string(data, "investment")))));
            }
            default -> throw new IllegalArgumentException();
        };
    }

    private static Month month(BudgetStore.BudgetView v) {
        var b = v.budget();
        return new Month(v.version(), v.catalog().revision().version(),
                amount(b.income().amount()), amount(b.investment().amount()), b.spending(v.catalog().catalog()).stream()
                .map(l -> new Line(l.category().id().toString(), l.category().label(),
                        l.category().status() == Category.Status.ARCHIVED, amount(l.line().amount()))).toList());
    }

    private static String amount(org.financelab.budget.domain.BudgetAmount value) {
        return formatAmount(value).replace("\u00a0₽", "");
    }

    private static Object result(SaveResult<?> r) {
        if (r instanceof SaveResult.Saved<?> saved) return Map.of("saved", true, "revision", saved.revision());
        if (r instanceof SaveResult.Unchanged<?> unchanged) return Map.of("saved", true, "revision", unchanged.revision());
        return Map.of("error", r instanceof SaveResult.Conflict<?> ? "conflict" : r instanceof SaveResult.Invalid<?> ? "invalid"
                : r instanceof SaveResult.Failed<?> ? "failed" : "unknown");
    }

    private static String string(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isString()) throw new IllegalArgumentException();
        return value.asString();
    }
    private static long number(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw new IllegalArgumentException();
        return value.asLong();
    }
    private static UUID uuid(JsonNode node) { return UUID.fromString(string(node, "id")); }
    private static YearMonth period(JsonNode node) {
        String month = string(node, "month");
        if (!month.matches("(?!0000)[0-9]{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException();
        return YearMonth.parse(month);
    }
    private static List<JsonNode> array(JsonNode node, String key) {
        var a = node.path(key);
        if (!a.isArray()) throw new IllegalArgumentException();
        return StreamSupport.stream(a.spliterator(), false).toList();
    }
}
