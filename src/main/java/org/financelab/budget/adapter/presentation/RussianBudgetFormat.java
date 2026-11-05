package org.financelab.budget.adapter.presentation;

import org.financelab.budget.domain.BudgetAmount;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Presentation boundary shared by future UI/report adapters; no framework or persistence dependencies. */
public final class RussianBudgetFormat {
    public static final Locale LOCALE = Locale.forLanguageTag("ru-RU");
    public static final ZoneId HISTORY_ZONE = ZoneId.of("Europe/Moscow");
    private static final String SPACES = "[ \\u00A0\\u202F]";
    private static final Pattern MONEY = Pattern.compile(
            "(?:[0-9]+|[0-9]{1,3}(?:" + SPACES + "[0-9]{3})+)(?:[,.][0-9]{1,2})?|[,.][0-9]{1,2}");
    private static final Pattern DATE = Pattern.compile("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu", LOCALE)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL uuuu", LOCALE);
    private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss VV", LOCALE)
            .withZone(HISTORY_ZONE);

    private RussianBudgetFormat() { }

    public static BudgetAmount parseAmount(String input) {
        Objects.requireNonNull(input, "input");
        String trimmed = input.replaceAll("^" + SPACES + "+|" + SPACES + "+$", "");
        if (trimmed.isEmpty()) return BudgetAmount.UNSET;
        if (!MONEY.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Введите неотрицательную сумму с точностью до двух знаков после запятой");
        }
        String decimal = trimmed.replaceAll(SPACES, "").replace(',', '.');
        if (decimal.startsWith(".")) decimal = "0" + decimal;
        try {
            return BudgetAmount.set(new BigDecimal(decimal));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Сумма должна быть от 0,00 до 99 999 999 999 999 999,99 ₽", error);
        }
    }

    public static String formatAmount(BudgetAmount amount) {
        return amount.value().map(RussianBudgetFormat::formatMoney).orElse("");
    }

    /** Also formats negative/unbounded derived totals, without applying the entered-amount range. */
    public static String formatMoney(BigDecimal amount) {
        if (amount.scale() > 2) throw new IllegalArgumentException("Money display requires at most two decimal places");
        var symbols = DecimalFormatSymbols.getInstance(LOCALE);
        symbols.setGroupingSeparator('\u00A0');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", symbols).format(amount) + "\u00A0₽";
    }

    public static LocalDate parseDate(String input) {
        if (!DATE.matcher(input).matches()) throw new IllegalArgumentException("Введите дату в формате ДД.ММ.ГГГГ");
        try {
            return LocalDate.parse(input, DATE_FORMAT);
        } catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("Введите существующую календарную дату", error);
        }
    }

    public static String formatDate(LocalDate date) { return DATE_FORMAT.format(date); }
    public static String formatMonth(YearMonth month) { return MONTH_FORMAT.format(month); }
    public static String formatHistoryTime(Instant instant) { return HISTORY_FORMAT.format(instant); }
}
