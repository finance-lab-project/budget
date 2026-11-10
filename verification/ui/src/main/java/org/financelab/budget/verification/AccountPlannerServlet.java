package org.financelab.budget.verification;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.financelab.budget.adapter.ui.BudgetPlanner;
import org.financelab.budget.adapter.ui.PlannerCommands;
import org.financelab.budget.application.BudgetService;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Synthetic owner fixture for the packaged shared planner and command adapter. */
public final class AccountPlannerServlet extends HttpServlet {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        service(request);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
                <!doctype html><html lang="ru"><meta charset="utf-8"><title>Бюджет</title>
                <body>%s<script type="module">
                import {startPlanner} from './ui/local.js';
                window.plannerStorage = async (action, data) => (await fetch('./account', {
                  method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({action,data})
                })).json();
                startPlanner(document, (action, data) => window.plannerStorage(action, data));
                </script></body></html>
                """, BudgetPlanner.month(true));
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var data = JSON.readTree(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(PlannerCommands.execute(service(request), data.path("action").asString(), data.path("data").toString()));
    }

    private BudgetService service(HttpServletRequest request) {
        var session = request.getSession();
        UUID owner = (UUID) session.getAttribute("planner-owner");
        if (owner == null) {
            owner = UUID.randomUUID();
            session.setAttribute("planner-owner", owner);
            TestApplication.store.initializeCatalog(owner);
        }
        return new BudgetService(TestApplication.store, () -> (UUID) session.getAttribute("planner-owner"));
    }
}
