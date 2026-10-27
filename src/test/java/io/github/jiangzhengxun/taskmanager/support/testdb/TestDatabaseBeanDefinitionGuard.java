package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayConnectionDetails;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.core.type.MethodMetadata;

/**
 * Ordinary bean factory post-processor that accepts only the reviewed Boot auto-configuration structure: one Hikari
 * DataSource, Boot's property-based connection details and one Boot Flyway sharing that DataSource.
 *
 * <p>It inspects bean definitions and predicted types only and never requests bean instances, calls
 * {@link FactoryBean#getObject()} or {@link FactoryBean#getObjectType()}. Every local definition must first be
 * verifiable: FactoryBeans are rejected (except Boot's own configuration properties binder factory, matched by its
 * exact name, class and registration structure), and a definition whose predicted type is unresolved or a supertype of
 * a guarded type (for example {@code Object}) is rejected because it could produce a guarded type. Type prediction may load
 * classes; side effects of arbitrary custom factory implementations are not excluded. It runs after registry
 * processing; it cannot prevent side effects of arbitrary earlier custom code.
 */
public final class TestDatabaseBeanDefinitionGuard implements BeanFactoryPostProcessor {

    private static final String HIKARI_CONFIGURATION =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration$Hikari";
    private static final String POOLED_DATASOURCE_CONFIGURATION =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration$PooledDataSourceConfiguration";
    private static final String FLYWAY_CONFIGURATION =
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration$FlywayConfiguration";
    private static final Set<String> BOOT_FLYWAY_CUSTOMIZER_CONFIGURATIONS = Set.of(
            FLYWAY_CONFIGURATION + "$PostgresqlConfiguration",
            FLYWAY_CONFIGURATION + "$SqlServerConfiguration",
            FLYWAY_CONFIGURATION + "$OracleConfiguration");

    private static final List<Class<?>> GUARDED_TYPES = List.of(DataSource.class, JdbcConnectionDetails.class,
            Flyway.class, FlywayConnectionDetails.class, FlywayMigrationInitializer.class,
            FlywayMigrationStrategy.class, JavaMigration.class, Callback.class, FlywayConfigurationCustomizer.class);

    // Registered by ConfigurationPropertiesBinder.register (Boot 3.5.16): a RootBeanDefinition of this class, by
    // Class reference, ROLE_INFRASTRUCTURE, no factory method. Name, role or package alone is never sufficient.
    static final String BOOT_BINDER_FACTORY_BEAN_NAME =
            "org.springframework.boot.context.internalConfigurationPropertiesBinder";
    static final String BOOT_BINDER_FACTORY_CLASS =
            "org.springframework.boot.context.properties.ConfigurationPropertiesBinder"
                    + "$ConfigurationPropertiesBinderFactory";

    TestDatabaseBeanDefinitionGuard() {
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        try {
            requireVerifiableDefinitions(beanFactory);
            requireBootStructure(beanFactory);
        } catch (TestDatabaseGuardException rejection) {
            // A fresh exception keeps the fixed reason while dropping anything attached on the way out.
            throw reject(rejection.reason());
        } catch (RuntimeException inspectionFailure) {
            // External messages may name classes, beans or values; they are replaced by a fixed diagnostic.
            throw reject(Reason.DEFINITION_INSPECTION_FAILED);
        }
    }

