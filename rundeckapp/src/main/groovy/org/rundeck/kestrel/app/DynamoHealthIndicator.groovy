package org.rundeck.kestrel.app

import groovy.transform.CompileStatic
import org.rundeck.kestrel.gorm.dynamodb.DynamoDatastore
import org.springframework.boot.actuate.health.AbstractHealthIndicator
import org.springframework.boot.actuate.health.Health

/**
 * Readiness in DynamoDB storage mode (M2d). Registered under the name of upstream's datasource
 * indicator, {@code rundeckDataSourceHealthIndicator}, so the readiness group in application.yml
 * checks the database the app actually uses instead of the in-memory placeholder.
 */
@CompileStatic
class DynamoHealthIndicator extends AbstractHealthIndicator {
    DynamoDatastore datastore

    DynamoHealthIndicator() {
        super('DynamoDB health check failed')
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long start = System.nanoTime()
        datastore.ping()
        builder.up()
               .withDetail('database', 'DynamoDB')
               .withDetail('tablePrefix', datastore.tables.prefix)
               .withDetail('pingMillis', (System.nanoTime() - start).intdiv(1_000_000L))
    }
}
