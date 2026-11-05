package org.financelab.budget.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BudgetAmountTest {
    @Test void distinguishesUnsetFromZeroAndNormalizesExactValues() {
        var zero = BudgetAmount.set(BigDecimal.ZERO);
        assertEquals(BudgetAmount.State.UNSET, BudgetAmount.UNSET.state());
        assertEquals(Optional.empty(), BudgetAmount.UNSET.value());
        assertEquals(BudgetAmount.State.SET, zero.state());
        assertNotEquals(zero, BudgetAmount.UNSET);
        assertEquals(new BigDecimal("0.00"), BudgetAmount.UNSET.contribution());
        assertEquals(zero.contribution(), BudgetAmount.UNSET.contribution());
        assertEquals(BudgetAmount.set(new BigDecimal("1.20")), BudgetAmount.set(new BigDecimal("1.2")));
        assertEquals(new BigDecimal("1000.00"), BudgetAmount.set(new BigDecimal("1E+3")).contribution());
        assertEquals(BudgetAmount.MAX_VALUE, BudgetAmount.set(BudgetAmount.MAX_VALUE).contribution());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "0.001", "1.000", "0.000", "99999999999999999.999", "100000000000000000"})
    void allTypedEntryPointsRejectExcessScaleAndRange(String input) {
        assertThrows(IllegalArgumentException.class, () -> BudgetAmount.set(new BigDecimal(input)));
        assertThrows(IllegalArgumentException.class, () -> new BudgetAmount(Optional.of(new BigDecimal(input))));
    }
}
