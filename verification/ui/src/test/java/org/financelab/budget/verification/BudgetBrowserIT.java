package org.financelab.budget.verification;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class BudgetBrowserIT {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    List<String> errors;
    static final String URL = System.getenv("BUDGET_UI_URL");

    @BeforeAll static void launch() {
        assertNotNull(URL, "Run scripts/verify-ui.sh");
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }
    @AfterAll static void stop() { if (browser != null) browser.close(); if (playwright != null) playwright.close(); }
    @BeforeEach void session() {
        context = browser.newContext(new Browser.NewContextOptions().setLocale("en-US").setTimezoneId("Pacific/Honolulu"));
        page = context.newPage();
        errors = new ArrayList<>();
        page.onPageError(errors::add);
        page.navigate(URL);
        assertThat(page.locator("h2:not([slot='title'])")).hasText("ноябрь 2026");
    }
    @AfterEach void close(TestInfo info) {
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/screenshots", info.getTestMethod().orElseThrow().getName() + ".png")).setFullPage(true));
        context.close();
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private Locator button(String text) { return button(page, text); }
    private static Locator button(Page target, String text) {
        return target.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(text).setExact(true));
    }
    private Locator income() { return page.locator("#income input"); }
    private Locator spending(int index) { return page.locator("vaadin-text-field[id^='spending-'] input").nth(index); }
    private Locator categories() { return page.locator("vaadin-vertical-layout[id^='category-']"); }
    private void dialogClosed() { assertThat(page.locator("vaadin-dialog-overlay[opened]")).hasCount(0); }
    private void save() { page.locator("#save").click(); state("saved"); }
    private void state(String state) { assertThat(page.locator("#save-status")).hasAttribute("data-state", state); }
    private void reloadStored() {
        page.locator("#reload").click();
        button("Заменить черновик").click();
        state("ready");
    }

    @Test void explicitSaveReloadHistoryAndRussianAmounts() {
        income().fill("1,234");
        page.locator("#save").click();
        state("invalid");
        assertThat(income()).hasValue("1,234");
        assertThat(page.locator("#totals")).containsText("Итоги недоступны");
        assertThat(page.locator("#write-attempts")).hasText("0");
        income().fill("10 000,50");
        spending(0).fill("1.25");
        spending(1).fill("0");
        page.locator("#investment input").fill("100");
        assertThat(page.locator("#totals")).containsText("Остаток: 9 899,25 ₽");
        assertThat(page.locator("#write-attempts")).hasText("0");
        page.locator("#save").focus();
        page.keyboard().press("Enter");
        state("saved");
        page.reload();
        assertThat(income()).hasValue("10 000,50");
        assertThat(spending(1)).hasValue("0,00");
        assertThat(spending(2)).hasValue("");
        page.locator("#save").click();
        state("unchanged");
        income().fill("12,00");
        save();
        assertThat(page.locator("#totals")).containsText("Остаток: -89,25 ₽");
        button("История").click();
        assertThat(page.locator("vaadin-button[id^='revision-']")).hasCount(2);
        assertThat(page.locator("#revision-1")).containsText("Europe/Moscow");
        page.locator("#revision-1").click();
        assertThat(page.locator("#revision-detail")).containsText("Доход: 10 000,50 ₽");
        assertThat(page.locator("#revision-detail")).containsText("Продукты: 0,00 ₽");
        assertThat(page.locator("#revision-detail")).containsText("Рестораны и доставка еды: Не задано");
        assertThat(page.locator("#revision-detail input")).hasCount(0);
        page.locator("#test-missing").click();
        page.locator("#revision-1").click();
        assertThat(page.locator("#revision-detail")).hasText("Версия не найдена.");
    }

    @Test void categoriesRetainSavedArchivedLinesAndOriginalHistoryAcrossMonths() {
        spending(0).fill("100");
        spending(1).fill("0");
        save();
        button("Настройки").click();
        String firstId = categories().nth(0).getAttribute("id").substring("category-".length());
        categories().nth(0).locator("input").fill("Дом");
        categories().nth(0).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Переместить категорию 1 ниже")).click();
        assertThat(categories().nth(1)).hasAttribute("id", "category-" + firstId);
        categories().nth(1).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Архивировать категорию 2")).click();
        assertThat(categories().nth(1)).containsText("Категория 2 (в архиве)");
        categories().nth(0).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Архивировать категорию 1")).click();
        assertThat(categories().nth(0)).containsText("Категория 1 (в архиве)");
        categories().nth(2).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Архивировать категорию 3")).click();
        assertThat(categories().nth(2)).containsText("Категория 3 (в архиве)");
        page.locator("#add-category").click();
        page.locator("#save").click();
        state("invalid");
        categories().last().locator("input").fill("Путешествия");
        save();
        page.locator("#save").click();
        state("unchanged");
        button("План месяца").click();
        assertThat(page.locator("#spending-" + firstId + " input")).hasValue("100,00");
        assertThat(page.locator("#spending-" + firstId)).containsText("Дом (в архиве)");
        assertThat(spending(0)).hasValue("0,00");
        assertThat(spending(2)).hasValue("");
        assertThat(page.locator("#totals")).containsText("Расходы: 100,00 ₽");
        button("История").click();
        assertThat(page.locator("vaadin-button[id^='revision-']")).hasCount(1);
        page.locator("#revision-1").click();
        assertThat(page.locator("#revision-detail")).containsText("Жильё и коммунальные услуги: 100,00 ₽");
        assertThat(page.locator("#revision-detail")).not().containsText("Путешествия");
        button("План месяца").click();
        save(); // Materialize only the new active line on an explicit monthly save.
        button("Следующий месяц").click();
        assertThat(page.locator("h2:not([slot='title'])")).hasText("декабрь 2026");
        assertThat(page.locator("vaadin-text-field[id^='spending-']")).hasCount(8);
        assertThat(page.locator("#spending-" + firstId)).hasCount(0);
        assertThat(income()).hasValue("");
        button("Текущий месяц").click();
        assertThat(page.locator("#spending-" + firstId + " input")).hasValue("100,00");
    }

    @Test void dirtyEditsRequireSaveDiscardOrCancelForMonthsSettingsAndRoutes() {
        income().fill("7,");
        button("Следующий месяц").click();
        button("Отмена").click();
        dialogClosed();
        assertThat(income()).hasValue("7,");
        button("Настройки").click();
        button("Сохранить и перейти").click();
        state("invalid");
        dialogClosed();
        assertThat(income()).hasValue("7,");
        income().fill("7");
        button("Следующий месяц").click();
        button("Сохранить и перейти").click();
        assertThat(page.locator("h2:not([slot='title'])")).hasText("декабрь 2026");
        dialogClosed();
        button("Предыдущий месяц").click();
        assertThat(income()).hasValue("7,00");
        income().fill("8");
        page.getByText("Другая страница", new Page.GetByTextOptions().setExact(true)).click();
        assertThat(button("Не сохранять")).isVisible();
        page.keyboard().press("Escape");
        dialogClosed();
        assertThat(income()).hasValue("8");
        page.getByText("Другая страница", new Page.GetByTextOptions().setExact(true)).click();
        button("Не сохранять").click();
        assertThat(page.getByText("Назад")).isVisible();
        page.getByText("Назад").click();
        assertThat(income()).hasValue("7,00");
        button("Настройки").click();
        categories().first().locator("input").fill("Черновик");
        button("История").click();
        button("Отмена").click();
        assertThat(categories().first().locator("input")).hasValue("Черновик");
        button("План месяца").click();
        button("Не сохранять").click();
        assertThat(page.locator("h2:not([slot='title'])")).hasText("ноябрь 2026");
    }

    @Test void staleBudgetAndCatalogTabsKeepTheirDraftsWithoutRetry() {
        Page second = context.newPage();
        second.navigate(URL);
        assertThat(second.locator("#income input")).hasValue("");
        income().fill("100");
        save();
        second.locator("#income input").fill("200");
        second.locator("#save").click();
        assertThat(second.locator("#save-status")).hasAttribute("data-state", "conflict");
        assertThat(second.locator("#income input")).hasValue("200");
        assertThat(second.locator("#save")).isDisabled();
        assertThat(second.locator("#write-attempts")).hasText("1");
        second.locator("#reload").click();
        button(second, "Заменить черновик").click();
        assertThat(second.locator("#income input")).hasValue("100,00");
        button(second, "Настройки").click();
        button("Настройки").click();
        categories().first().locator("input").fill("Первое имя");
        save();
        second.locator("vaadin-text-field[id^='label-'] input").first().fill("Другое имя");
        second.locator("#save").click();
        assertThat(second.locator("#save-status")).hasAttribute("data-state", "conflict");
        assertThat(second.locator("vaadin-text-field[id^='label-'] input").first()).hasValue("Другое имя");
        button("План месяца").click();
        income().fill("300");
        second.locator("#reload").click();
        button(second, "Заменить черновик").click();
        assertThat(second.locator("#save-status")).hasAttribute("data-state", "ready");
        assertThat(second.locator("vaadin-text-field[id^='label-'] input").first()).hasValue("Первое имя");
        second.locator("vaadin-text-field[id^='label-'] input").first().fill("Третье имя");
        second.locator("#save").click();
        assertThat(second.locator("#save-status")).hasAttribute("data-state", "saved");
        page.locator("#save").click();
        state("conflict");
        assertThat(page.locator("#save-status")).containsText("Категории изменены");
        assertThat(income()).hasValue("300");
    }

    @Test void failureInvalidAndUnconfirmedCommitPreserveDraftAndRequireReadBeforeSave() {
        income().fill("10");
        page.locator("#test-failed").click();
        page.locator("#save").click();
        state("failed");
        assertThat(income()).hasValue("10");
        assertThat(page.locator("#save")).isEnabled();
        page.locator("#test-invalid").click();
        page.locator("#save").click();
        state("invalid");
        assertThat(page.locator("#save-status")).not().containsText("private diagnostic");
        page.locator("#test-unknown-after").click();
        page.locator("#save").click();
        state("unknown");
        assertThat(income()).hasValue("10");
        assertThat(page.locator("#save")).isDisabled();
        assertThat(page.locator("#write-attempts")).hasText("3");
        page.locator("#test-read").click();
        page.locator("#reload").click();
        button("Заменить черновик").click();
        state("read-failed");
        button("Отмена").click();
        assertThat(income()).hasValue("10");
        assertThat(page.locator("#save")).isDisabled();
        reloadStored();
        assertThat(income()).hasValue("10,00");
        assertThat(page.locator("#history-reads")).not().hasText("0");
        assertThat(page.locator("#write-attempts")).hasText("3");
        page.locator("#save").click();
        state("unchanged");
        income().fill("20");
        page.locator("#test-unknown-before").click();
        page.locator("#save").click();
        state("unknown");
        reloadStored();
        assertThat(income()).hasValue("10,00");
        button("История").click();
        assertThat(page.locator("vaadin-button[id^='revision-']")).hasCount(1);
    }

    @Test void unconfirmedCategorySaveUsesExplicitReloadAndNoAutomaticRetry() {
        button("Настройки").click();
        categories().first().locator("input").fill("Новое имя");
        page.locator("#test-unknown-after").click();
        page.locator("#save").click();
        state("unknown");
        assertThat(categories().first().locator("input")).hasValue("Новое имя");
        assertThat(page.locator("#write-attempts")).hasText("1");
        reloadStored();
        page.locator("#save").click();
        state("unchanged");
    }

    @Test void browserRefreshWarnsAndMobileKeyboardRemainsUsable() {
        page.setViewportSize(390, 844);
        income().focus();
        page.keyboard().type("25,50");
        page.keyboard().press("Tab");
        assertThat(spending(0)).isFocused();
        AtomicBoolean warned = new AtomicBoolean();
        page.onceDialog(dialog -> { warned.set(dialog.type().equals("beforeunload")); dialog.dismiss(); });
        try { page.reload(new Page.ReloadOptions().setTimeout(5000)); } catch (PlaywrightException expected) { /* Browser cancels navigation. */ }
        assertTrue(warned.get());
        assertThat(income()).hasValue("25,50");
        page.locator("#save").focus();
        page.keyboard().press("Enter");
        state("saved");
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
        button("Настройки").click();
        page.locator("#add-category").focus();
        page.keyboard().press("Enter");
        assertThat(categories().last().locator("input")).isFocused();
        page.keyboard().type("Книги");
        save();
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
    }

    @Test void separateServerSessionsCannotSelectAnotherOwnerViaUrl() {
        income().fill("123");
        save();
        try (var isolated = browser.newContext()) {
            Page other = isolated.newPage();
            other.navigate(URL + "?owner=00000000-0000-0000-0000-000000000001");
            assertThat(other.locator("#income input")).hasValue("");
            button(other, "История").click();
            assertThat(other.getByText("Сохранённых версий пока нет.")).isVisible();
        }
    }
}
