package org.financelab.budget.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One immutable owner-wide catalog. Archived identities remain in the catalog permanently. */
public record CategoryCatalog(UUID ownerId, List<Category> categories) {
    private static final List<String> STARTER_LABELS = List.of(
            "Жильё и коммунальные услуги", "Продукты", "Рестораны и доставка еды", "Транспорт",
            "Здоровье и уход за собой", "Одежда и товары для дома", "Подписки и связь",
            "Образование и хобби", "Кредиты, страхование и налоги", "Прочие и нерегулярные расходы");

    public static List<String> starterLabels() {
        return STARTER_LABELS;
    }

    public CategoryCatalog {
        Objects.requireNonNull(ownerId, "ownerId");
        categories = List.copyOf(categories);
        var ids = new HashSet<UUID>();
        for (int i = 0; i < categories.size(); i++) {
            Category category = categories.get(i);
            if (!ownerId.equals(category.ownerId()) || !ids.add(category.id()) || category.position() != i + 1) {
                throw new IllegalArgumentException("Catalog requires same-owner categories, unique IDs and complete ordered positions");
            }
        }
    }

    /** Absence of the catalog, never the category count, is the initialization condition. */
    public static CategoryCatalog initialize(UUID ownerId, Optional<CategoryCatalog> existing) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (existing.isPresent()) {
            existing.get().requireOwner(ownerId);
            return existing.get();
        }
        var categories = new ArrayList<Category>();
        for (String label : STARTER_LABELS) {
            categories.add(new Category(UUID.randomUUID(), ownerId, label, categories.size() + 1, Category.Status.ACTIVE));
        }
        return new CategoryCatalog(ownerId, categories);
    }

    public void requireOwner(UUID expectedOwnerId) {
        if (!ownerId.equals(expectedOwnerId)) throw new IllegalArgumentException("Catalog belongs to another owner");
    }

    public Category category(UUID id) {
        return categories.stream().filter(category -> category.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
    }

    public CategoryCatalog add(String label) {
        UUID id;
        do { id = UUID.randomUUID(); } while (contains(id));
        var updated = new ArrayList<>(categories);
        updated.add(new Category(id, ownerId, label, updated.size() + 1, Category.Status.ACTIVE));
        return new CategoryCatalog(ownerId, updated);
    }

    private boolean contains(UUID id) {
        return categories.stream().anyMatch(category -> category.id().equals(id));
    }

    public CategoryCatalog rename(UUID id, String label) {
        Category old = category(id);
        return replace(new Category(id, ownerId, label, old.position(), old.status()));
    }

    public CategoryCatalog archive(UUID id) {
        Category old = category(id);
        return replace(new Category(id, ownerId, old.label(), old.position(), Category.Status.ARCHIVED));
    }

    private CategoryCatalog replace(Category replacement) {
        return new CategoryCatalog(ownerId, categories.stream()
                .map(category -> category.id().equals(replacement.id()) ? replacement : category).toList());
    }

    /** The full order includes archived categories, so no identity or position is silently dropped. */
    public CategoryCatalog reorder(List<UUID> orderedIds) {
        if (orderedIds.size() != categories.size() || new HashSet<>(orderedIds).size() != categories.size()) {
            throw new IllegalArgumentException("Reorder must contain every category exactly once");
        }
        var updated = new ArrayList<Category>();
        for (UUID id : orderedIds) {
            Category category = category(id);
            updated.add(new Category(id, ownerId, category.label(), updated.size() + 1, category.status()));
        }
        return new CategoryCatalog(ownerId, updated);
    }
}
