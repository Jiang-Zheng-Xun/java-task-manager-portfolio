package io.github.jiangzhengxun.taskmanager.support.testdb;

import static net.bytebuddy.matcher.ElementMatchers.named;

import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.FinalResult;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Point;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Session;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.Installer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * Installs exact-name advice transformers using only the {@link Instrumentation} stored by the startup
 * {@code -javaagent} premain ({@code Installer.getInstrumentation()}). Never calls {@code ByteBuddyAgent.install()},
 * never self-attaches, never attaches externally and never retransforms.
 *
 * <p>Artifact identity: each relevant class must be served by exactly one class-path resource, its code source must be
 * a regular jar file, and that file's SHA256 must equal the reviewed hash below. No machine-specific path is encoded;
 * the actual path comes from the running JVM. Target classes are only ever referred to by name.
 */
final class NativeLifecycleInstrumentation {

    /** Reviewed artifact SHA256 values (evidence: recovery-2 B14, correction-3 P10). */
    static final String AGENT_SHA256 = "5b17113e66e77ca6f8af07ff367c216df964a980f0e9f11e3b1aa793253aa64f";
    static final String BYTE_BUDDY_SHA256 = "2b5ddc8c1f4234bdb7cb45338a8e10a13e0e3ca473e91d5d821d681127ea8ba1";
    static final Map<String, String> TARGET_SHA256 = Map.of(
            "com.zaxxer.hikari.HikariDataSource", "709f378c05756280939ce50fc1b1f1a53bb8e1899dc1b249f21f12703640b48b",
            "org.postgresql.Driver", "31fbf6f06b2217fb51d5100cee51b22625cc81640da0679b47914e54c1e6377c");

    /** Test-only one-shot transform fault (T1): the exact target type name while pending, null otherwise. */
    private static final AtomicReference<String> INDUCE_TRANSFORM_FAILURE_FOR = new AtomicReference<>();
    private static final AtomicInteger INDUCED_TRANSFORM_FAILURES = new AtomicInteger();

    private final Instrumentation instrumentation;
    private final Session session;
    private final ResettableClassFileTransformer transformer;
    /** Test-only: times the cleanup sequence was claimed; at most one per instance, not per finish() call. */
    private final AtomicInteger cleanupRuns = new AtomicInteger();
    // Guarded by this: the published result, and whether cleanup was already claimed (even if it ended abnormally).
    private FinalResult result;
    private boolean cleanupClaimed;
    /** Test-only (P1n): per-instance one-shot pause after the freeze, before reset/detach; null (off) by default. */
    private final AtomicReference<FreezePause> freezePause = new AtomicReference<>();

    private NativeLifecycleInstrumentation(Instrumentation instrumentation, Session session,
            ResettableClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.session = session;
        this.transformer = transformer;
    }

    /**
     * Bootstrap order: (1) verify startup agent argument, agent and Byte Buddy artifact identity; (2) obtain the
     * premain Instrumentation; (3) refuse if any target type is already loaded; (4) verify target resources are unique
     * on the class path without loading them; (5) start the session; (6) install transformers; (7) re-check that no
     * target was loaded between (3) and (6). Failures in (1)-(4) throw; later problems invalidate the session.
     */
    static NativeLifecycleInstrumentation install(Set<Point> instrumented, Set<Point> required) {
        Instrumentation instrumentation = startupInstrumentation();
        Set<String> targetTypes = typeNames(required, instrumented);
        Set<String> preloaded = loadedTargets(instrumentation, targetTypes);
        if (!preloaded.isEmpty()) {
            throw new IllegalStateException("target types already loaded before install: " + preloaded);
        }
        ClassLoader loader = NativeLifecycleInstrumentation.class.getClassLoader();
        for (String typeName : targetTypes) {
            requireSingleResource(loader, typeName);
        }
        Session session = NativeLifecycleObservation.start(required);
        ResettableClassFileTransformer transformer;
        try {
            // Verified by javap (correction-4 I01/I02/J01): with(RedefinitionStrategy) returns WithoutBatchStrategy ->
            // WithImplicitDiscoveryStrategy -> RedefinitionListenable -> AgentBuilder; transform returns
            // Identified.Extendable, which extends AgentBuilder.
            AgentBuilder builder = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(new ReportingListener(session, targetTypes))
                    .with(AgentBuilder.RedefinitionStrategy.DISABLED);
            for (String typeName : typeNames(Set.of(), instrumented)) {
                builder = builder.type(named(typeName))
                        .transform(new ObservingTransformer(session, typeName, instrumented));
            }
            transformer = builder.installOn(instrumentation);
        } catch (RuntimeException failure) {
            NativeLifecycleObservation.detach(session);
            throw failure;
        }
        Set<String> racing = loadedTargets(instrumentation, targetTypes);
        if (!racing.isEmpty()) {
            session.invalidate("target types loaded during install: " + racing);
        }
        return new NativeLifecycleInstrumentation(instrumentation, session, transformer);
    }

