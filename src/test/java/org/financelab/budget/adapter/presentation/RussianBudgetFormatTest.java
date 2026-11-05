package org.financelab.budget.adapter.presentation;

import org.financelab.budget.domain.BudgetAmount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.TimeZone;

import static org.financelab.budget.adapter.presentation.RussianBudgetFormat.*;
import static org.junit.jupiter.api.Assertions.*;

class RussianBudgetFormatTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0|0.00
            0,0|0.00
            0.00|0.00
            000|0.00
            000 000,00|0.00
            1234|1234.00
            1 234|1234.00
            1234,5|1234.50
            1234.5|1234.50
            1 234,56|1234.56
            1 234.56|1234.56
            1 234,56|1234.56
            1 234.56|1234.56
            001234,50|1234.50
            001234.50|1234.50
            001 234,50|1234.50
            001 234.50|1234.50
            ,50|0.50
            .50|0.50
            ,5|0.50
            .5|0.50
            ,00|0.00
            .0|0.00
            1 234 567 890,12|1234567890.12
            99999999999999999.99|99999999999999999.99
            99 999 999 999 999 999,99|99999999999999999.99
            """)
    void acceptsExactMoneyContract(String input, String expected) {
        assertEquals(BudgetAmount.set(new BigDecimal(expected)), parseAmount(input));
        assertEquals(BudgetAmount.State.SET, parseAmount(input).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " ", " ", "    "})
    void onlyEmptyOrAcceptedSpacesAreUnset(String input) {
        assertEquals(BudgetAmount.UNSET, parseAmount(input));
        assertEquals("", formatAmount(parseAmount(input)));
    }

    @Test void trimsOnlyTheAcceptedSpaces() {
        assertEquals(new BigDecimal("1234.56"), parseAmount("   1 234,56   ").contribution());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-100", "+100", "1e3", "1E3", "100 ₽", "₽100", "100abc", "NaN", "Infinity",
            "12 34", "12 34,56", "0001 234,50", "1234 567", "1  234", "1  234", "1,2 3", "1 ,23",
            "1,000", "1.000", ",500", ".500", "1,2.3", "1.2.3", "1,2,3", "1,", "1.", ",", ".",
            "\t", "\n", "\r", "\t1", "1\n", "1\r", "1\t234", "1\n234", "1\r\n234", "\u20091", "1\u2009234",
            "١٢", "１２", "100000000000000000", "100000000000000000.00", "99999999999999999.999"})
    void rejectsInvalidAndIncompleteMoneyWithoutRounding(String input) {
        assertThrows(IllegalArgumentException.class, () -> parseAmount(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.11.2026", "04.1.2026", "04/11/26", "04/11/2026", "04.11.26", "31.02.2026",
            "29.02.2026", "00.11.2026", "04.00.2026", "04.13.2026", "04.11.2026 ", " 04.11.2026", "04.11.2026\n",
            "０４.１１.２０２６", "01.01.10000", ""})
    void rejectsNonContractOrImpossibleDates(String input) {
        assertThrows(IllegalArgumentException.class, () -> parseDate(input));
    }

    @Test void russianPresentationIsIndependentOfMachineDefaults() {
        Locale oldLocale = Locale.getDefault();
        TimeZone oldZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            assertEquals("1 234,56 ₽", formatAmount(parseAmount("1234.56")));
            assertEquals("0,00 ₽", formatAmount(parseAmount("0")));
            assertEquals("-1 234,56 ₽", formatMoney(new BigDecimal("-1234.56")));
            assertEquals("199 999 999 999 999 999,98 ₽", formatMoney(BudgetAmount.MAX_VALUE.multiply(BigDecimal.TWO)));
            assertThrows(IllegalArgumentException.class, () -> formatMoney(new BigDecimal("1.001")));
            assertEquals(LocalDate.of(2026, 11, 4), parseDate("04.11.2026"));
            assertEquals(LocalDate.of(2024, 2, 29), parseDate("29.02.2024"));
            assertEquals("04.11.2026", formatDate(LocalDate.of(2026, 11, 4)));
            var month = YearMonth.of(2026, 11);
            assertEquals("ноябрь 2026", formatMonth(month));
            assertEquals("01.11.2026 00:30:00 Europe/Moscow", formatHistoryTime(Instant.parse("2026-10-31T21:30:00Z")));
            assertEquals(YearMonth.of(2026, 11), month);
        } finally {
            Locale.setDefault(oldLocale);
            TimeZone.setDefault(oldZone);
        }
    }
}
