package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Definition-only checks of the controlled-mode recognition of Boot's JPA factory. No refresh, no database.
 *
 * <p>Two separate kinds of evidence:
 * <ul>
 * <li>REAL FIXTURE tests use a real {@link DefaultListableBeanFactory} with Boot's actual
 * {@code JpaBaseConfiguration#entityManagerFactory} metadata and run the public guard path.</li>
 * <li>SYNTHETIC helper tests ({@code synthetic*}) use a test-owned metadata double and a bean factory whose
 * {@code &entityManagerFactory} type is fixed, so that exactly one condition differs from an accepted baseline.
 * They test only {@link TestDatabaseBeanDefinitionGuard#isBootJpaFactoryDefinition} and do not show how Boot
 * registers definitions.</li>
 * </ul>
 * In the real Boot fixture, passing the FactoryBean stage is shown by the later {@code DATASOURCE_UNSUPPORTED}
 * rejection, because no DataSource is defined. That shows only the recognition boundary, not a bootstrap.
 * Singleton checks there cannot detect transient calls; the no-creation claim for Boot's factory rests on the
 * reviewed Spring source path, not on the custom counters.
 */
class TestDatabaseJpaFactoryDefinitionGuardTest {

    private static final String HIBERNATE_JPA_CONFIGURATION =
            TestDatabaseBeanDefinitionGuard.BOOT_HIBERNATE_JPA_CONFIGURATION_CLASS;
    private static final String NAME = TestDatabaseBeanDefinitionGuard.BOOT_JPA_FACTORY_BEAN_NAME;
    private static final String FACTORY_REFERENCE = BeanFactory.FACTORY_BEAN_PREFIX + NAME;

    @BeforeEach
    void resetCounters() {
        CountingFactoryBean.CONSTRUCTED.set(0);
        CountingFactoryBean.GET_OBJECT.set(0);
        CountingFactoryBean.GET_OBJECT_TYPE.set(0);
        CustomJpaConfiguration.CONSTRUCTED.set(0);
    }

    // ---- REAL FIXTURE: public guard path ----

    @Test
    void exactBootJpaFactoryPassesFactoryBeanStageInControlledMode() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        assertNoSingletons(beanFactory);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isTrue();
        assertRejected(beanFactory, true, Reason.DATASOURCE_UNSUPPORTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    @Test
    void exactBootJpaFactoryRemainsRejectedInNoncontrolledMode() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        assertRejected(beanFactory, false, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    @Test
    void customFactoryBeanNamedEntityManagerFactoryIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("jpaConfiguration", new RootBeanDefinition(CustomJpaConfiguration.class));
        beanFactory.registerBeanDefinition(NAME,
                factoryMethodDefinition(CustomJpaConfiguration.class, "entityManagerFactory", "jpaConfiguration"));
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    /** REAL: changes more than one condition (declaring class and method); isolation is the synthetic cases' role. */
    @Test
    void differentFactoryMethodWithBootReturnTypeIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("jpaConfiguration", new RootBeanDefinition(CustomJpaConfiguration.class));
        beanFactory.registerBeanDefinition(NAME,
                factoryMethodDefinition(CustomJpaConfiguration.class, "otherFactory", "jpaConfiguration"));
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    /** REAL: changes more than one condition (static, declaring class, no factory bean); see synthetic cases. */
    @Test
    void staticFactoryMethodIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("jpaConfiguration", new RootBeanDefinition(CustomJpaConfiguration.class));
        AnnotatedGenericBeanDefinition definition =
                factoryMethodDefinition(CustomJpaConfiguration.class, "staticFactory", null);
        definition.setBeanClass(CustomJpaConfiguration.class);
        beanFactory.registerBeanDefinition(NAME, definition);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    @Test
    void wrongConfigurationDefinitionIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition configuration = new RootBeanDefinition();
        configuration.setBeanClassName(JpaBaseConfiguration.class.getName());
        beanFactory.registerBeanDefinition("jpaConfiguration", configuration);
        beanFactory.registerBeanDefinition(NAME, bootFactoryDefinition("jpaConfiguration"));
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    @Test
    void parentDefinitionIsRejected() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        RootBeanDefinition parent = new RootBeanDefinition();
        parent.setAbstract(true);
        beanFactory.registerBeanDefinition("parentDefinition", parent);
        AnnotatedGenericBeanDefinition child = bootFactoryDefinition("jpaConfiguration");
        child.setParentName("parentDefinition");
        beanFactory.removeBeanDefinition(NAME);
        beanFactory.registerBeanDefinition(NAME, child);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    @Test
    void additionalUnknownFactoryBeanRemainsRejected() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        beanFactory.registerBeanDefinition("unknownFactory", new RootBeanDefinition(CountingFactoryBean.class));
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    /** The singleton is intentionally constructed once by the test before inspection. */
    @Test
    void manuallyRegisteredFactoryBeanSingletonRemainsRejected() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        beanFactory.registerSingleton("manualFactory", new CountingFactoryBean());
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("intentional pre-existing instance").isEqualTo(1);
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("no additional construction").isEqualTo(1);
        assertThat(CountingFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(CountingFactoryBean.GET_OBJECT_TYPE.get()).isZero();
        assertThat(CustomJpaConfiguration.CONSTRUCTED.get()).isZero();
    }

    // ---- REAL bean factory: missing configuration and abstract ----

    /**
     * Missing configuration: the helper rejects it before any type lookup. On the public path, isFactoryBean predicts
     * the factory-method type, which calls getType(factoryBeanName) (AACBF getTypeForFactoryMethod) and reaches
     * getMergedLocalBeanDefinition -> DefaultListableBeanFactory.getBeanDefinition, which throws
     * NoSuchBeanDefinitionException with no catch on that path; the guard maps it to DEFINITION_INSPECTION_FAILED.
     */
    @Test
    void missingConfigurationIsNotRecognized() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, bootFactoryDefinition("missingConfiguration"));
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertRejected(beanFactory, true, Reason.DEFINITION_INSPECTION_FAILED);
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    /** The guard skips abstract definitions globally; only the helper's refusal is asserted. */
    @Test
    void abstractDefinitionIsNotRecognized() {
        DefaultListableBeanFactory beanFactory = bootJpaDefinitions();
        ((org.springframework.beans.factory.support.AbstractBeanDefinition)
                beanFactory.getBeanDefinition(NAME)).setAbstract(true);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, NAME)).isFalse();
        assertNoSingletons(beanFactory);
        assertCustomCountersZero();
    }

    // ---- SYNTHETIC helper tests: exactly one condition differs from the accepted synthetic baseline ----

    @Test
    void syntheticBaselineIsRecognized() {
        assertThat(synthetic(new Baseline())).isTrue();
    }

    @Test
    void syntheticBeanNameDiffers() {
        Baseline b = new Baseline();
        b.beanName = "otherEntityManagerFactory";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticDeclaringClassDiffers() {
        Baseline b = new Baseline();
        b.declaringClass = "example.OtherConfiguration";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticMethodNameDiffers() {
        Baseline b = new Baseline();
        b.methodName = "otherFactory";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticReturnTypeDiffers() {
        Baseline b = new Baseline();
        b.returnType = "example.OtherFactoryBean";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticStaticMethod() {
        Baseline b = new Baseline();
        b.staticMethod = true;
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticConfigurationClassDiffers() {
        Baseline b = new Baseline();
        b.configurationClass = JpaBaseConfiguration.class.getName();
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticConfigurationMissing() {
        Baseline b = new Baseline();
        b.registerConfiguration = false;
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticConfigurationHasParent() {
        Baseline b = new Baseline();
        b.configurationParent = "parentDefinition";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticConfigurationHasFactoryMethod() {
        Baseline b = new Baseline();
        b.configurationFactoryMethod = "create";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticConfigurationHasFactoryBean() {
        Baseline b = new Baseline();
        b.configurationFactoryBean = "otherFactoryBean";
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticPredictedFactoryTypeNull() {
        Baseline b = new Baseline();
        b.predictedFactoryType = null;
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticPredictedFactoryTypeDifferentClass() {
        Baseline b = new Baseline();
        b.predictedFactoryType = CountingFactoryBean.class;
        assertThat(synthetic(b)).isFalse();
    }

    @Test
    void syntheticPredictedFactoryTypeSubclass() {
        Baseline b = new Baseline();
        b.predictedFactoryType = SubclassFactoryBean.class;
        assertThat(synthetic(b)).isFalse();
    }

    // ---- helpers ----

    private static void assertRejected(DefaultListableBeanFactory beanFactory, boolean controlled, Reason reason) {
        Throwable thrown = catchThrowable(
                () -> new TestDatabaseBeanDefinitionGuard(controlled).postProcessBeanFactory(beanFactory));
        assertThat(thrown).isInstanceOf(TestDatabaseGuardException.class);
        TestDatabaseGuardException rejection = (TestDatabaseGuardException) thrown;
        assertThat(rejection.stage()).isEqualTo(Stage.BEAN_DEFINITION);
        assertThat(rejection.reason()).isEqualTo(reason);
    }

    /** Singleton state only; it cannot detect transient calls during inspection. */
    private static void assertNoSingletons(DefaultListableBeanFactory beanFactory) {
        assertThat(beanFactory.getSingletonCount()).as("singletons").isZero();
        assertThat(beanFactory.containsSingleton("jpaConfiguration")).as("configuration singleton").isFalse();
        assertThat(beanFactory.containsSingleton(NAME)).as("factory singleton").isFalse();
    }

    private static void assertCustomCountersZero() {
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("custom factory constructed").isZero();
        assertThat(CountingFactoryBean.GET_OBJECT.get()).as("custom getObject").isZero();
        assertThat(CountingFactoryBean.GET_OBJECT_TYPE.get()).as("custom getObjectType").isZero();
        assertThat(CustomJpaConfiguration.CONSTRUCTED.get()).as("custom configuration constructed").isZero();
    }

    /** Real fixture: HibernateJpaConfiguration definition plus Boot's actual entityManagerFactory metadata. */
    private static DefaultListableBeanFactory bootJpaDefinitions() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition configuration = new RootBeanDefinition();
        configuration.setBeanClassName(HIBERNATE_JPA_CONFIGURATION);
        beanFactory.registerBeanDefinition("jpaConfiguration", configuration);
        beanFactory.registerBeanDefinition(NAME, bootFactoryDefinition("jpaConfiguration"));
        return beanFactory;
    }

    private static AnnotatedGenericBeanDefinition bootFactoryDefinition(String factoryBeanName) {
        return factoryMethodDefinition(JpaBaseConfiguration.class, "entityManagerFactory", factoryBeanName);
    }

    private static AnnotatedGenericBeanDefinition factoryMethodDefinition(Class<?> declaring, String method,
            String factoryBeanName) {
        AnnotationMetadata metadata = AnnotationMetadata.introspect(declaring);
        MethodMetadata factoryMethod = metadata.getAnnotatedMethods(Bean.class.getName()).stream()
                .filter(candidate -> candidate.getMethodName().equals(method)).findFirst().orElseThrow();
        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(metadata, factoryMethod);
        definition.setBeanClassName(null);
        definition.setFactoryBeanName(factoryBeanName);
        definition.setFactoryMethodName(method);
        return definition;
    }

    /** SYNTHETIC: builds the helper-level baseline with one mutable condition at a time. */
    private static boolean synthetic(Baseline b) {
        FixedFactoryTypeBeanFactory beanFactory = new FixedFactoryTypeBeanFactory(b.predictedFactoryType);
        if (b.registerConfiguration) {
            org.springframework.beans.factory.support.GenericBeanDefinition configuration =
                    new org.springframework.beans.factory.support.GenericBeanDefinition();
            configuration.setBeanClassName(b.configurationClass);
            configuration.setParentName(b.configurationParent);
            configuration.setFactoryMethodName(b.configurationFactoryMethod);
            configuration.setFactoryBeanName(b.configurationFactoryBean);
            beanFactory.registerBeanDefinition("jpaConfiguration", configuration);
        }
        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(
                AnnotationMetadata.introspect(Object.class),
                new SyntheticMethodMetadata(b.methodName, b.declaringClass, b.returnType, b.staticMethod));
        definition.setBeanClassName(null);
        definition.setFactoryBeanName("jpaConfiguration");
        definition.setFactoryMethodName(NAME);
        beanFactory.registerBeanDefinition(b.beanName, definition);
        boolean recognized = TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, b.beanName);
        assertThat(beanFactory.getSingletonCount()).as("synthetic singletons").isZero();
        assertCustomCountersZero();
        return recognized;
    }

    /** SYNTHETIC: one accepted baseline; each test changes exactly one field. */
    private static final class Baseline {
        String beanName = NAME;
        String declaringClass = TestDatabaseBeanDefinitionGuard.BOOT_JPA_BASE_CONFIGURATION_CLASS;
        String methodName = NAME;
        String returnType = TestDatabaseBeanDefinitionGuard.BOOT_JPA_FACTORY_CLASS;
        boolean staticMethod = false;
        String configurationClass = HIBERNATE_JPA_CONFIGURATION;
        boolean registerConfiguration = true;
        String configurationParent = null;
        String configurationFactoryMethod = null;
        String configurationFactoryBean = null;
        Class<?> predictedFactoryType = LocalContainerEntityManagerFactoryBean.class;
    }

    /** SYNTHETIC: fixes only the {@code &entityManagerFactory} type answer; never creates beans. */
    private static final class FixedFactoryTypeBeanFactory extends DefaultListableBeanFactory {

        private final Class<?> factoryType;

        FixedFactoryTypeBeanFactory(Class<?> factoryType) {
            this.factoryType = factoryType;
        }

        @Override
        public Class<?> getType(String name, boolean allowFactoryBeanInit) {
            if (FACTORY_REFERENCE.equals(name) || name.startsWith(BeanFactory.FACTORY_BEAN_PREFIX)) {
                return factoryType;
            }
            return super.getType(name, allowFactoryBeanInit);
        }
    }

    /** SYNTHETIC metadata double; carries no annotations. */
    private record SyntheticMethodMetadata(String methodName, String declaringClass, String returnType,
            boolean staticMethod) implements MethodMetadata {

        @Override
        public String getMethodName() {
            return methodName;
        }

        @Override
        public String getDeclaringClassName() {
            return declaringClass;
        }

        @Override
        public String getReturnTypeName() {
            return returnType;
        }

        @Override
        public boolean isAbstract() {
            return false;
        }

        @Override
        public boolean isStatic() {
            return staticMethod;
        }

        @Override
        public boolean isFinal() {
            return false;
        }

        @Override
        public boolean isOverridable() {
            return !staticMethod;
        }

        @Override
        public MergedAnnotations getAnnotations() {
            return MergedAnnotations.of(Collections.emptyList());
        }
    }

    /** Never instantiated; only its class is used as a predicted type. */
    abstract static class SubclassFactoryBean extends LocalContainerEntityManagerFactoryBean {
    }

    static class CustomJpaConfiguration {

        static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        CustomJpaConfiguration() {
            CONSTRUCTED.incrementAndGet();
        }

        @Bean
        CountingFactoryBean entityManagerFactory() {
            return new CountingFactoryBean();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean otherFactory() {
            return new LocalContainerEntityManagerFactoryBean();
        }

        @Bean
        static CountingFactoryBean staticFactory() {
            return new CountingFactoryBean();
        }
    }

    static class CountingFactoryBean implements FactoryBean<Object> {

        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        static final AtomicInteger GET_OBJECT = new AtomicInteger();
        static final AtomicInteger GET_OBJECT_TYPE = new AtomicInteger();

        CountingFactoryBean() {
            CONSTRUCTED.incrementAndGet();
        }

        @Override
        public Object getObject() {
            GET_OBJECT.incrementAndGet();
            return new Object();
        }

        @Override
        public Class<?> getObjectType() {
            GET_OBJECT_TYPE.incrementAndGet();
            return Object.class;
        }
    }
}