    Session session() {
        return session;
    }

    /**
     * Test-only (P1n): installs the one-shot freeze pause. Must be called before any finisher starts; a second pause is
     * refused. Does not take this instance's monitor, so it never waits behind a paused finish().
     */
    void installFreezePause(FreezePause pause) {
        if (pause == null) {
            throw new NullPointerException("pause");
        }
        if (!freezePause.compareAndSet(null, pause)) {
            throw new IllegalStateException("freeze pause already installed");
        }
    }

    /**
     * Test-only: removes the freeze pause without taking this instance's monitor. It does not release a paused finisher;
     * release the pause object first.
     */
    void clearFreezePause() {
        freezePause.set(null);
    }

    private void pauseAfterFreeze() {
        FreezePause pause = freezePause.get();
        if (pause != null) {
            pause.pause();
        }
    }

    /**
     * Test-only (P1n) one-shot pause inside the first {@link #finish()}: after the session has frozen its result and
     * before the transformer is removed and the session detached. Release and diagnostics never need this instance's
     * monitor (held by the paused finish()). The first arrival takes ownership by CAS, signals {@link #arrived} and
     * waits at most {@link #RELEASE_WAIT_SECONDS} for {@link #release}. A timeout, an interrupt (status restored) or any
     * other failure is recorded here for the test and never propagates, so reset and detach still run; the frozen
     * result is not altered.
     */
    static final class FreezePause {

        static final long RELEASE_WAIT_SECONDS = 10;

        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean owned = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean timedOut;

        boolean fired() {
            return owned.get();
        }

        Throwable failure() {
            return failure.get();
        }

        boolean timedOut() {
            return timedOut;
        }

        private void pause() {
            if (!owned.compareAndSet(false, true)) {
                return;
            }
            try {
                arrived.countDown();
                if (!release.await(RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    timedOut = true;
                }
            } catch (InterruptedException interrupted) {
                failure.compareAndSet(null, interrupted);
                Thread.currentThread().interrupt();
            } catch (Throwable other) {
                failure.compareAndSet(null, other);
            }
        }
    }

    /**
     * Finalizes the observation exactly once: (1) close admission and freeze events under the session write lock,
     * (2) remove the transformer, (3) detach the session. A removal that returns false or throws, or a failed detach,
     * makes the final result INVALID. Removing the transformer only stops future class transformation; classes already
     * transformed keep their inlined advice, and their later events are counted as late/unattributed. Observer errors
     * raised while removing the transformer occur after the boundary: they are late failures (diagnostics only) and the
     * removal outcome itself is what invalidates. Caller obligation: all observed native calls have returned.
     *
     * <p>Concurrency: the whole method holds this instance's monitor, so concurrent and later callers get the same
     * published instance and the cleanup sequence is claimed at most once. Each cleanup step runs in its own
     * try/finally, so a RuntimeException in one step does not skip the later steps; RuntimeExceptions become INVALID
     * reasons. If the session cannot be finished, no frozen snapshot exists and the result is INVALID with no events.
     * Errors (for example OutOfMemoryError) are not caught. A step's finally protection applies only once its try has
     * been entered: an Error raised after cleanupClaimed is set but before the first try (for example while allocating
     * the failure list) can propagate before reset or detach have run. When an Error propagates, no result is published
     * and later calls refuse to re-enter cleanup; that refusal does not mean cleanup succeeded. Recovery from
     * OutOfMemoryError or StackOverflowError is not claimed, and the actions inside the finally blocks can fail too.
     *
     * <p>Test-only: an installed {@link FreezePause} holds the first call (and this monitor) after step (1) and before
     * step (2); it never throws, so reset and detach still run, and it does not alter the frozen result. Control P1n uses
     * it to overlap a native call with finish() on purpose, breaking the caller obligation above to study the event
     * boundary; that overlap is not thereby a supported use.
     */
    synchronized FinalResult finish() {
        if (result != null) {
            return result;
        }
        if (cleanupClaimed) {
            throw new IllegalStateException("finish cleanup previously ended abnormally; cleanup is not re-entered");
        }
        cleanupClaimed = true;
        cleanupRuns.incrementAndGet();
        List<String> cleanupFailures = new ArrayList<>();
        FinalResult frozen = null;
        try {
            // Admission must be closed first so no in-flight event can change the final result.
            frozen = session.finish(List.of());
            pauseAfterFreeze();
        } catch (RuntimeException failure) {
            cleanupFailures.add("session finish failed: " + failure.getClass().getName());
        } finally {
            try {
                if (!transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.DISABLED)) {
                    cleanupFailures.add("transformer removal returned false");
                }
            } catch (RuntimeException failure) {
                cleanupFailures.add("transformer removal failed: " + failure.getClass().getName());
            } finally {
                if (!NativeLifecycleObservation.detach(session)) {
                    cleanupFailures.add("session was not the active session at detach");
                }
            }
        }
        if (frozen == null) {
            cleanupFailures.add("no frozen event snapshot");
            result = new FinalResult(NativeLifecycleObservation.Status.INVALID, cleanupFailures, Set.of(), List.of());
        } else if (cleanupFailures.isEmpty()) {
            result = frozen;
        } else {
            List<String> reasons = new ArrayList<>(frozen.invalidReasons());
            reasons.addAll(cleanupFailures);
            result = new FinalResult(NativeLifecycleObservation.Status.INVALID, reasons, frozen.required(),
                    frozen.events());
        }
        return result;
    }

