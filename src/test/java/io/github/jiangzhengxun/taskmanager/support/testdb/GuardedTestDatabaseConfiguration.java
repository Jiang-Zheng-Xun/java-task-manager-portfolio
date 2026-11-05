package io.github.jiangzhengxun.taskmanager.support.testdb;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides the single controlled {@link GuardedFlywayMigrationStrategy}. {@link TestDatabaseBeanDefinitionGuard}
 * accepts exactly this factory method by declaring class, name and concrete return type; keep it the only method here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GuardedTestDatabaseConfiguration {

    @Bean
    GuardedFlywayMigrationStrategy guardedFlywayMigrationStrategy(ConfigurableListableBeanFactory beanFactory) {
        // The identity policy is this context's snapshot from GuardedTestDatabaseInitializer; neither the mode nor the
        // private policy file is read again, and nothing comes from datasource settings.
        return new GuardedFlywayMigrationStrategy(GuardedTestDatabaseInitializer.policySnapshot(beanFactory).identity());
    }
}
