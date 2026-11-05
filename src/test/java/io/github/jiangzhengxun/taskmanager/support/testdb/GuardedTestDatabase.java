package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * Applies the shared database guard to a Spring test: settings check, bean structure check, database identity check,
 * then migration.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ContextConfiguration(initializers = GuardedTestDatabaseInitializer.class)
@Import(GuardedTestDatabaseConfiguration.class)
public @interface GuardedTestDatabase {
}
