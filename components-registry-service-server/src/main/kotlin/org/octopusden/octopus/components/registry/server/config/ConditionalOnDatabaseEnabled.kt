package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * SYS-047: marks a bean as part of the database layer. Such beans are present in
 * the default (db) mode and dropped from the context when the `no-db` profile
 * sets `components-registry.database.enabled=false` (see application-no-db.yml).
 *
 * `matchIfMissing = true` is the contract that keeps db-mode (id17) untouched:
 * with the flag unset, every annotated bean loads exactly as before.
 *
 * Put this on any bean that transitively requires a [javax.sql.DataSource] —
 * i.e. injects a Spring Data JPA repository, a `TransactionTemplate`, an
 * `EntityManager`, or another bean so annotated. The no-db context-load test
 * (`NoDbModeContextTest`) is the completeness backstop: if a DB bean is left
 * un-annotated, the no-db context fails to start and the test goes red.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(
    name = ["components-registry.database.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
annotation class ConditionalOnDatabaseEnabled
