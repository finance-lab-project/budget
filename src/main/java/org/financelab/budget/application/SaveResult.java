package org.financelab.budget.application;

import java.util.List;

/** Public command results contain no database exceptions, SQL or connection details. */
public sealed interface SaveResult<T> {
    record Saved<T>(T revision) implements SaveResult<T> { }
    record Unchanged<T>(T revision) implements SaveResult<T> { }
    record Invalid<T>(List<String> fields) implements SaveResult<T> {
        public Invalid { fields = List.copyOf(fields); }
    }
    enum Aggregate { BUDGET, CATALOG }
    record Conflict<T>(Aggregate aggregate) implements SaveResult<T> { }
    record Failed<T>() implements SaveResult<T> { }
    record OutcomeUnknown<T>() implements SaveResult<T> { }
}
