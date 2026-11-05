package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.data.repository.query.QueryLookupStrategy;
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
 * <p>It inspects bean definitions and predicted types and never requests bean instances or calls
 * {@link FactoryBean#getObject()} itself. Exception: for a FactoryBean singleton that ALREADY exists (Boot's recognized
 * metadata factory), Spring's type enumeration may call its {@link FactoryBean#getObjectType()}. Every local definition must first be
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

    static final String GUARDED_STRATEGY_FACTORY_METHOD = "guardedFlywayMigrationStrategy";

    // Boot 3.5.16 shared metadata-reader factory, registered by SharedMetadataReaderFactoryContextInitializer.
    static final String BOOT_METADATA_FACTORY_BEAN_NAME =
            "org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory";
    static final String BOOT_METADATA_FACTORY_CLASS = "org.springframework.boot.autoconfigure."
            + "SharedMetadataReaderFactoryContextInitializer$SharedMetadataReaderFactoryBean";

    // Boot 3.5.16 JPA factory: JpaBaseConfiguration#entityManagerFactory, inherited by HibernateJpaConfiguration.
    static final String BOOT_JPA_FACTORY_BEAN_NAME = "entityManagerFactory";
    static final String BOOT_JPA_BASE_CONFIGURATION_CLASS =
            "org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration";
    static final String BOOT_HIBERNATE_JPA_CONFIGURATION_CLASS =
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaConfiguration";
    static final String BOOT_JPA_FACTORY_CLASS = "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean";

    private final boolean requireGuardedStrategy;

    TestDatabaseBeanDefinitionGuard() {
        this(false);
    }

    /**
     * With {@code requireGuardedStrategy}, exactly one {@link FlywayMigrationStrategy} must exist and it must be the
     * {@link GuardedFlywayMigrationStrategy} returned by {@link GuardedTestDatabaseConfiguration}'s single factory
     * method; otherwise every strategy is rejected.
     */
    TestDatabaseBeanDefinitionGuard(boolean requireGuardedStrategy) {
        this.requireGuardedStrategy = requireGuardedStrategy;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        try {
            requireVerifiableDefinitions(beanFactory, requireGuardedStrategy);
            requireBootStructure(beanFactory);
            requireStrategyStructure(beanFactory);
        } catch (TestDatabaseGuardException rejection) {
            // A fresh exception keeps the fixed reason while dropping anything attached on the way out.
            throw reject(rejection.reason());
        } catch (RuntimeException inspectionFailure) {
            // External messages may name classes, beans or values; they are replaced by a fixed diagnostic.
            throw reject(Reason.DEFINITION_INSPECTION_FAILED);
        }
    }

    private static void requireVerifiableDefinitions(ConfigurableListableBeanFactory beanFactory,
            boolean controlled) {
        for (String name : beanFactory.getSingletonNames()) {
            // Manually registered singletons: checked by instance class only, never by getObjectType().
            if (!beanFactory.containsBeanDefinition(name) && beanFactory.getSingleton(name) instanceof FactoryBean) {
                throw reject(Reason.FACTORY_BEAN_REJECTED);
            }
        }
        // Evaluated once per validation, before the ordered scan; a failed recognition never throws here, so the
        // original per-definition rejection order and attribution are unchanged.
        Set<String> repositoryGroup = recognizedRepositoryGroup(beanFactory, controlled);
        for (String name : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.getMergedBeanDefinition(name).isAbstract()) {
                continue;
            }
            // isFactoryBean predicts from the definition, or inspects an already existing singleton's class.
            boolean metadata = isBootMetadataFactoryDefinition(name, beanFactory.getBeanDefinition(name),
                    beanFactory.getSingleton(name));
            if (beanFactory.isFactoryBean(name) && !isBootBinderFactory(beanFactory, name) && !metadata
                    && !(controlled && isBootJpaFactoryDefinition(beanFactory, name))
                    && !repositoryGroup.contains(name)) {
                throw reject(Reason.FACTORY_BEAN_REJECTED);
            }
            if (metadata) {
                // The fully recognized metadata definition completes its verifiability check here: its product type
                // is not queried, because getType on an existing FactoryBean singleton would call getObjectType().
                // Later structure checks enumerate types with getBeanNamesForType, which CAN still query an existing
                // FactoryBean singleton's object type (allowFactoryBeanInit is true when the singleton exists).
                continue;
            }
            Class<?> type = beanFactory.getType(name, false);
            if (type == null || mayProduceGuardedType(type)) {
                throw reject(Reason.DEFINITION_UNVERIFIABLE);
            }
        }
    }

    // Spring Data JPA 3.5.13 repository registration for the project's single repository (fixed fixture scope).
    static final String REPOSITORY_BEAN_NAME = "taskJpaRepository";
    static final String NAMED_QUERIES_BEAN_NAME = "jpa.named-queries#0";
    static final String FRAGMENTS_BEAN_NAME = "jpa.TaskJpaRepository.fragments#0";
    static final String MAPPING_CONTEXT_BEAN_NAME = "jpaMappingContext";
    static final String SHARED_ENTITY_MANAGER_BEAN_NAME = "jpaSharedEM_entityManagerFactory";
    static final Set<String> REPOSITORY_GROUP = Set.of(REPOSITORY_BEAN_NAME, NAMED_QUERIES_BEAN_NAME,
            FRAGMENTS_BEAN_NAME, MAPPING_CONTEXT_BEAN_NAME);
    static final String REPOSITORY_FACTORY_CLASS = "org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean";
    static final String REPOSITORY_INTERFACE =
            "io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence.TaskJpaRepository";
    static final String REPOSITORY_DOMAIN = "io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence.TaskEntity";
    static final String FRAGMENTS_FACTORY_CLASS =
            "org.springframework.data.repository.core.support.RepositoryFragmentsFactoryBean";
    static final String NAMED_QUERIES_FACTORY_CLASS =
            "org.springframework.data.repository.config.PropertiesBasedNamedQueriesFactoryBean";
    static final String MAPPING_CONTEXT_FACTORY_CLASS =
            "org.springframework.data.jpa.repository.config.JpaMetamodelMappingContextFactoryBean";
    static final String NAMED_QUERIES_LOCATION = "classpath*:META-INF/jpa-named-queries.properties";
    static final String SHARED_ENTITY_MANAGER_CREATOR = "org.springframework.orm.jpa.SharedEntityManagerCreator";

    /**
     * Whole-group structural recognition of the four exact repository definitions, controlled mode only. Reads
     * REGISTERED definitions and the singleton cache only (no merge, getBean, getType, isFactoryBean, supplier or
     * FactoryBean call). Structural evidence only, not provenance: an identical foreign definition is not
     * distinguishable. Returns an empty set unless every condition holds; never throws for a mismatch.
     */
    static Set<String> recognizedRepositoryGroup(ConfigurableListableBeanFactory beanFactory, boolean controlled) {
        if (!controlled) {
            return Set.of();
        }
        for (String name : REPOSITORY_GROUP) {
            if (!beanFactory.containsBeanDefinition(name) || beanFactory.containsSingleton(name)) {
                return Set.of();
            }
        }
        if (!beanFactory.containsBeanDefinition(SHARED_ENTITY_MANAGER_BEAN_NAME)) {
            return Set.of();
        }
        boolean recognized = isRepositoryDefinition(beanFactory.getBeanDefinition(REPOSITORY_BEAN_NAME))
                && isFragmentsDefinition(beanFactory.getBeanDefinition(FRAGMENTS_BEAN_NAME))
                && isNamedQueriesDefinition(beanFactory.getBeanDefinition(NAMED_QUERIES_BEAN_NAME))
                && isMappingContextDefinition(beanFactory.getBeanDefinition(MAPPING_CONTEXT_BEAN_NAME))
                && isSharedEntityManagerDefinition(beanFactory.getBeanDefinition(SHARED_ENTITY_MANAGER_BEAN_NAME));
        return recognized ? REPOSITORY_GROUP : Set.of();
    }

    /** Common local shape of the four members: plain singleton RootBeanDefinition built by BeanDefinitionBuilder. */
    private static boolean isPlainRoot(BeanDefinition definition) {
        if (definition == null || definition.getClass() != RootBeanDefinition.class) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        return root.getFactoryBeanName() == null && root.getFactoryMethodName() == null && hasDefaultCreationSettings(root);
    }

    /**
     * Spring 6.2.19 AbstractBeanDefinition defaults that affect creation (fields lines 172-231 of the matching source):
     * singleton scope "", not abstract, no parent/supplier/overrides/dependsOn/init/destroy names, AUTOWIRE_NO,
     * DEPENDENCY_CHECK_NONE, candidate flags, no primary/fallback/synthetic/backgroundInit, enforced lifecycle defaults,
     * non-public access and lenient constructor resolution, and no FactoryBean object-type attribute.
     */
    private static boolean hasDefaultCreationSettings(RootBeanDefinition root) {
        return root.getParentName() == null && !root.isAbstract() && root.getInstanceSupplier() == null
                && !root.isBackgroundInit() && root.isEnforceInitMethod() && root.isEnforceDestroyMethod()
                && root.isNonPublicAccessAllowed() && root.isLenientConstructorResolution()
                && !root.hasMethodOverrides() && "".equals(root.getScope())
                && (root.getDependsOn() == null || root.getDependsOn().length == 0)
                && root.getInitMethodNames() == null && root.getDestroyMethodNames() == null
                && root.getAutowireMode() == AbstractBeanDefinition.AUTOWIRE_NO
                && root.getDependencyCheck() == AbstractBeanDefinition.DEPENDENCY_CHECK_NONE
                && root.isAutowireCandidate() && root.isDefaultCandidate() && !root.isPrimary() && !root.isFallback()
                && !root.isSynthetic() && root.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE) == null;
    }

    private static boolean hasClass(RootBeanDefinition root, String className) {
        return root.hasBeanClass() && className.equals(root.getBeanClass().getName());
    }

    /** Exactly one indexed argument at index 0 with a plain holder (no type, name or converted value). */
    private static Object singleIndexedArgument(RootBeanDefinition root) {
        ConstructorArgumentValues arguments = root.getConstructorArgumentValues();
        if (!arguments.getGenericArgumentValues().isEmpty() || arguments.getIndexedArgumentValues().size() != 1) {
            return null;
        }
        ConstructorArgumentValues.ValueHolder holder = arguments.getIndexedArgumentValues().get(0);
        if (holder == null || holder.getType() != null || holder.getName() != null || holder.isConverted()) {
            return null;
        }
        return holder.getValue();
    }

    private static boolean isReference(Object value, String beanName) {
        return value instanceof RuntimeBeanReference reference && beanName.equals(reference.getBeanName())
                && reference.getBeanType() == null && !reference.isToParent();
    }

    private static boolean isRepositoryDefinition(BeanDefinition definition) {
        if (!isPlainRoot(definition)) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        if (root.hasBeanClass() || !REPOSITORY_FACTORY_CLASS.equals(root.getBeanClassName())
                || root.getRole() != BeanDefinition.ROLE_APPLICATION || !Boolean.FALSE.equals(root.getLazyInit())
                || !REPOSITORY_INTERFACE.equals(singleIndexedArgument(root))) {
            return false;
        }
        // Explicit target required: getTargetType() is non-null only for an explicit (or Spring-cached) target; with no
        // bean class and no factory method, getResolvableType() then returns the explicit ResolvableType (RootBeanDefinition
        // 6.2.19 lines 350-377) and otherwise falls back to NONE, so generics below cannot come from a fallback.
        if (root.getTargetType() == null) {
            return false;
        }
        ResolvableType target = root.getResolvableType();
        Class<?> targetClass = target.resolve();
        if (targetClass == null || !REPOSITORY_FACTORY_CLASS.equals(targetClass.getName())
                || target.getGenerics().length != 3 || !hasGeneric(target, 0, REPOSITORY_INTERFACE)
                || !hasGeneric(target, 1, REPOSITORY_DOMAIN) || !hasGeneric(target, 2, Long.class.getName())) {
            return false;
        }
        PropertyValues values = root.getPropertyValues();
        return values.getPropertyValues().length == 9
                && value(values, "queryLookupStrategyKey") == QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND
                && Boolean.FALSE.equals(value(values, "lazyInit"))
                && isReference(value(values, "namedQueries"), NAMED_QUERIES_BEAN_NAME)
                && isReference(value(values, "repositoryFragments"), FRAGMENTS_BEAN_NAME)
                && "transactionManager".equals(value(values, "transactionManager"))
                && isReference(value(values, "entityManager"), SHARED_ENTITY_MANAGER_BEAN_NAME)
                && Character.valueOf('\\').equals(value(values, "escapeCharacter"))
                && isReference(value(values, "mappingContext"), MAPPING_CONTEXT_BEAN_NAME)
                && Boolean.TRUE.equals(value(values, "enableDefaultTransactions"));
    }

    private static boolean hasGeneric(ResolvableType type, int index, String className) {
        Class<?> generic = type.getGeneric(index).resolve();
        return generic != null && className.equals(generic.getName());
    }

    private static Object value(PropertyValues values, String name) {
        PropertyValue value = values.getPropertyValue(name);
        return value == null ? null : value.getValue();
    }

    private static boolean isFragmentsDefinition(BeanDefinition definition) {
        if (!isPlainRoot(definition)) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        return hasClass(root, FRAGMENTS_FACTORY_CLASS) && root.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE
                && root.getLazyInit() == null && root.getTargetType() == null && root.getPropertyValues().isEmpty()
                && singleIndexedArgument(root) instanceof List<?> fragments && fragments.isEmpty();
    }

    private static boolean isNamedQueriesDefinition(BeanDefinition definition) {
        if (!isPlainRoot(definition)) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        PropertyValues values = root.getPropertyValues();
        return hasClass(root, NAMED_QUERIES_FACTORY_CLASS) && root.getRole() == BeanDefinition.ROLE_APPLICATION
                && root.getLazyInit() == null && root.getTargetType() == null && !root.hasConstructorArgumentValues()
                && values.getPropertyValues().length == 2
                && NAMED_QUERIES_LOCATION.equals(value(values, "locations"))
                && Boolean.TRUE.equals(value(values, "ignoreResourceNotFound"));
    }

    private static boolean isMappingContextDefinition(BeanDefinition definition) {
        if (!isPlainRoot(definition)) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        return hasClass(root, MAPPING_CONTEXT_FACTORY_CLASS) && root.getRole() == BeanDefinition.ROLE_APPLICATION
                && Boolean.TRUE.equals(root.getLazyInit()) && root.getTargetType() == null
                && !root.hasConstructorArgumentValues() && root.getPropertyValues().isEmpty();
    }

    /** Bounded external reference check: shape only; never resolved, never allowed to be created by this guard. */
    private static boolean isSharedEntityManagerDefinition(BeanDefinition definition) {
        if (definition == null || definition.getClass() != RootBeanDefinition.class) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        // JpaRepositoryConfigExtension 3.5.13 lines 213-223/248-258: rootBeanDefinition(String), setFactoryMethod,
        // addConstructorArgReference, setRole(SUPPORT), setPrimary(false), setAutowireCandidate(false); all else default.
        return !root.hasBeanClass() && SHARED_ENTITY_MANAGER_CREATOR.equals(root.getBeanClassName())
                && "createSharedEntityManager".equals(root.getFactoryMethodName()) && root.getFactoryBeanName() == null
                && root.getParentName() == null && !root.isAbstract() && root.getInstanceSupplier() == null
                && !root.isBackgroundInit() && root.isEnforceInitMethod() && root.isEnforceDestroyMethod()
                && root.isNonPublicAccessAllowed() && root.isLenientConstructorResolution()
                && !root.hasMethodOverrides() && "".equals(root.getScope())
                && (root.getDependsOn() == null || root.getDependsOn().length == 0)
                && root.getInitMethodNames() == null && root.getDestroyMethodNames() == null
                && root.getAutowireMode() == AbstractBeanDefinition.AUTOWIRE_NO
                && root.getDependencyCheck() == AbstractBeanDefinition.DEPENDENCY_CHECK_NONE
                && root.getRole() == BeanDefinition.ROLE_SUPPORT && !root.isPrimary() && !root.isFallback()
                && !root.isAutowireCandidate() && root.isDefaultCandidate() && !root.isSynthetic()
                && root.getLazyInit() == null && root.getTargetType() == null
                && root.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE) == null && root.getPropertyValues().isEmpty()
                && isReference(singleIndexedArgument(root), BOOT_JPA_FACTORY_BEAN_NAME);
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

    /**
     * Both modes: Boot's shared metadata-reader FactoryBean registered by SharedMetadataReaderFactoryContextInitializer
     * (Boot 3.5.16) as {@code rootBeanDefinition(SharedMetadataReaderFactoryBean.class, SharedMetadataReaderFactoryBean::new)}.
     * Spring 6.2.19's BeanDefinitionBuilder turns that into {@code new RootBeanDefinition()} with an explicit TARGET TYPE
     * and an instance supplier and NO bean class; this recognizer requires exactly that shape. The target type is read
     * with {@link RootBeanDefinition#getTargetType()} on the REGISTERED definition (no bean-class or factory fallback).
     * This HELPER is side-effect free: it reads only the supplied definition and an already existing singleton; it never
     * creates, predicts or asks a bean. A non-null instance supplier is structural evidence only; its provenance is not
     * established and it is never invoked. The complete guard path is NOT side-effect free for this definition: Boot has
     * already created the factory and later type enumeration may query an existing singleton's object type.
     */
    static boolean isBootMetadataFactoryDefinition(String name, BeanDefinition definition, Object existingSingleton) {
        if (!BOOT_METADATA_FACTORY_BEAN_NAME.equals(name) || definition == null
                || definition.getClass() != RootBeanDefinition.class) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        if (root.hasBeanClass() || root.getBeanClassName() != null) {
            return false;
        }
        Class<?> targetType = root.getTargetType();
        if (targetType == null || !BOOT_METADATA_FACTORY_CLASS.equals(targetType.getName())
                || !FactoryBean.class.isAssignableFrom(targetType)
                || root.getInstanceSupplier() == null || root.isAbstract() || root.getParentName() != null
                || root.getFactoryMethodName() != null || root.getFactoryBeanName() != null) {
            return false;
        }
        return existingSingleton == null || existingSingleton.getClass() == targetType;
    }

    /**
     * Controlled mode only: Boot's JPA factory, {@code JpaBaseConfiguration#entityManagerFactory} inherited by the exact
     * {@code HibernateJpaConfiguration} definition. Definition-only: the factory type is predicted through the
     * {@code &} dereference, which returns the predicted factory class without constructing the factory or calling
     * {@code getObject()}/{@code getObjectType()}. Every later check still applies to the definition.
     */
    static boolean isBootJpaFactoryDefinition(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!BOOT_JPA_FACTORY_BEAN_NAME.equals(name) || !beanFactory.containsBeanDefinition(name)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        if (!(definition instanceof AnnotatedBeanDefinition annotated) || definition.isAbstract()
                || definition.getParentName() != null) {
            return false;
        }
        MethodMetadata method = annotated.getFactoryMethodMetadata();
        if (method == null || method.isStatic()
                || !BOOT_JPA_BASE_CONFIGURATION_CLASS.equals(method.getDeclaringClassName())
                || !BOOT_JPA_FACTORY_BEAN_NAME.equals(method.getMethodName())
                || !BOOT_JPA_FACTORY_CLASS.equals(method.getReturnTypeName())
                || !BOOT_JPA_FACTORY_BEAN_NAME.equals(definition.getFactoryMethodName())) {
            return false;
        }
        String configurationName = definition.getFactoryBeanName();
        if (configurationName == null || !beanFactory.containsBeanDefinition(configurationName)) {
            return false;
        }
        BeanDefinition configuration = beanFactory.getBeanDefinition(configurationName);
        if (!BOOT_HIBERNATE_JPA_CONFIGURATION_CLASS.equals(configuration.getBeanClassName())
                || configuration.isAbstract() || configuration.getParentName() != null
                || configuration.getFactoryBeanName() != null || configuration.getFactoryMethodName() != null) {
            return false;
        }
        Class<?> factoryType = beanFactory.getType(BeanFactory.FACTORY_BEAN_PREFIX + name, false);
        return factoryType != null && BOOT_JPA_FACTORY_CLASS.equals(factoryType.getName());
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
        rejectAny(beanFactory, JavaMigration.class, Reason.FLYWAY_EXTENSION_REJECTED);
        rejectAny(beanFactory, Callback.class, Reason.FLYWAY_EXTENSION_REJECTED);
        for (String name : namesForType(beanFactory, FlywayConfigurationCustomizer.class)) {
            MethodMetadata method = factoryMethod(beanFactory, name);
            if (method == null || !BOOT_FLYWAY_CUSTOMIZER_CONFIGURATIONS.contains(method.getDeclaringClassName())) {
                throw reject(Reason.FLYWAY_CUSTOMIZER_REJECTED);
            }
        }
    }

    private void requireStrategyStructure(ConfigurableListableBeanFactory beanFactory) {
        if (!requireGuardedStrategy) {
            rejectAny(beanFactory, FlywayMigrationStrategy.class, Reason.MIGRATION_STRATEGY_REJECTED);
            return;
        }
        String[] names = namesForType(beanFactory, FlywayMigrationStrategy.class);
        if (names.length != 1 || !isGuardedStrategyDefinition(beanFactory, names[0])) {
            throw reject(Reason.MIGRATION_STRATEGY_REJECTED);
        }
    }

    /** Definition-only check: source configuration, method, declared return type and predicted type must all match. */
    private static boolean isGuardedStrategyDefinition(ConfigurableListableBeanFactory beanFactory, String name) {
        MethodMetadata method = factoryMethod(beanFactory, name);
        if (method == null || method.isStatic()
                || !GuardedTestDatabaseConfiguration.class.getName().equals(method.getDeclaringClassName())
                || !GUARDED_STRATEGY_FACTORY_METHOD.equals(method.getMethodName())
                || !GuardedFlywayMigrationStrategy.class.getName().equals(method.getReturnTypeName())) {
            return false;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(name);
        String factoryBeanName = definition.getFactoryBeanName();
        if (!GUARDED_STRATEGY_FACTORY_METHOD.equals(definition.getFactoryMethodName()) || factoryBeanName == null
                || !beanFactory.containsBeanDefinition(factoryBeanName)
                || definition.getParentName() != null
                || !GuardedTestDatabaseConfiguration.class.getName()
                        .equals(beanFactory.getBeanDefinition(factoryBeanName).getBeanClassName())) {
            return false;
        }
        return beanFactory.getType(name, false) == GuardedFlywayMigrationStrategy.class;
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
        // allowEagerInit=false: no bean or FactoryBean is instantiated, but an ALREADY existing FactoryBean singleton
        // is still type-matched through its getObjectType() (Spring sets allowFactoryBeanInit when the singleton exists).
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
