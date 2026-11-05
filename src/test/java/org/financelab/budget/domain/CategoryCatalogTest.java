package org.financelab.budget.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CategoryCatalogTest {
    private final UUID owner = UUID.randomUUID();
    private final CategoryCatalog catalog = CategoryCatalog.initialize(owner, Optional.empty());

    @Test void seedsTheAgreedLabelsAndOrderWithUniqueV4Ids() {
        assertEquals(List.of("Жильё и коммунальные услуги", "Продукты", "Рестораны и доставка еды", "Транспорт",
                "Здоровье и уход за собой", "Одежда и товары для дома", "Подписки и связь", "Образование и хобби",
                "Кредиты, страхование и налоги", "Прочие и нерегулярные расходы"), catalog.categories().stream().map(Category::label).toList());
        assertEquals(10, catalog.categories().stream().map(Category::id).distinct().count());
        for (int i = 0; i < 10; i++) {
            var category = catalog.categories().get(i);
            assertEquals(i + 1, category.position());
            assertEquals(4, category.id().version());
            assertEquals(owner, category.ownerId());
            assertEquals(Category.Status.ACTIVE, category.status());
        }
    }

    @Test void initializationPreservesExistingEditedArchivedAndEmptyCatalogs() {
        var ids = new ArrayList<>(catalog.categories().stream().map(Category::id).toList());
        Collections.reverse(ids);
        var edited = catalog.rename(ids.getFirst(), "  Другое  ").reorder(ids).add("Другое");
        for (Category category : edited.categories()) edited = edited.archive(category.id());
        assertSame(edited, CategoryCatalog.initialize(owner, Optional.of(edited)));
        assertEquals(ids.getFirst(), edited.categories().getFirst().id());
        assertEquals("Другое", edited.categories().getFirst().label());
        var emptyExisting = new CategoryCatalog(owner, List.of());
        assertSame(emptyExisting, CategoryCatalog.initialize(owner, Optional.of(emptyExisting)));
    }

    @Test void labelsMayDuplicateAndArchiveNeverReleasesIdentity() {
        UUID oldId = catalog.categories().getFirst().id();
        var changed = catalog.rename(oldId, "\uFEFF  \tДубликат\u2028 ").archive(oldId).add("Дубликат");
        assertEquals("Дубликат", changed.category(oldId).label());
        assertEquals(Category.Status.ARCHIVED, changed.category(oldId).status());
        assertNotEquals(oldId, changed.categories().getLast().id());
        assertEquals("Дубликат", changed.categories().getLast().label());
        assertEquals(11, changed.categories().size());
        assertEquals(11, changed.categories().stream().map(Category::id).distinct().count());
        assertEquals(Category.Status.ACTIVE, catalog.category(oldId).status());
        assertEquals("Жильё и коммунальные услуги", catalog.category(oldId).label());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "  ", "\uFEFF", "\u2028\u2029"})
    void blankLabelsCannotBeAddedOrRenamed(String label) {
        assertThrows(IllegalArgumentException.class, () -> catalog.add(label));
        assertThrows(IllegalArgumentException.class, () -> catalog.rename(catalog.categories().getFirst().id(), label));
    }

    @Test void reorderRequiresEveryIdentityExactlyOnceAndRetainsArchiveStatus() {
        var ids = new ArrayList<>(catalog.categories().stream().map(Category::id).toList());
        UUID archived = ids.getFirst();
        Collections.reverse(ids);
        var reordered = catalog.archive(archived).reorder(ids);
        assertEquals(ids, reordered.categories().stream().map(Category::id).toList());
        assertEquals(Category.Status.ARCHIVED, reordered.category(archived).status());
        assertThrows(IllegalArgumentException.class, () -> catalog.reorder(ids.subList(0, 9)));
        ids.set(0, ids.get(1));
        assertThrows(IllegalArgumentException.class, () -> catalog.reorder(ids));
        ids.set(0, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> catalog.reorder(ids));
        assertThrows(IllegalArgumentException.class, () -> catalog.rename(UUID.randomUUID(), "Неизвестная"));
    }

    @Test void rejectsCorruptCatalogAndForeignOwner() {
        var first = catalog.categories().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new CategoryCatalog(UUID.randomUUID(), catalog.categories()));
        assertThrows(IllegalArgumentException.class, () -> CategoryCatalog.initialize(UUID.randomUUID(), Optional.of(catalog)));
        assertThrows(IllegalArgumentException.class, () -> new CategoryCatalog(owner, List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new CategoryCatalog(owner, List.of(catalog.categories().get(1))));
        assertThrows(UnsupportedOperationException.class, () -> catalog.categories().clear());
    }
}
