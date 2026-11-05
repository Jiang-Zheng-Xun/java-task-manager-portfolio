package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.data.repository.config.RepositoryConfigurationDelegate;

/**
 * Fixed four-definition repository group recognition, baselined on REAL Spring Data JPA 3.5.13 registration
 * (RepositoryConfigurationDelegate + JpaRepositoryConfigExtension). Definition registration only: no refresh, no bean
 * creation. Every negative mutates a freshly registered real baseline. Structural recognition is not provenance: an
 * identical foreign definition is not distinguishable.
 */
class TestDatabaseRepositoryGroupDefinitionGuardTest {

    private static final String REPOSITORY = "taskJpaRepository";
    private static final String NAMED_QUERIES = "jpa.named-queries#0";
    private static final String FRAGMENTS = "jpa.TaskJpaRepository.fragments#0";
    private static final String MAPPING_CONTEXT = "jpaMappingContext";
    private static final String SHARED_EM = "jpaSharedEM_entityManagerFactory";
    private static final String INTERFACE =
            "io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence.TaskJpaRepository";

    @EnableJpaRepositories(basePackages = "io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence")
    static class RepositoryConfig {
    }

    private static DefaultListableBeanFactory registered() {
        return registered(new DefaultListableBeanFactory());
    }

    private static <F extends DefaultListableBeanFactory> F registered(F beanFactory) {
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        StandardEnvironment environment = new StandardEnvironment();
        AnnotationRepositoryConfigurationSource source = new AnnotationRepositoryConfigurationSource(
                AnnotationMetadata.introspect(RepositoryConfig.class), EnableJpaRepositories.class, resourceLoader,
                environment, beanFactory);
        new RepositoryConfigurationDelegate(source, resourceLoader, environment)
                .registerRepositoriesIn(beanFactory, new JpaRepositoryConfigExtension());
        assertThat(beanFactory.getSingletonCount()).isZero();
        return beanFactory;
    }

    private static boolean recognized(DefaultListableBeanFactory beanFactory) {
        return TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(beanFactory, true)
                .equals(TestDatabaseBeanDefinitionGuard.REPOSITORY_GROUP);
    }

    /** Mutation on a fresh real baseline: recognition is refused and nothing is created. */
    private static void assertRefused(String label, String name, Consumer<RootBeanDefinition> alteration) {
        DefaultListableBeanFactory beanFactory = registered();
        alteration.accept((RootBeanDefinition) beanFactory.getBeanDefinition(name));
        assertThat(TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(beanFactory, true)).as(label).isEmpty();
        assertThat(beanFactory.getSingletonCount()).as(label).isZero();
    }

    private static TestDatabaseGuardException guardRejection(DefaultListableBeanFactory beanFactory, boolean controlled) {
        Throwable thrown = catchThrowable(() -> new TestDatabaseBeanDefinitionGuard(controlled)
                .postProcessBeanFactory(beanFactory));
        assertThat(thrown).isInstanceOf(TestDatabaseGuardException.class);
        TestDatabaseGuardException rejection = (TestDatabaseGuardException) thrown;
        assertThat(rejection.stage()).isEqualTo(Stage.BEAN_DEFINITION);
        return rejection;
    }

    // ---- positive baseline ----

