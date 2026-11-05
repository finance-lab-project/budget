package org.financelab.budget.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BudgetServiceTest {
    @Test void everyOperationUsesTheServerOwnerAndRechecksIdentity() {
        UUID owner = UUID.randomUUID();
        var identity = new AtomicReference<>(owner);
        List<List<Object>> calls = new ArrayList<>();
        BudgetStore store = (BudgetStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BudgetStore.class},
                (proxy, method, args) -> {
                    calls.add(Arrays.asList(args));
                    return switch (method.getName()) {
                        case "catalog", "revision" -> Optional.empty();
                        case "revisions" -> List.of();
                        default -> null;
                    };
                });
        var service = new BudgetService(store, identity::get);
        UUID budget = UUID.randomUUID();
        List<Runnable> operations = List.of(
                () -> service.current(YearMonth.of(2026, 11)), () -> service.revisions(budget),
                () -> service.revision(budget, 1), () -> service.save(null), () -> service.saveCatalog(1, null));
        operations.forEach(Runnable::run);
        assertThrows(java.util.NoSuchElementException.class, service::catalog);
        assertEquals(6, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.getFirst().equals(owner)));
        identity.set(null);
        operations.forEach(operation -> assertThrows(SecurityException.class, operation::run));
        assertThrows(SecurityException.class, service::catalog);
        assertThrows(SecurityException.class, () -> new BudgetService(store, identity::get));
        identity.set(UUID.randomUUID());
        operations.forEach(operation -> assertThrows(SecurityException.class, operation::run));
        assertThrows(SecurityException.class, service::catalog);
        assertEquals(6, calls.size(), "Unauthenticated/changed sessions never reach persistence");
    }
}
