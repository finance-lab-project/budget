package org.financelab.budget.verification;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;
import org.financelab.budget.adapter.ui.BudgetWorkspace;
import org.financelab.budget.application.BudgetService;
import org.financelab.budget.application.BudgetStore;
import org.financelab.budget.application.SaveResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Route("")
public class TestView extends Div {
    private String fault = "none";
    private int writes;
    private int historyReads;

    public TestView() {
        var session = VaadinSession.getCurrent();
        UUID owner = session.getAttribute(UUID.class);
        if (owner == null) {
            owner = UUID.randomUUID();
            session.setAttribute(UUID.class, owner);
            TestApplication.store.initializeCatalog(owner);
        }
        Span attempts = new Span("0");
        attempts.setId("write-attempts");
        Span reads = new Span("0");
        reads.setId("history-reads");
        BudgetStore port = (BudgetStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BudgetStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("revisions")) reads.setText(Integer.toString(++historyReads));
                    if (method.getName().equals("revision") && fault.equals("missing")) { fault = "none"; return Optional.empty(); }
                    if (method.getName().equals("current") && fault.equals("read")) { fault = "none"; throw new IllegalStateException("private diagnostic"); }
                    boolean write = method.getName().equals("save") || method.getName().equals("saveCatalog");
                    String selected = fault;
                    if (write) {
                        fault = "none";
                        attempts.setText(Integer.toString(++writes));
                        if (selected.equals("failed")) return new SaveResult.Failed<>();
                        if (selected.equals("invalid")) return new SaveResult.Invalid<>(java.util.List.of("private diagnostic"));
                        if (selected.equals("unknown-before")) return new SaveResult.OutcomeUnknown<>();
                    }
                    try {
                        Object result = method.invoke(TestApplication.store, args);
                        return write && selected.equals("unknown-after") ? new SaveResult.OutcomeUnknown<>() : result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
        add(new BudgetWorkspace(new BudgetService(port, () -> session.getAttribute(UUID.class)),
                Clock.fixed(Instant.parse("2026-10-31T22:30:00Z"), ZoneOffset.UTC)));
        add(new RouterLink("Другая страница", OtherView.class), attempts, reads);
        for (String mode : new String[]{"failed", "invalid", "unknown-before", "unknown-after", "missing", "read"}) {
            Button button = new Button("Test " + mode, e -> fault = mode);
            button.setId("test-" + mode);
            add(button);
        }
    }
}
