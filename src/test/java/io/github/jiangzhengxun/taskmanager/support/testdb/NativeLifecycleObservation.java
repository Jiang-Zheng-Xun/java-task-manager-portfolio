package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe sink for native method observations (test-only spike, no database access).
 *
 * <p>Must stay {@code public}: advice is inlined into third-party classes in other packages and calls the static
 * {@code on*} methods directly. The {@code on*} methods convert their own failures into invalidation; failures that
 * escape into advice are suppressed there and reported through {@link NativeLifecycleAdvice#ADVICE_FAILURE_PROPERTY}.
 *
 * <p>Events carry only a fixed point, a kind and, for exceptional exits, the throwable class name. Target objects,
 * arguments, URLs, properties, credentials, SQL and connections are never stored. Counts are per point; different
 * points are different layers and are never summed into one connection-attempt count.
 *
 * <p>Observation window and finalization boundary. The window runs from {@link #start} until {@link Session#finish}
 * acquires the session write lock. Event admission and every sink-side failure are handled while holding the read lock,
 * so they are either fully applied before the boundary or seen as late after it. In addition, every observer failure
 * first increments a global failure counter (before taking any lock), and the advice failure handler sets a JVM-global
 * flag; both are read under the write lock. A failure whose increment or flag write happened before that read makes the
 * final result INVALID; anything later is a late failure, reported in diagnostics, and never changes the frozen
 * result. Caller obligation: every observed native call must have returned (and any background work the caller cares
 * about must have completed) before {@code finish}; the lock only orders recording, it does not prove that native
 * calls or background work have ended. A call still in flight at the boundary leaves an unpaired ENTRY, which makes the
 * result INVALID.
 */
public final class NativeLifecycleObservation {

    /** Exact observation points: binary type name, internal method name and JVM descriptor. */
    public enum Point {
        HIKARI_NO_ARG_CONSTRUCTOR("com.zaxxer.hikari.HikariDataSource", "<init>", "()V"),
        HIKARI_CLOSE("com.zaxxer.hikari.HikariDataSource", "close", "()V"),
        HIKARI_GET_CONNECTION_NO_ARG("com.zaxxer.hikari.HikariDataSource", "getConnection",
                "()Ljava/sql/Connection;"),
        PG_DRIVER_CONNECT("org.postgresql.Driver", "connect",
                "(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;");

        private final String typeName;
        private final String methodName;
        private final String descriptor;

        Point(String typeName, String methodName, String descriptor) {
            this.typeName = typeName;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        public String typeName() {
            return typeName;
        }

        public String methodName() {
            return methodName;
        }

        public String descriptor() {
            return descriptor;
        }

        public boolean isConstructor() {
            return "<init>".equals(methodName);
        }

        public boolean isVoid() {
            return descriptor.endsWith(")V");
        }

        public String id() {
            return typeName + "." + methodName + descriptor;
        }

        static Point fromId(String id) {
            for (Point point : values()) {
                if (point.id().equals(id)) {
                    return point;
                }
            }
            return null;
        }
    }

    /**
     * Event kinds. Constructor completion is recorded only after the constructor body returns normally; it is neither
     * pre-superclass-constructor entry nor evidence of pool initialization.
     */
    public enum Kind {
        ENTRY,
        RETURN_NULL,
        RETURN_NON_NULL,
        RETURN_VOID,
        THROWN,
        CONSTRUCTOR_COMPLETED
    }

    public enum Status {
        VALID,
        INVALID
    }

    public record Event(long sequence, Point point, Kind kind, String throwableClassName) {
        public Event {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Raised instead of returning a count from an invalid observation. */
    public static final class ObservationInvalidException extends IllegalStateException {
        ObservationInvalidException(String message) {
            super(message);
        }
    }

    /** Deliberate failure used only by the induced-failure controls. */
    static final class InducedObservationFailure extends RuntimeException {
        InducedObservationFailure() {
            super("induced observation failure", null, false, false);
        }
    }

    /** Live, diagnostic-only view. It has no accepted-count API and may change until and after finish. */
    public record DiagnosticSnapshot(boolean finished, List<String> invalidReasons, Set<Point> armed,
            Map<String, Integer> transformedTypes, List<Event> events, long lateEvents, long lateFailures) {
        public DiagnosticSnapshot {
            invalidReasons = List.copyOf(invalidReasons);
            armed = Set.copyOf(armed);
            transformedTypes = Map.copyOf(transformedTypes);
            events = List.copyOf(events);
        }
    }

    /** Immutable final result. Accepted counts exist only for VALID results and only for required points. */
    public record FinalResult(Status status, List<String> invalidReasons, Set<Point> required, List<Event> events) {
        public FinalResult {
            invalidReasons = List.copyOf(invalidReasons);
            required = Set.copyOf(required);
            events = List.copyOf(events);
        }

        public long acceptedCount(Point point, Kind kind) {
            if (status != Status.VALID) {
                throw new ObservationInvalidException("observation invalid: " + invalidReasons);
            }
            if (!required.contains(point)) {
                throw new ObservationInvalidException("point not required: " + point);
            }
            return events.stream().filter(e -> e.point() == point && e.kind() == kind).count();
        }
    }

    /** Test-only (S1/S2 and later synthetic controls): where a synchronisation hook may pause the calling thread. */
    enum HookPosition {
        /** In {@code admit}, before the admission read lock is requested. */
        BEFORE_ADMIT_LOCK,
        /** In {@code admit}, holding the read lock after the not-finished check, before the event and its sequence. */
        INSIDE_ADMIT_LOCK,
        /** In {@code fail}, after the observer failure counter increment and before the read lock is requested. */
        AFTER_FAILURE_COUNT
    }

    /**
     * Test-only one-shot synchronisation hook. Admission positions match exactly one point and kind; the failure-count
     * position takes neither, so it never matches an ordinary event through a null comparison. The first matching
     * thread takes ownership by CAS, signals {@link #arrived} and then waits at most {@link #RELEASE_WAIT_SECONDS} for
     * {@link #release}. A timeout, an interrupt (status restored) or any other hook failure is recorded on the hook for
     * the test to assert; it is never counted as an observer failure and never escapes into the observed path.
     */
    static final class Hook {

        static final long RELEASE_WAIT_SECONDS = 10;

        final HookPosition position;
        final Point point;
        final Kind kind;
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean owned = new AtomicBoolean();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean timedOut;

        private Hook(HookPosition position, Point point, Kind kind) {
            this.position = position;
            this.point = point;
            this.kind = kind;
        }

        /** A hook at an admission position, matching exactly this point and kind. */
        static Hook atAdmission(HookPosition position, Point point, Kind kind) {
            Objects.requireNonNull(position, "position");
            if (position == HookPosition.AFTER_FAILURE_COUNT) {
                throw new IllegalArgumentException("failure-count hook takes no point or kind; use afterFailureCount()");
            }
            return new Hook(position, Objects.requireNonNull(point, "point"), Objects.requireNonNull(kind, "kind"));
        }

        /** A hook after the observer failure counter increment; it has no point or kind. */
        static Hook afterFailureCount() {
            return new Hook(HookPosition.AFTER_FAILURE_COUNT, null, null);
        }

        boolean fired() {
            return owned.get();
        }

        Throwable failure() {
            return failure.get();
        }

        boolean timedOut() {
            return timedOut;
        }

        private boolean matches(HookPosition at, Point eventPoint, Kind eventKind) {
            if (position != at) {
                return false;
            }
            return at == HookPosition.AFTER_FAILURE_COUNT || (point == eventPoint && kind == eventKind);
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

    /** One observation session. At most one is active per JVM. */
    public static final class Session {

        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final Set<Point> required;
        private final Set<Point> armed = EnumSet.noneOf(Point.class);
        private final Map<String, Integer> transformedTypes = new HashMap<>();
        private final List<String> invalidReasons = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong lateEvents = new AtomicLong();
        private final AtomicLong lateFailures = new AtomicLong();
        private final long unattributedAtStart;
        private final long failuresAtStart;
        private volatile Point induceSinkFailureAt;
        private volatile boolean finished;
        private FinalResult result;
        private final Object hookInstall = new Object();
        /** Test-only synchronisation hooks; empty by default, at most two, published as an immutable list. */
        private volatile List<Hook> hooks = List.of();

        private Session(Set<Point> required, long unattributedAtStart, long failuresAtStart) {
            this.required = required.isEmpty() ? EnumSet.noneOf(Point.class) : EnumSet.copyOf(required);
            this.unattributedAtStart = unattributedAtStart;
            this.failuresAtStart = failuresAtStart;
        }

        /** Test-only: the sink fails internally (inside the read lock) on the next event for this point. */
        void induceSinkFailureAt(Point point) {
            induceSinkFailureAt = point;
        }

        /** Test-only: installs a synchronisation hook; at most two at a time, each instance at most once. */
        void installHook(Hook hook) {
            Objects.requireNonNull(hook, "hook");
            synchronized (hookInstall) {
                if (hooks.size() >= 2) {
                    throw new IllegalStateException("at most two hooks may be installed");
                }
                if (hooks.contains(hook)) {
                    throw new IllegalStateException("hook already installed");
                }
                List<Hook> next = new ArrayList<>(hooks);
                next.add(hook);
                hooks = List.copyOf(next);
            }
        }

        /** Test-only: removes every hook. Does not release a paused thread; release each hook first. */
        void clearHooks() {
            synchronized (hookInstall) {
                hooks = List.of();
            }
        }

        /** Test-only: whether the thread is queued on this session's lock; a precondition aid, not a safety proof. */
        boolean hasQueuedThread(Thread thread) {
            return lock.hasQueuedThread(thread);
        }

        private void pauseAt(HookPosition at, Point point, Kind kind) {
            List<Hook> current = hooks;
            for (int i = 0; i < current.size(); i++) {
                Hook hook = current.get(i);
                if (hook.matches(at, point, kind)) {
                    hook.pause();
                }
            }
        }

        /** Admission and any admission failure are applied under the same read lock as the finalization boundary. */
        void admit(Point point, Kind kind, String throwableClassName) {
            pauseAt(HookPosition.BEFORE_ADMIT_LOCK, point, kind);
            ReentrantReadWriteLock.ReadLock read = lock.readLock();
            read.lock();
            try {
                if (finished) {
                    lateEvents.incrementAndGet();
                    return;
                }
                pauseAt(HookPosition.INSIDE_ADMIT_LOCK, point, kind);
                try {
                    if (induceSinkFailureAt == point) {
                        induceSinkFailureAt = null;
                        throw new InducedObservationFailure();
                    }
                    synchronized (events) {
                        events.add(new Event(sequence.incrementAndGet(), point, kind, throwableClassName));
                    }
                } catch (Throwable failure) {
                    OBSERVER_FAILURES.incrementAndGet();
                    addReason("observation failure: " + failure.getClass().getName());
                }
            } finally {
                read.unlock();
            }
        }

        /** Records an observer failure inside the window, or counts it as late after the boundary. */
        void fail(String reason) {
            OBSERVER_FAILURES.incrementAndGet();
            pauseAt(HookPosition.AFTER_FAILURE_COUNT, null, null);
            ReentrantReadWriteLock.ReadLock read = lock.readLock();
            read.lock();
            try {
                if (finished) {
                    lateFailures.incrementAndGet();
                } else {
                    addReason(reason);
                }
            } finally {
                read.unlock();
            }
        }

        public void markArmed(Point point) {
            synchronized (armed) {
                armed.add(point);
            }
        }

        public void markTransformed(String typeName) {
            synchronized (transformedTypes) {
                transformedTypes.merge(typeName, 1, Integer::sum);
            }
        }

        /** Instrumentation-side invalidation (transformer/listener); same boundary rules as {@link #fail}. */
        public void invalidate(String reason) {
            fail(Objects.requireNonNull(reason, "reason"));
        }

        private void addReason(String reason) {
            synchronized (invalidReasons) {
                invalidReasons.add(reason);
            }
        }

        public DiagnosticSnapshot diagnostics() {
            List<String> reasons;
            synchronized (invalidReasons) {
                reasons = List.copyOf(invalidReasons);
            }
            Set<Point> armedNow;
            synchronized (armed) {
                armedNow = Set.copyOf(armed);
            }
            Map<String, Integer> transformedNow;
            synchronized (transformedTypes) {
                transformedNow = Map.copyOf(transformedTypes);
            }
            List<Event> eventsNow;
            synchronized (events) {
                eventsNow = List.copyOf(events);
            }
            return new DiagnosticSnapshot(finished, reasons, armedNow, transformedNow, eventsNow, lateEvents.get(),
                    lateFailures.get());
        }

        /**
         * Closes the window under the write lock and builds the final result once. Validity requires: no invalidation,
         * no observer failure counted and no advice failure flag set before the boundary, every required point armed,
         * every required type transformed exactly once, every method ENTRY matched by exactly one exit event, and no
         * unattributed events since the session started.
         */
        FinalResult finish(List<String> extraReasons) {
            ReentrantReadWriteLock.WriteLock write = lock.writeLock();
            write.lock();
            try {
                if (finished) {
                    return result;
                }
                finished = true;
                List<String> reasons;
                synchronized (invalidReasons) {
                    reasons = new ArrayList<>(invalidReasons);
                }
                reasons.addAll(extraReasons);
                if (OBSERVER_FAILURES.get() != failuresAtStart) {
                    reasons.add("observer failures during window");
                }
                if (System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY) != null) {
                    reasons.add("advice failure suppressed during window");
                }
                if (UNATTRIBUTED.get() != unattributedAtStart) {
                    reasons.add("unattributed events during session");
                }
                List<Event> frozen;
                synchronized (events) {
                    frozen = List.copyOf(events);
                }
                for (Point point : required) {
                    synchronized (armed) {
                        if (!armed.contains(point)) {
                            reasons.add("required point not armed: " + point);
                        }
                    }
                    Integer transformed;
                    synchronized (transformedTypes) {
                        transformed = transformedTypes.get(point.typeName());
                    }
                    if (transformed == null || transformed != 1) {
                        reasons.add("required type not transformed exactly once: " + point.typeName());
                    }
                    if (!point.isConstructor()) {
                        long entries = frozen.stream().filter(e -> e.point() == point && e.kind() == Kind.ENTRY)
                                .count();
                        long exits = frozen.stream().filter(e -> e.point() == point && e.kind() != Kind.ENTRY)
                                .count();
                        if (entries != exits) {
                            reasons.add("unpaired entry/exit events: " + point);
                        }
                    }
                }
                result = new FinalResult(reasons.isEmpty() ? Status.VALID : Status.INVALID, reasons, required, frozen);
                return result;
            } finally {
                write.unlock();
            }
        }
    }

    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private static final AtomicLong UNATTRIBUTED = new AtomicLong();
    private static final AtomicLong OBSERVER_FAILURES = new AtomicLong();
    private static volatile Point induceAdviceFaultAt;
    /** Test-only one-shot exit-advice fault (H2-H5): the pending point, or null. */
    private static final AtomicReference<Point> INDUCE_EXIT_ADVICE_FAULT_AT = new AtomicReference<>();
    private static final AtomicInteger EXIT_ADVICE_FAULT_HITS = new AtomicInteger();

    private NativeLifecycleObservation() {
    }

    /** Starts the single session; refuses if another is active or an advice failure was already flagged. */
    static Session start(Set<Point> required) {
        if (System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY) != null) {
            throw new ObservationInvalidException("advice failure flag already set before session start");
        }
        Session session = new Session(required, UNATTRIBUTED.get(), OBSERVER_FAILURES.get());
        if (!ACTIVE.compareAndSet(null, session)) {
            throw new ObservationInvalidException("observation session already active");
        }
        return session;
    }

    /** Detaches the session; returns false if it was not the active session. */
    static boolean detach(Session session) {
        return ACTIVE.compareAndSet(session, null);
    }

    static long unattributedEvents() {
        return UNATTRIBUTED.get();
    }

    /** Test-only: the next onEntry for this point throws out of the sink into the advice (advice-level fault). */
    static void induceAdviceFaultAt(Point point) {
        induceAdviceFaultAt = point;
    }

    /**
     * Test-only (H2-H5): the next value, void or constructor exit for this point throws out of the sink into the inlined
     * exit advice. The check runs at the exit method's entry, outside the method's own try/catch, so the advice
     * suppression handler is what absorbs it. One shot: the hit consumes the fault before throwing. Refuses if a fault
     * is already pending.
     */
    static void induceExitAdviceFaultOnce(Point point) {
        if (!INDUCE_EXIT_ADVICE_FAULT_AT.compareAndSet(null, Objects.requireNonNull(point, "point"))) {
            throw new IllegalStateException("exit advice fault already pending");
        }
    }

    /** Test-only: clears a pending exit fault; returns true if one was still pending. */
    static boolean clearExitAdviceFault() {
        return INDUCE_EXIT_ADVICE_FAULT_AT.getAndSet(null) != null;
    }

    static boolean exitAdviceFaultPending() {
        return INDUCE_EXIT_ADVICE_FAULT_AT.get() != null;
    }

    /** Test-only: how many induced exit faults were consumed and thrown. */
    static int exitAdviceFaultHits() {
        return EXIT_ADVICE_FAULT_HITS.get();
    }

    /** H2-H5 only: consumes a pending exit fault for this point, then deliberately escapes the sink. */
    private static void throwIfExitAdviceFault(String pointId) {
        Point fault = INDUCE_EXIT_ADVICE_FAULT_AT.get();
        if (fault != null && fault.id().equals(pointId) && INDUCE_EXIT_ADVICE_FAULT_AT.compareAndSet(fault, null)) {
            EXIT_ADVICE_FAULT_HITS.incrementAndGet();
            throw new InducedObservationFailure();
        }
    }

    // ---- called from inlined advice ----

    public static void onEntry(String pointId) {
        Point fault = induceAdviceFaultAt;
        if (fault != null && fault.id().equals(pointId)) {
            induceAdviceFaultAt = null;
            // Deliberately escapes the sink: exercises advice suppression plus the failure-handler channel.
            throw new InducedObservationFailure();
        }
        record(pointId, Kind.ENTRY, null);
    }

    public static void onValueExit(String pointId, boolean returnedNull, Class<?> throwableType) {
        throwIfExitAdviceFault(pointId);
        try {
            record(pointId, throwableType != null ? Kind.THROWN : returnedNull ? Kind.RETURN_NULL : Kind.RETURN_NON_NULL,
                    throwableType == null ? null : throwableType.getName());
        } catch (Throwable failure) {
            recordingFailed(failure);
        }
    }

    public static void onVoidExit(String pointId, Class<?> throwableType) {
        throwIfExitAdviceFault(pointId);
        try {
            record(pointId, throwableType != null ? Kind.THROWN : Kind.RETURN_VOID,
                    throwableType == null ? null : throwableType.getName());
        } catch (Throwable failure) {
            recordingFailed(failure);
        }
    }

    public static void onConstructorCompleted(String pointId) {
        throwIfExitAdviceFault(pointId);
        record(pointId, Kind.CONSTRUCTOR_COMPLETED, null);
    }

    private static void record(String pointId, Kind kind, String throwableClassName) {
        try {
            Session session = ACTIVE.get();
            if (session == null) {
                UNATTRIBUTED.incrementAndGet();
                return;
            }
            Point point = Point.fromId(pointId);
            if (point == null) {
                session.fail("unknown observation point id");
                return;
            }
            session.admit(point, kind, throwableClassName);
        } catch (Throwable failure) {
            recordingFailed(failure);
        }
    }

    private static void recordingFailed(Throwable failure) {
        OBSERVER_FAILURES.incrementAndGet();
        try {
            Session session = ACTIVE.get();
            if (session != null) {
                session.fail("observation failure: " + failure.getClass().getName());
            }
        } catch (Throwable ignored) {
            // The counter increment above already makes an in-window failure visible to finish().
        }
    }
}
