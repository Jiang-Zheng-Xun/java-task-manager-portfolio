package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;

/**
 * No-DB checks of the shared Boot metadata-reader factory recognition
 * ({@link TestDatabaseBeanDefinitionGuard#isBootMetadataFactoryDefinition}) and its use in the public guard path.
 *
 * <p>The positive baseline is built through the SAME public overload Boot uses,
 * {@code BeanDefinitionBuilder.rootBeanDefinition(Class, Supplier)}, which (spring-beans 6.2.19) yields a
 * RootBeanDefinition with an explicit target type, an instance supplier and NO bean class. Boot's package-private
 * SharedMetadataReaderFactoryBean class is loaded by name WITHOUT initialization and never instantiated; suppliers are
 * test-owned, throwing and counted. These are SYNTHETIC definitions: their fidelity to Boot's registration rests on the
 * builder source; actual Boot execution remains unverified until a separately authorized batch. Passing the FactoryBean
 * stage on the public path is shown by the later {@code DATASOURCE_UNSUPPORTED} rejection (no DataSource is defined).
 */
class TestDatabaseMetadataFactoryDefinitionGuardTest {

    private static final String NAME = TestDatabaseBeanDefinitionGuard.BOOT_METADATA_FACTORY_BEAN_NAME;
    private static final AtomicInteger SUPPLIER_CALLS = new AtomicInteger();
    private static final Supplier<Object> THROWING_SUPPLIER = () -> {
        SUPPLIER_CALLS.incrementAndGet();
        throw new AssertionError("SUPPLIER:INVOKED");
    };

    @BeforeEach
    void reset() {
        SUPPLIER_CALLS.set(0);
        CountingFactoryBean.CONSTRUCTED.set(0);
        CountingFactoryBean.GET_OBJECT.set(0);
        CountingFactoryBean.GET_OBJECT_TYPE.set(0);
    }

    // ---- builder baseline shape ----