    private static void requireVerifiableDefinitions(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getSingletonNames()) {
            // Manually registered singletons: checked by instance class only, never by getObjectType().
            if (!beanFactory.containsBeanDefinition(name) && beanFactory.getSingleton(name) instanceof FactoryBean) {
                throw reject(Reason.FACTORY_BEAN_REJECTED);
            }
        }
        for (String name : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.getMergedBeanDefinition(name).isAbstract()) {
                continue;
            }
            // Both calls predict from the definition; no bean, factory method or FactoryBean is invoked.
            if (beanFactory.isFactoryBean(name) && !isBootBinderFactory(beanFactory, name)) {
                throw reject(Reason.FACTORY_BEAN_REJECTED);
            }
            Class<?> type = beanFactory.getType(name, false);
            if (type == null || mayProduceGuardedType(type)) {
                throw reject(Reason.DEFINITION_UNVERIFIABLE);
            }
        }
    }

    /** Inspects the registered definition only; the factory is never requested, initialized or asked for its object. */
    private static boolean isBootBinderFactory(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!BOOT_BINDER_FACTORY_BEAN_NAME.equals(name)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        return definition.getClass() == RootBeanDefinition.class
                && definition instanceof RootBeanDefinition root
                && root.hasBeanClass()
                && BOOT_BINDER_FACTORY_CLASS.equals(root.getBeanClass().getName())
                && root.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE
                && root.getFactoryMethodName() == null
                && root.getFactoryBeanName() == null
                && root.getParentName() == null
                && !root.isAbstract();
    }

    private static boolean mayProduceGuardedType(Class<?> type) {
        for (Class<?> guarded : GUARDED_TYPES) {
            if (type != guarded && type.isAssignableFrom(guarded)) {
                return true;
            }
        }
        return false;
    }

    private static void requireBootStructure(ConfigurableListableBeanFactory beanFactory) {
        requireSingleBootDefinition(beanFactory, DataSource.class, HIKARI_CONFIGURATION, "dataSource",
                Reason.DATASOURCE_UNSUPPORTED);
        requireSingleBootDefinition(beanFactory, JdbcConnectionDetails.class, POOLED_DATASOURCE_CONFIGURATION,
                "jdbcConnectionDetails", Reason.CONNECTION_DETAILS_UNSUPPORTED);
        requireSingleBootDefinition(beanFactory, Flyway.class, FLYWAY_CONFIGURATION, "flyway",
                Reason.FLYWAY_UNSUPPORTED);
        requireSingleBootDefinition(beanFactory, FlywayConnectionDetails.class, FLYWAY_CONFIGURATION,
                "flywayConnectionDetails", Reason.FLYWAY_CONNECTION_DETAILS_UNSUPPORTED);
        requireSingleBootDefinition(beanFactory, FlywayMigrationInitializer.class, FLYWAY_CONFIGURATION,
                "flywayInitializer", Reason.MIGRATION_INITIALIZER_UNSUPPORTED);
        rejectAny(beanFactory, FlywayMigrationStrategy.class, Reason.MIGRATION_STRATEGY_REJECTED);
        rejectAny(beanFactory, JavaMigration.class, Reason.FLYWAY_EXTENSION_REJECTED);
        rejectAny(beanFactory, Callback.class, Reason.FLYWAY_EXTENSION_REJECTED);
        for (String name : namesForType(beanFactory, FlywayConfigurationCustomizer.class)) {
            MethodMetadata method = factoryMethod(beanFactory, name);
            if (method == null || !BOOT_FLYWAY_CUSTOMIZER_CONFIGURATIONS.contains(method.getDeclaringClassName())) {
                throw reject(Reason.FLYWAY_CUSTOMIZER_REJECTED);
            }
        }
    }

    private static void requireSingleBootDefinition(ConfigurableListableBeanFactory beanFactory, Class<?> type,
            String declaringClass, String methodName, Reason reason) {
        String[] names = namesForType(beanFactory, type);
        if (names.length != 1) {
            throw reject(reason);
        }
        MethodMetadata method = factoryMethod(beanFactory, names[0]);
        if (method == null || !declaringClass.equals(method.getDeclaringClassName())
                || !methodName.equals(method.getMethodName())) {
            throw reject(reason);
        }
    }

    private static void rejectAny(ConfigurableListableBeanFactory beanFactory, Class<?> type, Reason reason) {
        if (namesForType(beanFactory, type).length != 0) {
            throw reject(reason);
        }
    }

    private static String[] namesForType(ConfigurableListableBeanFactory beanFactory, Class<?> type) {
        // allowEagerInit=false: types are predicted from definitions; no bean or FactoryBean is instantiated.
        return beanFactory.getBeanNamesForType(type, true, false);
    }

    private static MethodMetadata factoryMethod(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsBeanDefinition(name)) {
            // Manually registered singletons have no reviewable definition.
            return null;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        return definition instanceof AnnotatedBeanDefinition annotated ? annotated.getFactoryMethodMetadata() : null;
    }

    private static TestDatabaseGuardException reject(Reason reason) {
        return new TestDatabaseGuardException(Stage.BEAN_DEFINITION, reason);
    }
}