    @Test
    void realRegistrationProducesTheFixedGroupAndIsRecognized() {
        DefaultListableBeanFactory beanFactory = registered();
        assertThat(beanFactory.getBeanDefinitionNames()).contains(REPOSITORY, NAMED_QUERIES, FRAGMENTS,
                MAPPING_CONTEXT, SHARED_EM);
        RootBeanDefinition repository = (RootBeanDefinition) beanFactory.getBeanDefinition(REPOSITORY);
        assertThat(repository.hasBeanClass()).isFalse();
        assertThat(repository.getTargetType()).isEqualTo(JpaRepositoryFactoryBean.class);
        assertThat(recognized(beanFactory)).isTrue();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void recognitionPerformsNoBeanTypeMergeOrFactoryQueries() {
        AtomicInteger queries = new AtomicInteger();
        class RecordingFactory extends DefaultListableBeanFactory {
            boolean armed;

            private void count() {
                if (armed) {
                    queries.incrementAndGet();
                }
            }

            @Override
            public Object getBean(String name) {
                count();
                return super.getBean(name);
            }

            @Override
            public <T> T getBean(String name, Class<T> requiredType) {
                count();
                return super.getBean(name, requiredType);
            }

            @Override
            public Class<?> getType(String name) {
                count();
                return super.getType(name);
            }

            @Override
            public Class<?> getType(String name, boolean allowFactoryBeanInit) {
                count();
                return super.getType(name, allowFactoryBeanInit);
            }

            @Override
            public boolean isFactoryBean(String name) {
                count();
                return super.isFactoryBean(name);
            }

            @Override
            public BeanDefinition getMergedBeanDefinition(String name) {
                count();
                return super.getMergedBeanDefinition(name);
            }

            @Override
            public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
                count();
                return super.getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
            }
        }
        RecordingFactory beanFactory = registered(new RecordingFactory());
        beanFactory.armed = true;
        assertThat(recognized(beanFactory)).isTrue();
        assertThat(queries.get()).as("bean/type/merge/FactoryBean queries during recognition").isZero();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    // ---- production guard path ----

    @Test
    void recognizedGroupProceedsToTheNextStructuralRejectionOnTheGuardPath() {
        DefaultListableBeanFactory beanFactory = registered();
        assertThat(guardRejection(beanFactory, true).reason()).isEqualTo(Reason.DATASOURCE_UNSUPPORTED);
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void unrecognizedGroupOrUncontrolledModeIsRejectedAsFactoryBeanOnTheGuardPath() {
        assertThat(guardRejection(registered(), false).reason()).as("uncontrolled").isEqualTo(Reason.FACTORY_BEAN_REJECTED);
        DefaultListableBeanFactory mutated = registered();
        mutated.getBeanDefinition(REPOSITORY).setLazyInit(true);
        assertThat(guardRejection(mutated, true).reason()).as("mutated").isEqualTo(Reason.FACTORY_BEAN_REJECTED);
        DefaultListableBeanFactory singleton = registered();
        singleton.registerSingleton(NAMED_QUERIES, new Object());
        assertThat(guardRejection(singleton, true).reason()).as("existing singleton")
                .isEqualTo(Reason.FACTORY_BEAN_REJECTED);
    }

    @Test
    void earlierRejectionOrderAndLaterUnknownFactoryBeanAreUnchanged() {
        for (boolean mutate : new boolean[] {false, true}) {
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerBeanDefinition("aEarlierUnverifiable", new RootBeanDefinition(Object.class));
            registered(beanFactory);
            if (mutate) {
                beanFactory.getBeanDefinition(REPOSITORY).setPrimary(true);
            }
            assertThat(guardRejection(beanFactory, true).reason()).as("earlier, mutate=" + mutate)
                    .isEqualTo(Reason.DEFINITION_UNVERIFIABLE);
        }
        UnknownFactoryBean.CALLS.set(0);
        DefaultListableBeanFactory later = registered();
        later.registerBeanDefinition("zLaterUnknownFactory", new RootBeanDefinition(UnknownFactoryBean.class));
        assertThat(guardRejection(later, true).reason()).isEqualTo(Reason.FACTORY_BEAN_REJECTED);
        assertThat(UnknownFactoryBean.CALLS.get()).isZero();
        assertThat(later.getSingletonCount()).isZero();
    }

    // ---- mode, singleton, membership ----

    @Test
    void uncontrolledModeRecognizesNothing() {
        assertThat(TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(registered(), false)).isEmpty();
    }

    @Test
    void existingSingletonForAnyMemberRejectsTheGroup() {
        for (String name : TestDatabaseBeanDefinitionGuard.REPOSITORY_GROUP) {
            DefaultListableBeanFactory beanFactory = registered();
            beanFactory.registerSingleton(name, new Object());
            assertThat(TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(beanFactory, true)).as(name).isEmpty();
        }
    }

    @Test
    void missingMemberOrExternalReferenceRejectsTheGroup() {
        for (String name : List.of(REPOSITORY, NAMED_QUERIES, FRAGMENTS, MAPPING_CONTEXT, SHARED_EM)) {
            DefaultListableBeanFactory beanFactory = registered();
            beanFactory.removeBeanDefinition(name);
            assertThat(TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(beanFactory, true)).as(name).isEmpty();
        }
    }

    // ---- creation-related field families (each label on a fresh baseline) ----

    @Test
    void creationSettingMutationsAreRefusedForEveryMember() {
        for (String name : TestDatabaseBeanDefinitionGuard.REPOSITORY_GROUP) {
            assertRefused(name + ":scope", name, d -> d.setScope(BeanDefinition.SCOPE_PROTOTYPE));
            assertRefused(name + ":abstract", name, d -> d.setAbstract(true));
            assertRefused(name + ":dependsOn", name, d -> d.setDependsOn("other"));
            assertRefused(name + ":init", name, d -> d.setInitMethodName("init"));
            assertRefused(name + ":destroy", name, d -> d.setDestroyMethodName("close"));
            assertRefused(name + ":autowire", name, d -> d.setAutowireMode(RootBeanDefinition.AUTOWIRE_BY_TYPE));
            assertRefused(name + ":dependencyCheck", name, d -> d.setDependencyCheck(RootBeanDefinition.DEPENDENCY_CHECK_ALL));
            assertRefused(name + ":autowireCandidate", name, d -> d.setAutowireCandidate(false));
            assertRefused(name + ":defaultCandidate", name, d -> d.setDefaultCandidate(false));
            assertRefused(name + ":primary", name, d -> d.setPrimary(true));
            assertRefused(name + ":fallback", name, d -> d.setFallback(true));
            assertRefused(name + ":synthetic", name, d -> d.setSynthetic(true));
            assertRefused(name + ":backgroundInit", name, d -> d.setBackgroundInit(true));
            assertRefused(name + ":enforceInit", name, d -> d.setEnforceInitMethod(false));
            assertRefused(name + ":enforceDestroy", name, d -> d.setEnforceDestroyMethod(false));
            assertRefused(name + ":nonPublicAccess", name, d -> d.setNonPublicAccessAllowed(false));
            assertRefused(name + ":lenient", name, d -> d.setLenientConstructorResolution(false));
            assertRefused(name + ":factoryMethod", name, d -> d.setFactoryMethodName("create"));
            assertRefused(name + ":factoryBean", name, d -> d.setFactoryBeanName("otherFactory"));
            assertRefused(name + ":supplier", name, d -> d.setInstanceSupplier(() -> {
                throw new AssertionError("SUPPLIER:INVOKED");
            }));
            assertRefused(name + ":objectTypeAttribute", name, d -> d.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE,
                    Object.class));
            assertRefused(name + ":role", name, d -> d.setRole(BeanDefinition.ROLE_SUPPORT));
        }
    }

    @Test
    void repositoryClassTargetAndLazyMutationsAreRefused() {
        assertRefused("className", REPOSITORY, d -> d.setBeanClassName("java.lang.Object"));
        assertRefused("classObject", REPOSITORY, d -> d.setBeanClass(JpaRepositoryFactoryBean.class));
        assertRefused("noTarget", REPOSITORY, d -> d.setTargetType((ResolvableType) null));
        assertRefused("rawTarget", REPOSITORY, d -> d.setTargetType(ResolvableType.forClass(JpaRepositoryFactoryBean.class)));
        assertRefused("wrongTargetClass", REPOSITORY, d -> d.setTargetType(ResolvableType.forClass(Object.class)));
        assertRefused("wrongGenerics", REPOSITORY, d -> d.setTargetType(ResolvableType.forClassWithGenerics(
                JpaRepositoryFactoryBean.class, Object.class, Object.class, Long.class)));
        assertRefused("wrongId", REPOSITORY, d -> d.setTargetType(ResolvableType.forClassWithGenerics(
                JpaRepositoryFactoryBean.class, ((RootBeanDefinition) registered().getBeanDefinition(REPOSITORY))
                        .getResolvableType().getGeneric(0).resolve(),
                ((RootBeanDefinition) registered().getBeanDefinition(REPOSITORY)).getResolvableType().getGeneric(1)
                        .resolve(), Integer.class)));
        assertRefused("lazyTrue", REPOSITORY, d -> d.setLazyInit(true));
    }

    @Test
    void repositoryArgumentAndHolderMutationsAreRefused() {
        assertRefused("otherInterface", REPOSITORY,
                d -> d.getConstructorArgumentValues().addIndexedArgumentValue(0, "x.Other"));
        assertRefused("extraIndexed", REPOSITORY, d -> d.getConstructorArgumentValues().addIndexedArgumentValue(1, "x"));
        assertRefused("generic", REPOSITORY, d -> d.getConstructorArgumentValues().addGenericArgumentValue("x"));
        assertRefused("typedHolder", REPOSITORY, d -> d.getConstructorArgumentValues().addIndexedArgumentValue(0,
                new ConstructorArgumentValues.ValueHolder(INTERFACE, "java.lang.String")));
        assertRefused("namedHolder", REPOSITORY, d -> d.getConstructorArgumentValues().addIndexedArgumentValue(0,
                new ConstructorArgumentValues.ValueHolder(INTERFACE, null, "repositoryInterface")));
        assertRefused("convertedHolder", REPOSITORY, d -> d.getConstructorArgumentValues().getIndexedArgumentValues()
                .get(0).setConvertedValue(INTERFACE));
    }

    @Test
    void repositoryPropertyAndReferenceMutationsAreRefused() {
        assertRefused("repositoryBaseClass", REPOSITORY, d -> d.getPropertyValues().add("repositoryBaseClass", "x.Base"));
        assertRefused("customImplementation", REPOSITORY, d -> d.getPropertyValues().add("customImplementation",
                new RuntimeBeanReference("custom")));
        assertRefused("missingLazyInit", REPOSITORY, d -> d.getPropertyValues().removePropertyValue("lazyInit"));
        assertRefused("lookupStrategy", REPOSITORY, d -> d.getPropertyValues().add("queryLookupStrategyKey",
                org.springframework.data.repository.query.QueryLookupStrategy.Key.CREATE));
        assertRefused("namedQueriesRef", REPOSITORY, d -> d.getPropertyValues().add("namedQueries",
                new RuntimeBeanReference("other")));
        assertRefused("fragmentsRef", REPOSITORY, d -> d.getPropertyValues().add("repositoryFragments",
                new RuntimeBeanReference("other")));
        assertRefused("entityManagerDirectFactory", REPOSITORY, d -> d.getPropertyValues().add("entityManager",
                new RuntimeBeanReference("entityManagerFactory")));
        assertRefused("parentReference", REPOSITORY, d -> d.getPropertyValues().add("mappingContext",
                new RuntimeBeanReference(MAPPING_CONTEXT, true)));
        assertRefused("typeOnlyReference", REPOSITORY, d -> d.getPropertyValues().add("mappingContext",
                new RuntimeBeanReference(Object.class)));
        assertRefused("mappingContextString", REPOSITORY, d -> d.getPropertyValues().add("mappingContext", MAPPING_CONTEXT));
        assertRefused("escape", REPOSITORY, d -> d.getPropertyValues().add("escapeCharacter", '/'));
        assertRefused("defaultTx", REPOSITORY, d -> d.getPropertyValues().add("enableDefaultTransactions", false));
        assertRefused("txManager", REPOSITORY, d -> d.getPropertyValues().add("transactionManager", "other"));
    }

    @Test
    void memberDefinitionMutationsAreRefused() {
        assertRefused("fragmentsNonEmpty", FRAGMENTS,
                d -> d.getConstructorArgumentValues().addIndexedArgumentValue(0, List.of("fragment")));
        assertRefused("fragmentsRole", FRAGMENTS, d -> d.setRole(BeanDefinition.ROLE_APPLICATION));
        assertRefused("fragmentsClass", FRAGMENTS, d -> d.setBeanClass(Object.class));
        assertRefused("fragmentsProperty", FRAGMENTS, d -> d.getPropertyValues().add("extra", "x"));
        assertRefused("fragmentsTarget", FRAGMENTS, d -> d.setTargetType(ResolvableType.forClass(Object.class)));
        assertRefused("fragmentsLazy", FRAGMENTS, d -> d.setLazyInit(false));
        assertRefused("namedQueriesLocation", NAMED_QUERIES,
                d -> d.getPropertyValues().add("locations", "classpath:other.properties"));
        assertRefused("namedQueriesIgnore", NAMED_QUERIES,
                d -> d.getPropertyValues().removePropertyValue("ignoreResourceNotFound"));
        assertRefused("namedQueriesArg", NAMED_QUERIES, d -> d.getConstructorArgumentValues().addGenericArgumentValue("x"));
        assertRefused("namedQueriesLazy", NAMED_QUERIES, d -> d.setLazyInit(true));
        assertRefused("mappingLazy", MAPPING_CONTEXT, d -> d.setLazyInit(false));
        assertRefused("mappingProperty", MAPPING_CONTEXT, d -> d.getPropertyValues().add("extra", "x"));
        assertRefused("mappingTarget", MAPPING_CONTEXT, d -> d.setTargetType(ResolvableType.forClass(Object.class)));
        assertRefused("mappingClass", MAPPING_CONTEXT, d -> d.setBeanClassName("java.lang.Object"));
    }

    @Test
    void externalEntityManagerShapeMutationsAreRefused() {
        assertRefused("emFactoryMethod", SHARED_EM, d -> d.setFactoryMethodName("other"));
        assertRefused("emClass", SHARED_EM, d -> d.setBeanClassName("java.lang.Object"));
        assertRefused("emRole", SHARED_EM, d -> d.setRole(BeanDefinition.ROLE_APPLICATION));
        assertRefused("emAutowireCandidate", SHARED_EM, d -> d.setAutowireCandidate(true));
        assertRefused("emPrimary", SHARED_EM, d -> d.setPrimary(true));
        assertRefused("emScope", SHARED_EM, d -> d.setScope(BeanDefinition.SCOPE_PROTOTYPE));
        assertRefused("emDependsOn", SHARED_EM, d -> d.setDependsOn("other"));
        assertRefused("emInit", SHARED_EM, d -> d.setInitMethodName("init"));
        assertRefused("emLazy", SHARED_EM, d -> d.setLazyInit(true));
        assertRefused("emTarget", SHARED_EM, d -> d.setTargetType(ResolvableType.forClass(Object.class)));
        assertRefused("emBackgroundInit", SHARED_EM, d -> d.setBackgroundInit(true));
        assertRefused("emProperty", SHARED_EM, d -> d.getPropertyValues().add("extra", "x"));
        assertRefused("emGenericArg", SHARED_EM, d -> d.getConstructorArgumentValues().addGenericArgumentValue("x"));
        assertRefused("emReference", SHARED_EM, d -> d.getConstructorArgumentValues()
                .addIndexedArgumentValue(0, new RuntimeBeanReference("otherFactory")));
        assertRefused("emAttribute", SHARED_EM, d -> d.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, Object.class));
    }

    @Test
    void foreignDefinitionRepresentationIsRefused() {
        DefaultListableBeanFactory beanFactory = registered();
        GenericBeanDefinition copy = new GenericBeanDefinition(beanFactory.getBeanDefinition(MAPPING_CONTEXT));
        beanFactory.removeBeanDefinition(MAPPING_CONTEXT);
        beanFactory.registerBeanDefinition(MAPPING_CONTEXT, copy);
        assertThat(TestDatabaseBeanDefinitionGuard.recognizedRepositoryGroup(beanFactory, true)).isEmpty();
    }

    /** Unknown FactoryBean: every entry point counts; must never be constructed or asked. */
    static class UnknownFactoryBean implements FactoryBean<String> {
        static final AtomicInteger CALLS = new AtomicInteger();

        UnknownFactoryBean() {
            CALLS.incrementAndGet();
        }

        @Override
        public String getObject() {
            CALLS.incrementAndGet();
            return "unknown";
        }

        @Override
        public Class<?> getObjectType() {
            CALLS.incrementAndGet();
            return String.class;
        }
    }
}
