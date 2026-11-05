package org.financelab.budget.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** An entered RUB amount, or an explicit absence. Derived totals use BigDecimal directly. */
public record BudgetAmount(Optional<BigDecimal> value) {
    public enum State { SET, UNSET }

    public static final BigDecimal MAX_VALUE = new BigDecimal("99999999999999999.99");
    public static final BudgetAmount UNSET = new BudgetAmount(Optional.empty());

    public BudgetAmount {
        Objects.requireNonNull(value, "value");
        value = value.map(amount -> {
            if (amount.scale() > 2 || amount.signum() < 0 || amount.compareTo(MAX_VALUE) > 0) {
                throw new IllegalArgumentException("Amount must have at most two decimal places and be within the RUB input range");
            }
            return amount.setScale(2);
        });
    }

    public static BudgetAmount set(BigDecimal value) {
        return new BudgetAmount(Optional.of(value));
    }

    public State state() {
        return value.isPresent() ? State.SET : State.UNSET;
    }

    public BigDecimal contribution() {
        return value.orElse(new BigDecimal("0.00"));
    }
}
