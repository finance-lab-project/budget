# Russian Budget UI

Implements [issue #4](https://github.com/finance-lab-project/budget/issues/4)
as a module-owned Vaadin component, using the application assembly's pinned
Vaadin 25.3.5. The library does not register an anonymous route or own a servlet,
database connection, migration runner, pool, login flow or application lifecycle.

## Shared guest and account planner

`BudgetPlanner.month(false)` and `BudgetPlanner.month(true)` render the same
month, category and history controls. The guest adapter saves to browser storage.
The account route passes its authenticated `BudgetService` to
`PlannerCommands.execute`. The module JAR serves the planner scripts and
stylesheet from `META-INF/resources/ui/`. The host supplies the page shell,
account action, routes, session and request protection.

`BudgetPlanner.transfer` renders the import form. After validating the request
and its CSRF token, the host passes its form parameters and authenticated service
to `PlannerTransfer.submit`. A new account month can replay earlier guest
amounts in order. An existing account month requires review before different
amounts are written. Blank guest fields preserve existing amounts. Original
dated snapshots stay in the browser when it has more than one version.

## Vaadin component integration

After migrations, mapping validation and catalog initialization described in
[persistence.md](persistence.md), the root constructs a **new** `BudgetService`
for the authenticated UI/session and embeds `BudgetWorkspace` in its protected
route. Supply the identity resolver from the trusted server authentication
context, never from a URL, field, client storage or request parameter:

```java
var service = new BudgetService(budgetStore, authenticatedSession::ownerId);
add(new BudgetWorkspace(service));
```

The resolver must return no identity when the session expires or logs out.
The service requires an authenticated owner at construction, rechecks it on every
operation, and rejects identity changes rather than switching an existing editor
to another owner's data. The persistence API still scopes every query and write.
The root remains responsible for route authentication, session expiry/logout and
composition, as defined by the canonical architecture. The supplied constructor
with a `Clock` supports deterministic UI tests; the normal constructor selects
the current month in `Europe/Moscow`.

## Editing and navigation

- The month planner shows Russian labels, RUB suffixes, exact derived spending
  and remaining, and previous/current/next month controls. Text input uses the
  existing comma/dot parser and preserves invalid and incomplete strings.
  Blank and numeric zero remain distinct. Invalid amounts suppress totals until
  corrected; negative derived remaining is allowed.
- Typing never writes. Explicit Save passes the complete typed draft and the
  loaded Budget/catalog versions. Saved and Unchanged acknowledge the result
  without an additional read that could misreport a confirmed commit as failed.
- Validation and known failures keep the draft available. Budget/catalog
  conflicts and unconfirmed saves retain the draft and disable further writes
  until the user explicitly loads server state. Loading asks before replacing
  edits; a failed read leaves the draft intact. An unconfirmed monthly save also
  rereads revision history. No writes are retried automatically, and matching
  stored values are not described as proof of which request committed.
- Switching months, Settings, History or routes with dirty edits offers Save,
  Discard and Cancel. A failed save stays on the current editor. Browser refresh,
  external navigation and tab close use the browser's native unsaved-change
  warning: browsers do not allow custom three-button dialogs there. Drafts are
  memory-only and are lost if the user confirms leaving or the session ends.
- Settings provides editable labels, add, keyboard-accessible up/down ordering
  and archive buttons, followed by an explicit catalog Save. Identities remain
  stable, and archiving does not remove saved monthly lines or their amounts.
  Saved archived zero/unset lines remain visible and editable. New months omit
  archived categories; adding an active category persists its monthly line only
  on the next explicit monthly Save.
- History lists only committed versions. Detail uses the service's pinned
  historical catalog, labels/order/status and exact line membership. It is
  read-only, distinguishes unset from zero and never substitutes current state
  for a missing revision. Dates use Russian presentation with an explicit
  `Europe/Moscow` zone, independently of browser/JVM time zones.

## Verification

With Java 27.0.1.0.3, Maven 3.10.0, Node 24.22.2, Jetty 12.1.14 and Docker running:

```sh
# Domain/service and real PostgreSQL integration tests:
scripts/verify.sh
# Set JAVA_HOME and JETTY_HOME to the pinned installations first:
scripts/verify-ui.sh
git diff --check
```

The UI verifier builds the pinned ORM dependency, installs the current Budget
library locally, runs its unit tests, then packages a production-mode test WAR
from `verification/ui`. It starts disposable PostgreSQL 18.6 and loopback-only
Jetty, and runs Java Playwright Chromium tests. The test WAR supplies synthetic
server-generated session identities and explicit fault-injection controls; these
classes and routes are **not** included in the Budget library or root application.
It requires `BUDGET_UI_TEST=true`, which the verifier sets only for this process.
Use this harness only with disposable data. It is not a deployable login flow.

Browser checks cover explicit save/reload/history, no-op history, Russian money
and time-zone-independent month selection, invalid drafts, zero/unset/negative
remaining, category changes across months, original history presentation,
Budget and catalog conflicts, known failures and unknown outcomes before/after
commit, failed reload, save/discard/cancel, route cancellation, browser refresh,
keyboard use, a 390-pixel viewport and separate-owner sessions. Service tests
verify missing/changed identities cannot reach the persistence port. Browser
reports and screenshots go to `verification/ui/target`; runtime logs stay under
`target`. These generated files contain only synthetic data and are not committed.

The harness does not prove production authentication, root artifact publication,
online deployment or cloud readiness. Those remain root/release work. Observed
test counts and completed results belong in the implementing PR.
