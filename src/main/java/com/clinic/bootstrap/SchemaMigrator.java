package com.clinic.bootstrap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionManagement;
import jakarta.ejb.TransactionManagementType;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the Flyway migrations before anything else touches the database.
 *
 * <p>Migrations execute outside container transactions so Flyway can manage its
 * own locking; the demo seeder depends on this bean so ordering is guaranteed.</p>
 */
@Singleton
@Startup
@PermitAll
@TransactionManagement(TransactionManagementType.BEAN)
public class SchemaMigrator {

    private static final Logger LOG = Logger.getLogger(SchemaMigrator.class.getName());

    @Resource(lookup = "java:jboss/datasources/ClinicDS")
    private DataSource dataSource;

    private boolean migrated;

    @PostConstruct
    void migrate() {
        LOG.info("Applying database migrations");
        Flyway flyway = Flyway.configure(SchemaMigrator.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
        var result = flyway.migrate();
        migrated = true;
        LOG.log(Level.INFO, "Migrations applied: {0}", result.migrationsExecuted);
    }

    public boolean isMigrated() {
        return migrated;
    }
}
