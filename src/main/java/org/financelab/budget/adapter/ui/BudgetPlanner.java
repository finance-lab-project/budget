package org.financelab.budget.adapter.ui;

import org.financelab.budget.domain.CategoryCatalog;

/** The month planner and its import and local-data controls, embedded in the host application shell. */
public final class BudgetPlanner {
    private BudgetPlanner() { }

    public static String transfer(String csrfToken) {
        return """
                <main class="reading"><h1>Из браузера в аккаунт</h1>
                <p>Перенесите сохранённые месяцы, чтобы открыть их на другом устройстве.
                Для нового месяца будут перенесены и суммы прошлых версий. История аккаунта отметит время переноса.
                Для существующего месяца вы сможете сравнить текущие суммы; прежние версии останутся в своих хранилищах.</p>
                <ul hidden id="starter-labels">%s</ul><div id="transfer"><input type="hidden" id="csrf" value="%s">
                <section id="catalog-transfer" hidden><h2>Категории из браузера</h2><div id="catalog-review"></div>
                <button class="secondary" type="button" id="copy-categories">Перенести категории</button></section>
                <div id="months"></div><div class="actions"><button class="primary" id="send" hidden>Перенести месяцы</button>
                <a href="./">Открыть мой бюджет</a></div><p role="status" id="status"></p></div></main>""".formatted(starterList(), escape(csrfToken));
    }

    public static String localMonthControls() {
        return """
                <div class="actions"><label>Локальный месяц<select id="local-months"></select></label>
                <button type="button" id="delete-local" class="subtle" disabled>Удалить месяц из браузера</button></div>
                <p role="status" id="local-status"></p>""";
    }

    private static String starterList() {
        var html = new StringBuilder();
        for (String label : CategoryCatalog.starterLabels()) {
            html.append("<li>").append(escape(label)).append("</li>");
        }
        return html.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String month(boolean cloud) {
        return """
                <main class="planner" id="planner" data-mode="%s"><div class="page-title"><div><h1>Бюджет на месяц</h1>
                <p id="storage-mode">%s</p></div><div class="date-controls">
                <label class="month">Месяц<select id="month"></select></label>
                <label class="month">Год<input id="year" type="number" min="1" max="9999" required></label></div></div>
                <nav class="planner-nav" aria-label="Разделы бюджета">
                <button type="button" data-panel="month" aria-current="page">План месяца</button>
                <button type="button" data-panel="catalog">Категории</button>
                <button type="button" data-panel="history">История</button></nav>
                <noscript>Для работы с планировщиком включите JavaScript.</noscript>
                <ul hidden id="starter-labels">%s</ul>
                <div id="month-panel" class="planner-grid"><aside class="summary" aria-labelledby="balance-label">
                <h2 id="balance-label">Остаток</h2><output id="balance">—</output>
                <dl><dt>Доход</dt><dd id="income-total">—</dd><dt>Инвестиции</dt><dd id="investment-total">—</dd>
                <dt>Расходы</dt><dd id="spending-total">—</dd></dl></aside>
                <form id="plan" class="surface" novalidate><section aria-labelledby="income-heading">
                <h2 id="income-heading">Доход</h2>%s</section><section aria-labelledby="investment-heading">
                <h2 id="investment-heading">Инвестиции</h2>%s</section><section aria-labelledby="spending-heading">
                <h2 id="spending-heading">Расходы</h2><div id="spending" class="field-grid"></div></section>
                <div class="actions"><button id="save" class="primary" type="submit" disabled>Сохранить</button>
                <button id="clear" type="button" class="subtle">Очистить суммы</button></div></form></div>
                <section id="catalog-panel" class="surface" hidden><h2>Категории расходов</h2>
                <p class="helper">Порядок и названия общие для всех месяцев. Архивные строки останутся в сохранённых планах.</p>
                <div id="categories"></div><div class="actions"><button class="secondary" id="add-category" type="button">Добавить категорию</button>
                <button class="primary" id="save-catalog" type="button">Сохранить категории</button></div></section>
                <section id="history-panel" class="surface" hidden><h2>История месяца</h2>
                <label class="month">Сохранённая версия<select id="revision"></select></label>
                <div id="revision-detail"></div></section>
                <p id="status" role="status" aria-live="polite"></p>
                <button type="button" id="reload-plan" class="secondary" hidden>Загрузить сохранённые данные</button>
                <dialog id="leave-dialog"><h2>Несохранённые изменения</h2><p>Сохранить изменения перед переходом?</p>
                <form method="dialog" class="actions"><button value="stay" class="secondary" autofocus>Остаться</button>
                <button value="discard" class="subtle">Не сохранять</button><button value="save" class="primary">Сохранить</button></form></dialog></main>"""
                .formatted(cloud ? "cloud" : "local", cloud ? "Сохранение в аккаунте" : "Сохранение в этом браузере",
                        starterList(), amount("income", "Сумма дохода"), amount("investment", "Сумма инвестиций"));
    }

    private static String amount(String id, String label) {
        return """
                <label class="amount" for="%1$s">%2$s<span class="amount-box">
                <input id="%1$s" name="%1$s" type="text" inputmode="decimal" autocomplete="off" maxlength="80"
                aria-describedby="%1$s-error"><span aria-hidden="true">₽</span></span>
                <small id="%1$s-error"></small></label>""".formatted(id, escape(label));
    }
}