    /** Test-only: how many times the cleanup sequence was claimed. Proves one start, not that every step succeeded. */
    int cleanupRuns() {
        return cleanupRuns.get();
    }

    /**
     * Test-only (T1): the next transform of {@code org.postgresql.Driver} by an installed {@link ObservingTransformer}
     * throws an {@link NativeLifecycleObservation.InducedObservationFailure} at its entry, before any check or advice
     * change. One shot: the hit consumes the fault before throwing. Refuses if a fault is already pending, so an earlier
     * unconsumed fault is not hidden. Byte Buddy's own error path (listener onError, then its wrapping exception) is
     * left unchanged; what the JVM then does with the class load is not decided here.
     */
    static void induceTransformFailureOnPgDriver() {
        if (!INDUCE_TRANSFORM_FAILURE_FOR.compareAndSet(null, Point.PG_DRIVER_CONNECT.typeName())) {
            throw new IllegalStateException("induced transform failure already pending");
        }
    }

    /** Test-only: clears a pending transform fault; returns true if one was still pending. */
    static boolean clearInducedTransformFailure() {
        return INDUCE_TRANSFORM_FAILURE_FOR.getAndSet(null) != null;
    }

    static boolean inducedTransformFailurePending() {
        return INDUCE_TRANSFORM_FAILURE_FOR.get() != null;
    }

    /** Test-only: how many induced transform faults were consumed and thrown. */
    static int inducedTransformFailures() {
        return INDUCED_TRANSFORM_FAILURES.get();
    }

    static Instrumentation startupInstrumentation() {
        long agentArguments = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:"))
                .count();
        if (agentArguments != 1) {
            throw new IllegalStateException("expected exactly one startup -javaagent argument, found " + agentArguments);
        }
        if (Installer.class.getClassLoader() != ClassLoader.getSystemClassLoader()) {
            throw new IllegalStateException("byte-buddy-agent Installer not on the system class loader");
        }
        requireSingleResource(ClassLoader.getSystemClassLoader(), Installer.class.getName());
        requireArtifact(Installer.class.getProtectionDomain(), AGENT_SHA256, "byte-buddy-agent");
        requireSingleResource(AgentBuilder.class.getClassLoader(), AgentBuilder.class.getName());
        requireArtifact(AgentBuilder.class.getProtectionDomain(), BYTE_BUDDY_SHA256, "byte-buddy");
        // Returns only the instance stored by premain/agentmain; it never attaches.
        Instrumentation instrumentation = Installer.getInstrumentation();
        if (instrumentation == null) {
            throw new IllegalStateException("startup agent Instrumentation unavailable");
        }
        return instrumentation;
    }

