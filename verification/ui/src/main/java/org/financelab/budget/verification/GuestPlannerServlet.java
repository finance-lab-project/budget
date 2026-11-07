package org.financelab.budget.verification;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.financelab.budget.adapter.ui.BudgetPlanner;

import java.io.IOException;

/** Standalone guest fixture using the UI and resources from the packaged Budget JAR. */
public final class GuestPlannerServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
                <!doctype html><html lang="ru"><meta charset="utf-8"><title>Бюджет</title>
                <a id="account-action" href="./">Войти</a>%s<script type="module" src="ui/local.js"></script></html>
                """, BudgetPlanner.month(false));
    }
}
