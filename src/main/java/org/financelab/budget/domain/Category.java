package org.financelab.budget.domain;

import java.util.Objects;
import java.util.UUID;

public record Category(UUID id, UUID ownerId, String label, int position, Status status) {
    public enum Status { ACTIVE, ARCHIVED }

    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(status, "status");
        // Match the reviewed JavaScript trim policy, including NBSP, narrow NBSP and BOM.
        label = label.replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (label.isEmpty()) throw new IllegalArgumentException("Category label must not be blank");
        if (position < 1) throw new IllegalArgumentException("Category position must be positive");
    }
}
