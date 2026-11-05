package org.financelab.budget.verification;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.financelab.budget.adapter.postgres.PostgresBudgetStore;
import org.financelab.orm.JdbcTransactions;
import org.flywaydb.core.Flyway;

/** Test WAR only: disposable database and synthetic server-session identities, never a release entry point. */
public final class TestApplication implements ServletContextListener {
    static PostgresBudgetStore store;
    private HikariDataSource pool;

    @Override public void contextInitialized(ServletContextEvent event) {
        if (!"true".equals(System.getenv("BUDGET_UI_TEST"))) throw new IllegalStateException("Test mode required");
        String url = System.getenv("BUDGET_TEST_URL");
        String user = System.getenv("BUDGET_TEST_USER");
        Flyway.configure().dataSource(url, user, "").locations("classpath:db/migration").load().migrate();
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(5000);
        pool = new HikariDataSource(config);
        store = new PostgresBudgetStore(new JdbcTransactions(pool), error -> System.err.println("Synthetic test database failure"));
        store.verifyMappings();
    }

    @Override public void contextDestroyed(ServletContextEvent event) { if (pool != null) pool.close(); }
}