    static Set<String> loadedTargets(Instrumentation instrumentation, Set<String> typeNames) {
        Set<String> loaded = new TreeSet<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (typeNames.contains(type.getName())) {
                loaded.add(type.getName());
            }
        }
        return loaded;
    }

    /** Rejects ambiguous class-path sources: the class file must be visible through exactly one resource. */
    static void requireSingleResource(ClassLoader loader, String typeName) {
        String resource = typeName.replace('.', '/') + ".class";
        List<URL> urls;
        try {
            urls = Collections.list(loader.getResources(resource));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("resource lookup failed for " + typeName, e);
        }
        if (urls.size() != 1) {
            throw new IllegalStateException("expected exactly one class-path source for " + typeName + ", found "
                    + urls.size());
        }
    }

    /** Verifies the actual code-source file against the reviewed SHA256. */
    static void requireArtifact(ProtectionDomain domain, String expectedSha256, String label) {
        String actual = codeSourceSha256(domain);
        if (!expectedSha256.equals(actual)) {
            throw new IllegalStateException("unexpected " + label + " artifact");
        }
    }

    static String codeSourceSha256(ProtectionDomain domain) {
        try {
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return "";
            }
            Path path = Path.of(location.toURI());
            if (!Files.isRegularFile(path)) {
                return "";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static Set<String> typeNames(Set<Point> first, Set<Point> second) {
        Set<String> names = new TreeSet<>();
        first.forEach(point -> names.add(point.typeName()));
        second.forEach(point -> names.add(point.typeName()));
        return names;
    }

    /** Matches exactly one point (declaring type, internal name, descriptor) and marks it armed. */
    private static final class ArmingMatcher implements ElementMatcher<MethodDescription> {

        private final Session session;
        private final Point point;

        ArmingMatcher(Session session, Point point) {
            this.session = session;
            this.point = point;
        }

        @Override
        public boolean matches(MethodDescription method) {
            if (!method.getDeclaringType().asErasure().getName().equals(point.typeName())
                    || !method.getInternalName().equals(point.methodName())
                    || !method.getDescriptor().equals(point.descriptor())) {
                return false;
            }
            session.markArmed(point);
            return true;
        }
    }

    private static final class ObservingTransformer implements AgentBuilder.Transformer {

        private final Session session;
        private final String typeName;
        private final Set<Point> points = EnumSet.noneOf(Point.class);

        ObservingTransformer(Session session, String typeName, Set<Point> instrumented) {
            this.session = session;
            this.typeName = typeName;
            for (Point point : instrumented) {
                if (point.typeName().equals(typeName)) {
                    points.add(point);
                }
            }
        }

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription type,
                ClassLoader classLoader, JavaModule module, ProtectionDomain protectionDomain) {
            // T1 only: for this transformer's exact target, consume the one-shot fault and throw before any advice change.
            if (type.getName().equals(typeName) && INDUCE_TRANSFORM_FAILURE_FOR.compareAndSet(typeName, null)) {
                INDUCED_TRANSFORM_FAILURES.incrementAndGet();
                throw new NativeLifecycleObservation.InducedObservationFailure();
            }
            if (!type.getName().equals(typeName)) {
                session.invalidate("transformer type mismatch");
                return builder;
            }
            if (!TARGET_SHA256.get(typeName).equals(codeSourceSha256(protectionDomain))) {
                session.invalidate("unexpected code source artifact for " + typeName);
                return builder;
            }
            if (!sinkVisible(classLoader)) {
                session.invalidate("observation sink not visible from loader of " + typeName);
                return builder;
            }
            DynamicType.Builder<?> result = builder;
            for (Point point : points) {
                Class<?> template = point.isConstructor() ? NativeLifecycleAdvice.ConstructorAdvice.class
                        : point.isVoid() ? NativeLifecycleAdvice.VoidMethodAdvice.class
                        : NativeLifecycleAdvice.ValueMethodAdvice.class;
                result = result.visit(Advice.withCustomMapping()
                        .bind(NativeLifecycleAdvice.PointId.class, (Object) point.id())
                        .to(template)
                        .withExceptionHandler(NativeLifecycleAdvice.failureHandler())
                        .on(new ArmingMatcher(session, point)));
            }
            return result;
        }

        private static boolean sinkVisible(ClassLoader classLoader) {
            try {
                return Class.forName(NativeLifecycleObservation.class.getName(), false, classLoader)
                        == NativeLifecycleObservation.class;
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
    }

    private static final class ReportingListener extends AgentBuilder.Listener.Adapter {

        private final Session session;
        private final Set<String> targetTypes;

        ReportingListener(Session session, Set<String> targetTypes) {
            this.session = session;
            this.targetTypes = targetTypes;
        }

        @Override
        public void onTransformation(TypeDescription type, ClassLoader classLoader, JavaModule module,
                boolean loaded, DynamicType dynamicType) {
            if (loaded) {
                session.invalidate("unexpected retransformation of " + type.getName());
                return;
            }
            session.markTransformed(type.getName());
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                Throwable throwable) {
            if (targetTypes.contains(typeName)) {
                session.invalidate("transformation error for " + typeName + ": " + throwable.getClass().getName());
            }
        }
    }
}