    @Test
    void builderBaselineHasBootShape() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        assertThat(definition.getClass()).isEqualTo(RootBeanDefinition.class);
        assertThat(definition.hasBeanClass()).isFalse();
        assertThat(definition.getBeanClassName()).isNull();
        assertThat(definition.getTargetType()).isEqualTo(bootClass());
        assertThat(definition.getInstanceSupplier()).isNotNull();
        assertThat(definition.getFactoryMethodName()).isNull();
        assertThat(definition.getFactoryBeanName()).isNull();
        assertThat(definition.getParentName()).isNull();
        assertThat(definition.isAbstract()).isFalse();
        assertNoSideEffects();
    }

    // ---- helper: builder baseline accepted; one condition changed per case where possible ----

    @Test
    void helperAcceptsExactDefinition() throws ClassNotFoundException {
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, built(), null)).isTrue();
        assertNoSideEffects();
    }

    /** Previous (incorrect) bean-class-only fixture shape; retained as a negative case. */
    @Test
    void helperRejectsOldBeanClassShape() throws ClassNotFoundException {
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, oldBeanClassShape(), null))
                .isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsBeanClassPlusMatchingTargetType() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        definition.setBeanClass(bootClass());
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsMissingTargetType() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsWrongTargetType() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setTargetType(ResolvableType.forClass(CountingFactoryBean.class));
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsBroadTargetType() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setTargetType(ResolvableType.forClass(FactoryBean.class));
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    /** Retained: a custom bean class with a supplier (no target type) is not the metadata shape. */
    @Test
    void helperRejectsWrongClass() {
        RootBeanDefinition definition = new RootBeanDefinition(CountingFactoryBean.class);
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsWrongName() throws ClassNotFoundException {
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME + "X", built(), null))
                .isFalse();
        assertNoSideEffects();
    }

    /** Retained: GenericBeanDefinition cannot carry a target type; this changes the definition class (and shape). */
    @Test
    void helperRejectsNonRootDefinition() throws ClassNotFoundException {
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(bootClass());
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsMissingSupplier() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        definition.setInstanceSupplier(null);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsFactoryMethodName() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        definition.setFactoryMethodName("create");
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsFactoryBeanName() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        definition.setFactoryBeanName("otherFactory");
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    /** A RootBeanDefinition cannot carry a parent; this case necessarily changes the definition class too. */
    @Test
    void helperRejectsParentDefinition() throws ClassNotFoundException {
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(bootClass());
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        definition.setParentName("parentDefinition");
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    /** The guard skips abstract definitions globally; only the helper's refusal is asserted. */
    @Test
    void helperRejectsAbstractDefinition() throws ClassNotFoundException {
        RootBeanDefinition definition = built();
        definition.setAbstract(true);
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, definition, null)).isFalse();
        assertNoSideEffects();
    }

    @Test
    void helperRejectsExistingSingletonOfDifferentClass() throws ClassNotFoundException {
        Object other = new Object();
        assertThat(TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(NAME, built(), other)).isFalse();
        assertNoSideEffects();
    }

    // ---- public guard path (real DefaultListableBeanFactory) ----

    @Test
    void exactDefinitionPassesFactoryBeanStageInControlledMode() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, built());
        assertRejected(beanFactory, true, Reason.DATASOURCE_UNSUPPORTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void exactDefinitionPassesFactoryBeanStageInNoncontrolledMode() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, built());
        assertRejected(beanFactory, false, Reason.DATASOURCE_UNSUPPORTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void oldBeanClassShapeIsRejectedOnPublicPath() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, oldBeanClassShape());
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void sameNameWrongClassIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(CountingFactoryBean.class);
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        beanFactory.registerBeanDefinition(NAME, definition);
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void sameClassWrongNameIsRejected() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME + "X", built());
        assertRejected(beanFactory, false, Reason.FACTORY_BEAN_REJECTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    /** The existing singleton is intentionally constructed once by the test. */
    @Test
    void existingSingletonOfDifferentFactoryBeanClassIsRejected() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, built());
        beanFactory.registerSingleton(NAME, new CountingFactoryBean());
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("intentional instance").isEqualTo(1);
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("no additional construction").isEqualTo(1);
        assertThat(CountingFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(CountingFactoryBean.GET_OBJECT_TYPE.get()).isZero();
        assertThat(SUPPLIER_CALLS.get()).isZero();
    }

    /** Singleton loop: a FactoryBean singleton without a definition stays rejected, even under the metadata name. */
    @Test
    void factoryBeanSingletonWithoutDefinitionIsRejected() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(NAME, new CountingFactoryBean());
        assertRejected(beanFactory, false, Reason.FACTORY_BEAN_REJECTED);
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).isEqualTo(1);
        assertThat(CountingFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(CountingFactoryBean.GET_OBJECT_TYPE.get()).isZero();
    }

    @Test
    void unknownFactoryBeanBesideExactDefinitionIsRejected() throws ClassNotFoundException {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(NAME, built());
        beanFactory.registerBeanDefinition("unknownFactory", new RootBeanDefinition(CountingFactoryBean.class));
        assertRejected(beanFactory, true, Reason.FACTORY_BEAN_REJECTED);
        assertNoSideEffects();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    /**
     * Regression for the verifiability check: a recognized metadata definition's product type is not queried through
     * getType(name, false) by the guard, in either mode. SYNTHETIC counting factory; no singleton exists here, so the
     * real existing-singleton path (and later getBeanNamesForType enumeration, which may call getObjectType on an
     * existing FactoryBean singleton, accepted by contract) remains unverified until a separately authorized batch.
     */
    @Test
    void recognizedDefinitionIsNotTypeQueriedByVerifiabilityCheck() throws ClassNotFoundException {
        for (boolean controlled : new boolean[] {true, false}) {
            TypeQueryCountingBeanFactory beanFactory = new TypeQueryCountingBeanFactory();
            beanFactory.registerBeanDefinition(NAME, built());
            assertRejected(beanFactory, controlled, Reason.DATASOURCE_UNSUPPORTED);
            assertThat(beanFactory.metadataTypeQueries).as("getType(metadata, false) calls").isZero();
            assertNoSideEffects();
            assertThat(beanFactory.getSingletonCount()).isZero();
        }
    }

    /** SYNTHETIC: counts only getType(NAME, false) calls; delegates unchanged. */
    static final class TypeQueryCountingBeanFactory extends DefaultListableBeanFactory {

        int metadataTypeQueries;

        @Override
        public Class<?> getType(String name, boolean allowFactoryBeanInit) {
            if (NAME.equals(name)) {
                metadataTypeQueries++;
            }
            return super.getType(name, allowFactoryBeanInit);
        }
    }

    // ---- helpers ----

    private static Class<?> bootClass() throws ClassNotFoundException {
        // Loaded without initialization; never instantiated.
        return Class.forName(TestDatabaseBeanDefinitionGuard.BOOT_METADATA_FACTORY_CLASS, false,
                TestDatabaseMetadataFactoryDefinitionGuardTest.class.getClassLoader());
    }

    /** Boot's actual shape: BeanDefinitionBuilder.rootBeanDefinition(Class, Supplier). */
    private static RootBeanDefinition built() throws ClassNotFoundException {
        return builtFor(bootClass());
    }

    /**
     * Test-only generic adaptation: binds T to the (captured) class so the builder overload
     * rootBeanDefinition(Class&lt;T&gt;, Supplier&lt;T&gt;) can be called; the counting supplier throws and is never
     * expected to run. spring-beans 6.2.19 BeanDefinitionBuilder creates a RootBeanDefinition (line 129).
     */
    private static <T> RootBeanDefinition builtFor(Class<T> type) {
        Supplier<T> supplier = () -> {
            SUPPLIER_CALLS.incrementAndGet();
            throw new AssertionError("SUPPLIER:INVOKED");
        };
        AbstractBeanDefinition definition = BeanDefinitionBuilder.rootBeanDefinition(type, supplier).getBeanDefinition();
        return (RootBeanDefinition) definition;
    }

    /** Previous incorrect fixture: bean class plus supplier, no target type. */
    private static RootBeanDefinition oldBeanClassShape() throws ClassNotFoundException {
        RootBeanDefinition definition = new RootBeanDefinition(bootClass());
        definition.setInstanceSupplier(THROWING_SUPPLIER);
        return definition;
    }

    private static void assertRejected(DefaultListableBeanFactory beanFactory, boolean controlled, Reason reason) {
        Throwable thrown = catchThrowable(
                () -> new TestDatabaseBeanDefinitionGuard(controlled).postProcessBeanFactory(beanFactory));
        assertThat(thrown).isInstanceOf(TestDatabaseGuardException.class);
        TestDatabaseGuardException rejection = (TestDatabaseGuardException) thrown;
        assertThat(rejection.stage()).isEqualTo(Stage.BEAN_DEFINITION);
        assertThat(rejection.reason()).isEqualTo(reason);
    }

    private static void assertNoSideEffects() {
        assertThat(SUPPLIER_CALLS.get()).as("supplier invoked").isZero();
        assertThat(CountingFactoryBean.CONSTRUCTED.get()).as("custom factory constructed").isZero();
        assertThat(CountingFactoryBean.GET_OBJECT.get()).as("custom getObject").isZero();
        assertThat(CountingFactoryBean.GET_OBJECT_TYPE.get()).as("custom getObjectType").isZero();
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
