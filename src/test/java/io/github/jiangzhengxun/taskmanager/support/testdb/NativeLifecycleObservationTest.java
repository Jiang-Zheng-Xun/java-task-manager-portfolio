package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.FinalResult;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Hook;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.HookPosition;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.InducedObservationFailure;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Kind;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.ObservationInvalidException;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Point;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Session;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Status;
import java.lang.management.ManagementFactory;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Non-DB native observation controls. Each control is enabled only when
 * {@code -Dtaskmanager.nativeObservation.control=<id>} names it, so a default suite reports every control as skipped
 * (never passed). Exactly one control is meant to run per fresh JVM; controls share no state and do not depend on
 * order. Target classes are only reached reflectively by name after the transformer is installed, so verification of
 * this class cannot load a target early.
 *
 * <p>Only the synthetic unsupported URL below and a null URL are used; no real PostgreSQL URL, host or credentials.
 * No control calls {@code getConnection}, initializes a pool, connects to a database, runs SQL, Flyway or Spring.
 */
class NativeLifecycleObservationTest {

    private static final String CONTROL = "taskmanager.nativeObservation.control";
    private static final String HIKARI = "com.zaxxer.hikari.HikariDataSource";
    private static final String PG_DRIVER = "org.postgresql.Driver";
    private static final String UNSUPPORTED_URL = "jdbc:observation-unsupported:";

    private static final Set<Point> HIKARI_POINTS = EnumSet.of(
            Point.HIKARI_NO_ARG_CONSTRUCTOR, Point.HIKARI_CLOSE, Point.HIKARI_GET_CONNECTION_NO_ARG);
    private static final Set<Point> PG_POINTS = EnumSet.of(Point.PG_DRIVER_CONNECT);

    /** An explicitly enabled run must name a known control and must have exactly one startup agent. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = ".*")
    void enabledRunHasKnownControlAndStartupAgent() {
        assertThat(System.getProperty(CONTROL)).matches("A|B|C|D|E|F|G|H|N1|N2|N3|P5|T1|H2|H3|H4|H5|S1|S2|S3a|S3b|S4|P1n");
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .filteredOn(argument -> argument.startsWith("-javaagent:"))
                .hasSize(1);
        NativeLifecycleInstrumentation.startupInstrumentation();
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "A")
    void controlA_installationHealthAndActualTransformation() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        assertThat(installed.session().diagnostics().transformedTypes()).isEmpty();

        Class<?> driverType = Class.forName(PG_DRIVER, false, getClass().getClassLoader());
        assertThat(driverType.getDeclaredMethod("connect", String.class, Properties.class).getReturnType())
                .isEqualTo(java.sql.Connection.class);
        assertThat(NativeLifecycleInstrumentation.codeSourceSha256(driverType.getProtectionDomain()))
                .isEqualTo(NativeLifecycleInstrumentation.TARGET_SHA256.get(PG_DRIVER));
        assertThat(installed.session().diagnostics().armed()).containsExactly(Point.PG_DRIVER_CONNECT);

        FinalResult result = installed.finish();
        assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
        // Valid only because the point is armed, transformed once and paired; not because the queue is empty.
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "B")
    void controlB_noArgHikariConstructionAndCloseOnly() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(HIKARI_POINTS, HIKARI_POINTS);
        Object dataSource = Class.forName(HIKARI, false, getClass().getClassLoader()).getConstructor().newInstance();
        ((AutoCloseable) dataSource).close();

        FinalResult result = installed.finish();
        assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
        assertThat(result.acceptedCount(Point.HIKARI_NO_ARG_CONSTRUCTOR, Kind.CONSTRUCTOR_COMPLETED)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.HIKARI_CLOSE, Kind.ENTRY)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.HIKARI_CLOSE, Kind.RETURN_VOID)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.HIKARI_CLOSE, Kind.THROWN)).isZero();
        assertThat(result.acceptedCount(Point.HIKARI_GET_CONNECTION_NO_ARG, Kind.ENTRY)).isZero();
    }

    /** Instrumented native behavior; compare with N1 (same calls, no transformer, separate JVM). */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "C")
    void controlC_pgDriverConnectWithUnsupportedUrlReturnsNull() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();

        assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();

        FinalResult result = installed.finish();
        assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY)).isEqualTo(1);
        // A null return means "URL not accepted by this driver"; it is not a connection success.
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.RETURN_NULL)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.RETURN_NON_NULL)).isZero();
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.THROWN)).isZero();
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "D")
    void controlD_omittedRequiredPointIsInvalidNotAcceptedZero() throws Exception {
        Set<Point> instrumented = EnumSet.of(Point.HIKARI_NO_ARG_CONSTRUCTOR, Point.HIKARI_CLOSE);
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(instrumented, HIKARI_POINTS);
        Object dataSource = Class.forName(HIKARI, false, getClass().getClassLoader()).getConstructor().newInstance();
        ((AutoCloseable) dataSource).close();

        FinalResult result = installed.finish();
        assertThat(result.status()).isEqualTo(Status.INVALID);
        assertThat(result.invalidReasons())
                .contains("required point not armed: " + Point.HIKARI_GET_CONNECTION_NO_ARG);
        assertThatThrownBy(() -> result.acceptedCount(Point.HIKARI_GET_CONNECTION_NO_ARG, Kind.ENTRY))
                .isInstanceOf(ObservationInvalidException.class);
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "F")
    void controlF_finishFreezesResultDetachesAndRefusesReuse() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();
        assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();

        FinalResult result = installed.finish();
        assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
        assertThat(installed.finish()).isSameAs(result);

        long unattributed = NativeLifecycleObservation.unattributedEvents();
        assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();
        // Transformer removal does not restore loaded bytecode: advice still fires, outside any session.
        assertThat(NativeLifecycleObservation.unattributedEvents()).isEqualTo(unattributed + 2);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY)).isEqualTo(1);

        assertThatThrownBy(() -> NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already loaded");
    }

    /**
     * Concurrent finish on one installation: both callers must get the same published result and the cleanup sequence
     * must be claimed once. The barrier only lines the callers up; it does not guarantee the calls overlap, so this
     * neither reproduces nor rules out every race. Worker outcomes (results and throwables) are handed back to this
     * thread and asserted here. All waits are bounded; the main thread never calls the synchronized finish while a
     * worker may still be inside it.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "P5")
    void controlP5_concurrentFinishReturnsSameResultAndCleansOnce() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();
        assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReferenceArray<FinalResult> results = new AtomicReferenceArray<>(2);
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(2);
        Thread[] workers = new Thread[2];
        for (int i = 0; i < workers.length; i++) {
            int index = i;
            workers[i] = new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    results.set(index, installed.finish());
                } catch (Throwable failure) {
                    failures.set(index, failure);
                }
            }, "p5-finish-" + i);
            workers[i].setDaemon(true);
        }
        Throwable primary = null;
        try {
            for (Thread worker : workers) {
                worker.start();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (Thread worker : workers) {
                long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                worker.join(remainingMillis);
                assertThat(worker.isAlive()).as("worker %s did not finish within the bound", worker.getName()).isFalse();
            }
            for (int i = 0; i < workers.length; i++) {
                assertThat(failures.get(i)).as("worker %s failure", workers[i].getName()).isNull();
                assertThat(results.get(i)).as("worker %s result", workers[i].getName()).isNotNull();
            }
            FinalResult result = results.get(0);
            assertThat(results.get(1)).isSameAs(result);
            assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
            assertThat(installed.cleanupRuns()).isEqualTo(1);
            assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY)).isEqualTo(1);
            assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.RETURN_NULL)).isEqualTo(1);

            // Workers are confirmed finished, so this sequential call cannot block on a stuck worker.
            assertThat(installed.finish()).isSameAs(result);
            assertThat(installed.cleanupRuns()).isEqualTo(1);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpWorkers(barrier, workers, primary);
        }
    }

    /**
     * Bounded P5 cleanup: resets the barrier, interrupts live workers and joins each worker against one overall
     * deadline. An interrupted join is recorded per worker and does not skip the remaining workers; a non-positive
     * remaining time skips the join instead of calling join(0). Interrupted joins and live workers are attached to the
     * primary failure as suppressed, or thrown when there is none. The caller's interrupt status is restored last. A
     * worker having exited is not evidence of success; worker outcomes are asserted by the test body.
     */
    private static void cleanUpWorkers(CyclicBarrier barrier, Thread[] workers, Throwable primary) {
        boolean interrupted = false;
        List<String> problems = new ArrayList<>();
        try {
            barrier.reset();
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    worker.interrupt();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (Thread worker : workers) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis > 0) {
                    try {
                        worker.join(remainingMillis);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        problems.add("cleanup join interrupted for " + worker.getName());
                    }
                }
                if (worker.isAlive()) {
                    problems.add("worker still alive after cleanup: " + worker.getName());
                }
            }
            if (!problems.isEmpty()) {
                AssertionError cleanupFailure = new AssertionError("P5 worker cleanup problems: " + problems);
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Bound for each S1/S2 wait (hook arrival, lock queueing, thread completion). */
    private static final long SYNTHETIC_WAIT_SECONDS = 10;

    /**
     * S1 (synthetic: empty required set, no transformer, no native call): an ENTRY already inside the admission read
     * lock completes before a finisher, confirmed queued on the session lock, closes the window. The entry is in the
     * frozen result with sequence 1. No EXIT is sent, so EXIT/finisher ordering cannot make the outcome ambiguous.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "S1")
    void controlS1_inLockEntryCompletesBeforeFinish() throws Exception {
        assertNoPendingFaults();
        Session session = NativeLifecycleObservation.start(Set.of());
        Hook hook = Hook.atAdmission(HookPosition.INSIDE_ADMIT_LOCK, Point.PG_DRIVER_CONNECT, Kind.ENTRY);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> finisherFailure = new AtomicReference<>();
        AtomicReference<FinalResult> finished = new AtomicReference<>();
        Thread worker = syntheticThread("s1-entry",
                () -> NativeLifecycleObservation.onEntry(Point.PG_DRIVER_CONNECT.id()), workerFailure);
        Thread finisher = syntheticThread("s1-finish", () -> finished.set(session.finish(List.of())), finisherFailure);
        Throwable primary = null;
        try {
            session.installHook(hook);
            worker.start();
            awaitArrived(hook, worker, workerFailure);
            finisher.start();
            awaitQueued(session, finisher, workerFailure, finisherFailure);
            hook.release.countDown();
            joinWithin(worker, finisher);

            assertThat(workerFailure.get()).as("worker failure").isNull();
            assertThat(finisherFailure.get()).as("finisher failure").isNull();
            assertThat(hook.fired()).isTrue();
            assertThat(hook.failure()).as("hook failure").isNull();
            assertThat(hook.timedOut()).as("hook timed out").isFalse();
            FinalResult result = finished.get();
            assertThat(result).isNotNull();
            assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
            assertThat(result.events()).hasSize(1);
            assertThat(result.events().get(0).sequence()).isEqualTo(1);
            assertThat(result.events().get(0).point()).isEqualTo(Point.PG_DRIVER_CONNECT);
            assertThat(result.events().get(0).kind()).isEqualTo(Kind.ENTRY);
            assertThat(session.diagnostics().lateEvents()).isZero();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpSynthetic(session, List.of(hook), new Thread[] {worker, finisher},
                    List.of(workerFailure, finisherFailure), primary);
        }
    }

    /**
     * S2 (synthetic, as S1): an ENTRY paused before requesting the admission lock is admitted only after a finisher has
     * completed the window, so it is counted as late and the frozen result, its health and its events stay unchanged.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "S2")
    void controlS2_entryAfterFinishIsLateAndResultUnchanged() throws Exception {
        assertNoPendingFaults();
        Session session = NativeLifecycleObservation.start(Set.of());
        Hook hook = Hook.atAdmission(HookPosition.BEFORE_ADMIT_LOCK, Point.PG_DRIVER_CONNECT, Kind.ENTRY);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> finisherFailure = new AtomicReference<>();
        AtomicReference<FinalResult> finished = new AtomicReference<>();
        Thread worker = syntheticThread("s2-entry",
                () -> NativeLifecycleObservation.onEntry(Point.PG_DRIVER_CONNECT.id()), workerFailure);
        Thread finisher = syntheticThread("s2-finish", () -> finished.set(session.finish(List.of())), finisherFailure);
        Throwable primary = null;
        try {
            session.installHook(hook);
            worker.start();
            awaitArrived(hook, worker, workerFailure);
            finisher.start();
            // The worker is paused before requesting the lock, so the finisher must complete on its own.
            joinWithin(finisher);
            assertThat(finisherFailure.get()).as("finisher failure").isNull();
            FinalResult result = finished.get();
            assertThat(result).isNotNull();
            assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
            assertThat(result.events()).isEmpty();
            List<String> frozenReasons = result.invalidReasons();
            assertThat(session.diagnostics().lateEvents()).isZero();

            hook.release.countDown();
            joinWithin(worker);

            assertThat(workerFailure.get()).as("worker failure").isNull();
            assertThat(hook.fired()).isTrue();
            assertThat(hook.failure()).as("hook failure").isNull();
            assertThat(hook.timedOut()).as("hook timed out").isFalse();
            assertThat(session.diagnostics().lateEvents()).isEqualTo(1);
            assertThat(session.diagnostics().events()).isEmpty();
            FinalResult again = session.finish(List.of());
            assertThat(again).isSameAs(result);
            assertThat(again.status()).isEqualTo(Status.VALID);
            assertThat(again.invalidReasons()).isEqualTo(frozenReasons);
            assertThat(again.events()).isEmpty();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpSynthetic(session, List.of(hook), new Thread[] {worker, finisher},
                    List.of(workerFailure, finisherFailure), primary);
        }
    }

    private static Thread syntheticThread(String name, Runnable body, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void awaitArrived(Hook hook, Thread worker, AtomicReference<Throwable> workerFailure)
            throws InterruptedException {
        boolean arrived = hook.arrived.await(SYNTHETIC_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(workerFailure.get()).as("worker failure before reaching the hook").isNull();
        assertThat(arrived).as("worker %s did not reach the hook within the bound", worker.getName()).isTrue();
    }

    /** Bounded spin (no sleep) until the finisher is queued on the session lock, failing fast on any thread failure. */
    private static void awaitQueued(Session session, Thread finisher, AtomicReference<Throwable> workerFailure,
            AtomicReference<Throwable> finisherFailure) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SYNTHETIC_WAIT_SECONDS);
        while (!session.hasQueuedThread(finisher)) {
            assertThat(workerFailure.get()).as("worker failure while waiting for the finisher to queue").isNull();
            assertThat(finisherFailure.get()).as("finisher failure while waiting for it to queue").isNull();
            assertThat(finisher.isAlive()).as("finisher ended without queueing on the session lock").isTrue();
            assertThat(deadline - System.nanoTime()).as("finisher not queued within the bound").isPositive();
            Thread.onSpinWait();
        }
    }

    private static void joinWithin(Thread... threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SYNTHETIC_WAIT_SECONDS);
        for (Thread thread : threads) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            thread.join(remainingMillis);
            assertThat(thread.isAlive()).as("thread %s did not finish within the bound", thread.getName()).isFalse();
        }
    }

    /**
     * Bounded S1/S2 cleanup: releases every hook, clears the hook set, interrupts live threads and joins each against
     * one overall deadline (a non-positive remaining time skips the join instead of calling join(0)), then collects
     * thread failures, hook failures/timeouts and thread leaks. The session is finished and detached only when every
     * thread has stopped; otherwise the leak is reported and the session is left attached rather than covered by
     * finish/detach. Problems are attached to the primary failure as suppressed, or thrown when there is none. The
     * caller's interrupt status is restored last.
     */
    private static void cleanUpSynthetic(Session session, List<Hook> hooks, Thread[] threads,
            List<AtomicReference<Throwable>> threadFailures, Throwable primary) {
        boolean interrupted = false;
        List<String> problems = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        try {
            for (Hook hook : hooks) {
                hook.release.countDown();
            }
            session.clearHooks();
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean stopped = true;
            for (Thread thread : threads) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis > 0) {
                    try {
                        thread.join(remainingMillis);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        problems.add("cleanup join interrupted for " + thread.getName());
                    }
                }
                if (thread.isAlive()) {
                    stopped = false;
                    problems.add("thread leak: still alive after cleanup: " + thread.getName());
                }
            }
            for (AtomicReference<Throwable> threadFailure : threadFailures) {
                Throwable failure = threadFailure.get();
                if (failure != null && failure != primary) {
                    problems.add("thread failure: " + failure.getClass().getName());
                    causes.add(failure);
                }
            }
            for (Hook hook : hooks) {
                if (hook.failure() != null) {
                    problems.add("hook failure at " + hook.position + ": " + hook.failure().getClass().getName());
                    causes.add(hook.failure());
                }
                if (hook.timedOut()) {
                    problems.add("hook release timed out at " + hook.position);
                }
            }
            if (stopped) {
                try {
                    session.finish(List.of());
                } catch (Throwable failure) {
                    problems.add("session finish during cleanup failed: " + failure.getClass().getName());
                    causes.add(failure);
                }
                if (!NativeLifecycleObservation.detach(session)) {
                    problems.add("session was not the active session at detach");
                }
            } else {
                problems.add("session left attached because threads did not stop");
            }
            if (!problems.isEmpty()) {
                AssertionError cleanupFailure = new AssertionError("synthetic control cleanup problems: " + problems);
                for (Throwable cause : causes) {
                    if (cause != primary) {
                        cleanupFailure.addSuppressed(cause);
                    }
                }
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * S3a (synthetic: empty required set, no transformer, no native call): an observer failure whose counter increment
     * happened before the boundary makes the result INVALID even though its reason is only added after the boundary,
     * where it is counted as a late failure and never changes the frozen result.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "S3a")
    void controlS3a_failureCountBeforeFinishInvalidatesAndReasonArrivesLate() throws Exception {
        assertNoPendingFaults();
        Session session = NativeLifecycleObservation.start(Set.of());
        Hook hook = Hook.afterFailureCount();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> finisherFailure = new AtomicReference<>();
        AtomicReference<FinalResult> finished = new AtomicReference<>();
        Thread worker = syntheticThread("s3a-invalidate", () -> session.invalidate("s3a"), workerFailure);
        Thread finisher = syntheticThread("s3a-finish", () -> finished.set(session.finish(List.of())), finisherFailure);
        Throwable primary = null;
        try {
            session.installHook(hook);
            worker.start();
            awaitArrived(hook, worker, workerFailure);
            finisher.start();
            joinWithin(finisher);
            assertThat(finisherFailure.get()).as("finisher failure").isNull();
            FinalResult result = finished.get();
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("observer failures during window").doesNotContain("s3a");
            assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
            List<String> frozenReasons = result.invalidReasons();
            List<NativeLifecycleObservation.Event> frozenEvents = result.events();
            assertThat(session.diagnostics().lateFailures()).isZero();

            hook.release.countDown();
            joinWithin(worker);

            assertThat(workerFailure.get()).as("worker failure").isNull();
            assertThat(hook.fired()).isTrue();
            assertThat(hook.failure()).as("hook failure").isNull();
            assertThat(hook.timedOut()).as("hook timed out").isFalse();
            assertThat(session.diagnostics().lateFailures()).isEqualTo(1);
            FinalResult again = session.finish(List.of());
            assertThat(again).isSameAs(result);
            assertThat(again.status()).isEqualTo(Status.INVALID);
            assertThat(again.invalidReasons()).isEqualTo(frozenReasons).doesNotContain("s3a");
            assertThat(again.events()).isEqualTo(frozenEvents);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpSynthetic(session, List.of(hook), new Thread[] {worker, finisher},
                    List.of(workerFailure, finisherFailure), primary);
        }
    }

    /** S3b (synthetic, as S3a, no hook): a failure after the boundary is late and leaves the frozen result unchanged. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "S3b")
    void controlS3b_failureAfterFinishIsLateAndResultUnchanged() throws Exception {
        assertNoPendingFaults();
        Session session = NativeLifecycleObservation.start(Set.of());
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = syntheticThread("s3b-invalidate", () -> session.invalidate("s3b"), workerFailure);
        Throwable primary = null;
        try {
            FinalResult result = session.finish(List.of());
            assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
            List<String> frozenReasons = result.invalidReasons();
            List<NativeLifecycleObservation.Event> frozenEvents = result.events();

            worker.start();
            joinWithin(worker);

            assertThat(workerFailure.get()).as("worker failure").isNull();
            assertThat(session.diagnostics().lateFailures()).isEqualTo(1);
            FinalResult again = session.finish(List.of());
            assertThat(again).isSameAs(result);
            assertThat(again.status()).isEqualTo(Status.VALID);
            assertThat(again.invalidReasons()).isEqualTo(frozenReasons).doesNotContain("s3b");
            assertThat(again.events()).isEqualTo(frozenEvents);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpSynthetic(session, List.of(), new Thread[] {worker}, List.of(workerFailure), primary);
        }
    }

    /**
     * S4 (synthetic, as S3a): eight workers released together each record one ENTRY and one RETURN_NULL. Only a complete,
     * duplicate-free sequence 1..16 is required; neither interleaving nor list order is. One controlled concurrent
     * observation, not a proof that no race exists.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "S4")
    void controlS4_concurrentSyntheticEventsHaveCompleteUniqueSequence() throws Exception {
        assertNoPendingFaults();
        Session session = NativeLifecycleObservation.start(Set.of());
        int workerCount = 8;
        CyclicBarrier barrier = new CyclicBarrier(workerCount);
        Thread[] workers = new Thread[workerCount];
        List<AtomicReference<Throwable>> failures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            failures.add(failure);
            workers[i] = new Thread(() -> {
                try {
                    barrier.await(SYNTHETIC_WAIT_SECONDS, TimeUnit.SECONDS);
                    NativeLifecycleObservation.onEntry(Point.PG_DRIVER_CONNECT.id());
                    NativeLifecycleObservation.onValueExit(Point.PG_DRIVER_CONNECT.id(), true, null);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "s4-worker-" + i);
            workers[i].setDaemon(true);
        }
        Throwable primary = null;
        try {
            for (Thread worker : workers) {
                worker.start();
            }
            joinWithin(workers);
            for (int i = 0; i < workerCount; i++) {
                assertThat(failures.get(i).get()).as("worker %s failure", workers[i].getName()).isNull();
            }
            FinalResult result = session.finish(List.of());
            assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
            assertThat(result.events()).hasSize(2 * workerCount);
            assertThat(result.events()).allMatch(event -> event.point() == Point.PG_DRIVER_CONNECT);
            assertThat(result.events()).filteredOn(event -> event.kind() == Kind.ENTRY).hasSize(workerCount);
            assertThat(result.events()).filteredOn(event -> event.kind() == Kind.RETURN_NULL).hasSize(workerCount);
            List<Long> sequences = result.events().stream().map(event -> event.sequence()).sorted().toList();
            assertThat(sequences).isEqualTo(LongStream.rangeClosed(1, 2L * workerCount).boxed().toList());
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            barrier.reset();
            cleanUpSynthetic(session, List.of(), workers, failures, primary);
        }
    }

    /**
     * P1n (native, controlled boundary experiment): a real Driver.connect ENTRY is admitted before the boundary and its
     * RETURN_NULL exit only after it. H-a holds the ENTRY inside the admission lock until the finisher is queued; the
     * freeze pause H-f holds finish() after the freeze and before reset/detach, so the exit's session lookup still finds
     * the original session; H-b then holds that exit before the admission lock until the result is published. This
     * deliberately breaks finish()'s caller obligation (native calls have returned) to study the event boundary; it is
     * not a supported shutdown pattern.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "P1n")
    void controlP1n_nativeEntryBeforeFinishAndExitAfterFinishIsLate() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Session session = installed.session();
        Hook entryHook = Hook.atAdmission(HookPosition.INSIDE_ADMIT_LOCK, Point.PG_DRIVER_CONNECT, Kind.ENTRY);
        Hook exitHook = Hook.atAdmission(HookPosition.BEFORE_ADMIT_LOCK, Point.PG_DRIVER_CONNECT, Kind.RETURN_NULL);
        NativeLifecycleInstrumentation.FreezePause freezePause = new NativeLifecycleInstrumentation.FreezePause();
        AtomicReference<java.sql.Connection> connection = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        AtomicReference<Throwable> finisherFailure = new AtomicReference<>();
        AtomicReference<FinalResult> finished = new AtomicReference<>();
        Thread worker = null;
        Thread finisher = syntheticThread("p1n-finish", () -> finished.set(installed.finish()), finisherFailure);
        Throwable primary = null;
        try {
            Driver driver = newPgDriver();
            assertThat(session.diagnostics().transformedTypes()).containsEntry(PG_DRIVER, 1);
            worker = new Thread(() -> {
                try {
                    connection.set(driver.connect(UNSUPPORTED_URL, new Properties()));
                } catch (Throwable thrown) {
                    workerFailure.set(thrown);
                }
            }, "p1n-connect");
            worker.setDaemon(true);
            session.installHook(entryHook);
            session.installHook(exitHook);
            installed.installFreezePause(freezePause);

            worker.start();
            awaitArrived(entryHook, worker, workerFailure);
            finisher.start();
            awaitQueued(session, finisher, workerFailure, finisherFailure);
            entryHook.release.countDown();
            // Both arrivals are latched, so their actual order does not matter.
            awaitSignal(freezePause.arrived, "finisher paused after freeze", workerFailure, finisherFailure);
            awaitSignal(exitHook.arrived, "exit paused before admission", workerFailure, finisherFailure);
            freezePause.release.countDown();
            joinWithin(finisher);

            assertThat(finisherFailure.get()).as("finisher failure").isNull();
            FinalResult result = finished.get();
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("unpaired entry/exit events: " + Point.PG_DRIVER_CONNECT);
            assertThat(result.invalidReasons()).noneMatch(reason -> reason.startsWith("transformer removal")
                    || reason.startsWith("session was not the active session") || reason.startsWith("session finish failed")
                    || reason.equals("no frozen event snapshot"));
            assertThat(result.events()).hasSize(1);
            assertThat(result.events().get(0).point()).isEqualTo(Point.PG_DRIVER_CONNECT);
            assertThat(result.events().get(0).kind()).isEqualTo(Kind.ENTRY);
            assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
            assertThat(session.diagnostics().lateEvents()).isZero();

            exitHook.release.countDown();
            joinWithin(worker);

            assertThat(workerFailure.get()).as("native connect failure").isNull();
            assertThat(connection.get()).as("native connect result").isNull();
            assertThat(session.diagnostics().lateEvents()).isEqualTo(1);
            assertThat(installed.finish()).isSameAs(result);
            assertThat(installed.cleanupRuns()).isEqualTo(1);
            for (Hook hook : List.of(entryHook, exitHook)) {
                assertThat(hook.fired()).as("hook %s fired", hook.position).isTrue();
                assertThat(hook.failure()).as("hook %s failure", hook.position).isNull();
                assertThat(hook.timedOut()).as("hook %s timed out", hook.position).isFalse();
            }
            assertThat(freezePause.fired()).as("freeze pause fired").isTrue();
            assertThat(freezePause.failure()).as("freeze pause failure").isNull();
            assertThat(freezePause.timedOut()).as("freeze pause timed out").isFalse();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpNativeBoundary(installed, session, List.of(entryHook, exitHook), freezePause,
                    worker == null ? new Thread[] {finisher} : new Thread[] {worker, finisher},
                    List.of(workerFailure, finisherFailure), connection, primary);
        }
    }

    private static void awaitSignal(CountDownLatch latch, String what, AtomicReference<Throwable> workerFailure,
            AtomicReference<Throwable> finisherFailure) throws InterruptedException {
        boolean signalled = latch.await(SYNTHETIC_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(workerFailure.get()).as("worker failure while waiting: %s", what).isNull();
        assertThat(finisherFailure.get()).as("finisher failure while waiting: %s", what).isNull();
        assertThat(signalled).as("not signalled within the bound: %s", what).isTrue();
    }

    /**
     * Bounded P1n cleanup: releases every session hook and the freeze pause through the held objects (no monitor
     * needed), clears them, interrupts live threads and joins each against one 5 s deadline (never join(0)), then
     * collects thread, hook and pause failures/timeouts and thread leaks. Only when every thread has stopped is a
     * non-null connection closed and installed.finish() called (returning the published result when it already ran);
     * otherwise the leak is reported and the installation is left unfinished rather than covered by finish(). Problems
     * are attached to the primary failure as suppressed, or thrown when there is none. The caller's interrupt status is
     * restored last.
     */
    private static void cleanUpNativeBoundary(NativeLifecycleInstrumentation installed, Session session, List<Hook> hooks,
            NativeLifecycleInstrumentation.FreezePause freezePause, Thread[] threads,
            List<AtomicReference<Throwable>> threadFailures, AtomicReference<java.sql.Connection> connection,
            Throwable primary) {
        boolean interrupted = false;
        List<String> problems = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        try {
            for (Hook hook : hooks) {
                hook.release.countDown();
            }
            freezePause.release.countDown();
            session.clearHooks();
            installed.clearFreezePause();
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean stopped = true;
            for (Thread thread : threads) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMillis > 0) {
                    try {
                        thread.join(remainingMillis);
                    } catch (InterruptedException e) {
                        interrupted = true;
                        problems.add("cleanup join interrupted for " + thread.getName());
                    }
                }
                if (thread.isAlive()) {
                    stopped = false;
                    problems.add("thread leak: still alive after cleanup: " + thread.getName());
                }
            }
            for (AtomicReference<Throwable> threadFailure : threadFailures) {
                Throwable failure = threadFailure.get();
                if (failure != null && failure != primary) {
                    problems.add("thread failure: " + failure.getClass().getName());
                    causes.add(failure);
                }
            }
            for (Hook hook : hooks) {
                if (hook.failure() != null) {
                    problems.add("hook failure at " + hook.position + ": " + hook.failure().getClass().getName());
                    causes.add(hook.failure());
                }
                if (hook.timedOut()) {
                    problems.add("hook release timed out at " + hook.position);
                }
            }
            if (freezePause.failure() != null) {
                problems.add("freeze pause failure: " + freezePause.failure().getClass().getName());
                causes.add(freezePause.failure());
            }
            if (freezePause.timedOut()) {
                problems.add("freeze pause release timed out");
            }
            if (stopped) {
                java.sql.Connection open = connection.get();
                if (open != null) {
                    problems.add("native connect returned a non-null connection");
                    try {
                        open.close();
                    } catch (Throwable failure) {
                        problems.add("closing the unexpected connection failed: " + failure.getClass().getName());
                        causes.add(failure);
                    }
                }
                try {
                    installed.finish();
                } catch (Throwable failure) {
                    problems.add("finish during cleanup failed: " + failure.getClass().getName());
                    causes.add(failure);
                }
            } else {
                problems.add("installation left unfinished because threads did not stop");
            }
            if (!problems.isEmpty()) {
                AssertionError cleanupFailure = new AssertionError("P1n cleanup problems: " + problems);
                for (Throwable cause : causes) {
                    if (cause != primary) {
                        cleanupFailure.addSuppressed(cause);
                    }
                }
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Native exception path, instrumented. Driver.connect(null, props) throws java.sql.SQLException at bytecode offsets
     * 0-13, before any connection work (correction-3 I10, sliced in correction-4 I06). Compare with N3.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "E")
    void controlE_instrumentedNullUrlExceptionPreserved() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();

        Throwable thrown = catchThrowable(() -> driver.connect(null, new Properties()));

        assertNativeNullUrlContract(thrown);
        FinalResult result = installed.finish();
        assertThat(result.status()).as(result.invalidReasons().toString()).isEqualTo(Status.VALID);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.THROWN)).isEqualTo(1);
        assertThat(result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.RETURN_NULL)).isZero();
        assertThat(result.events())
                .filteredOn(event -> event.kind() == Kind.THROWN)
                .extracting(NativeLifecycleObservation.Event::throwableClassName)
                .containsExactly(SQLException.class.getName());
    }

    /** Induced sink-internal failure on the real transformed method: native result preserved, observation INVALID. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "G")
    void controlG_inducedSinkFailureInvalidatesWithoutChangingNativeResult() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();
        installed.session().induceSinkFailureAt(Point.PG_DRIVER_CONNECT);

        assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();

        FinalResult result = installed.finish();
        assertThat(result.status()).isEqualTo(Status.INVALID);
        assertThat(result.invalidReasons()).anyMatch(reason -> reason.startsWith("observation failure: "));
        assertThat(result.invalidReasons()).contains("observer failures during window");
        assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                .isInstanceOf(ObservationInvalidException.class);
    }

    /**
     * Induced advice-level failure: the sink throws out into the inlined advice of the real transformed method. The
     * advice suppresses it (native result and exception contract unchanged) and its failure handler sets the JDK-only
     * failure flag, so the result is INVALID rather than an accepted count.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "H")
    void controlH_inducedAdviceFailureIsSuppressedButInvalidates() throws Exception {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Driver driver = newPgDriver();
        NativeLifecycleObservation.induceAdviceFaultAt(Point.PG_DRIVER_CONNECT);

        Throwable thrown = catchThrowable(() -> driver.connect(null, new Properties()));

        assertNativeNullUrlContract(thrown);
        assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isEqualTo("true");
        FinalResult result = installed.finish();
        assertThat(result.status()).isEqualTo(Status.INVALID);
        assertThat(result.invalidReasons()).contains("advice failure suppressed during window");
        assertThat(result.invalidReasons()).contains("unpaired entry/exit events: " + Point.PG_DRIVER_CONNECT);
        assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.THROWN))
                .isInstanceOf(ObservationInvalidException.class);
    }

    /**
     * T1: a one-shot InducedObservationFailure thrown at the entry of the PostgreSQL Driver transform, before any advice
     * change. Required: the fault is consumed once, onError reports the original induced failure class (Byte Buddy's
     * IllegalStateException wrapper is not accepted as the cause), the result is INVALID, accepted counts are refused,
     * the type is not recorded as transformed and no event exists. Native load/instantiate and connect outcomes are not
     * presumed either way: they are printed as bounded diagnostics (attempted, instance/null/non-null, throwable class,
     * top frame and cause classes; never URL, properties, arguments or messages) for independent review. This does not
     * prove the JVM's class-loading behavior in general.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "T1")
    void controlT1_inducedTransformFailureInvalidatesObservation() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Throwable primary = null;
        try {
            assertThat(driverLoadedByName()).isFalse();
            assertThat(installed.session().diagnostics().transformedTypes()).isEmpty();
            NativeLifecycleInstrumentation.induceTransformFailureOnPgDriver();

            Driver driver = null;
            Throwable loadFailure = null;
            try {
                driver = newPgDriver();
            } catch (ReflectiveOperationException | LinkageError failure) {
                loadFailure = failure;
            }
            java.sql.Connection connection = null;
            Throwable connectFailure = null;
            Throwable closeFailure = null;
            if (driver != null) {
                try {
                    connection = driver.connect(UNSUPPORTED_URL, new Properties());
                } catch (SQLException | RuntimeException failure) {
                    connectFailure = failure;
                }
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException | RuntimeException failure) {
                        closeFailure = failure;
                    }
                }
            }
            String load = loadFailure == null ? "instance" : "threw " + describe(loadFailure);
            String connect = driver == null ? "not attempted"
                    : "attempted, outcome=" + (connectFailure != null ? "threw " + describe(connectFailure)
                            : connection == null ? "null" : "non-null, close "
                                    + (closeFailure == null ? "completed" : "threw " + describe(closeFailure)));
            System.out.println("T1 native diagnostics: load/instantiate attempted, outcome=" + load + "; connect "
                    + connect);

            FinalResult result = installed.finish();
            // Only a loaded driver whose unsupported-URL connect returned null is an accepted T1 outcome. Any other
            // native outcome fails here for separate human review; it is not a verdict on JVM class-loading behaviour.
            Throwable nativeFailure = loadFailure != null ? loadFailure : connectFailure;
            if (nativeFailure != null || connection != null) {
                AssertionError unaccepted = new AssertionError("T1 native outcome requires separate review: "
                        + "load/instantiate " + load + "; connect " + connect, nativeFailure);
                if (closeFailure != null) {
                    unaccepted.addSuppressed(closeFailure);
                }
                throw unaccepted;
            }
            assertThat(NativeLifecycleInstrumentation.inducedTransformFailures()).isEqualTo(1);
            assertThat(NativeLifecycleInstrumentation.inducedTransformFailurePending()).isFalse();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons())
                    .filteredOn(reason -> reason.startsWith("transformation error for "))
                    .containsExactly("transformation error for " + PG_DRIVER + ": "
                            + InducedObservationFailure.class.getName());
            assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
            assertThat(installed.session().diagnostics().transformedTypes()).doesNotContainKey(PG_DRIVER);
            assertThat(result.events()).isEmpty();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpControl(installed, primary);
        }
    }

    /** H2: induced fault in the value exit advice on the normal null-return path; native null return preserved. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "H2")
    void controlH2_valueExitFaultPreservesNullReturn() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Throwable primary = null;
        try {
            Driver driver = newPgDriver();
            NativeLifecycleObservation.induceExitAdviceFaultOnce(Point.PG_DRIVER_CONNECT);

            assertThat(driver.connect(UNSUPPORTED_URL, new Properties())).isNull();

            assertExitFaultConsumed();
            assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isEqualTo("true");
            FinalResult result = installed.finish();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("advice failure suppressed during window");
            assertThat(result.invalidReasons()).contains("unpaired entry/exit events: " + Point.PG_DRIVER_CONNECT);
            assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpControl(installed, primary);
        }
    }

    /** H3: induced fault in the value exit advice on the onThrowable path; native SQLException contract preserved. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "H3")
    void controlH3_valueExitFaultPreservesNativeException() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(PG_POINTS, PG_POINTS);
        Throwable primary = null;
        try {
            Driver driver = newPgDriver();
            NativeLifecycleObservation.induceExitAdviceFaultOnce(Point.PG_DRIVER_CONNECT);

            Throwable thrown = catchThrowable(() -> driver.connect(null, new Properties()));

            assertNativeNullUrlContract(thrown);
            assertExitFaultConsumed();
            assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isEqualTo("true");
            FinalResult result = installed.finish();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("advice failure suppressed during window");
            assertThat(result.invalidReasons()).contains("unpaired entry/exit events: " + Point.PG_DRIVER_CONNECT);
            assertThatThrownBy(() -> result.acceptedCount(Point.PG_DRIVER_CONNECT, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpControl(installed, primary);
        }
    }

    /** H4: induced fault in the void exit advice of close(); real construction and close complete, no pool use. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "H4")
    void controlH4_voidExitFaultPreservesClose() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(HIKARI_POINTS, HIKARI_POINTS);
        Throwable primary = null;
        try {
            Object dataSource = Class.forName(HIKARI, false, getClass().getClassLoader()).getConstructor().newInstance();
            NativeLifecycleObservation.induceExitAdviceFaultOnce(Point.HIKARI_CLOSE);

            ((AutoCloseable) dataSource).close();

            assertExitFaultConsumed();
            assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isEqualTo("true");
            FinalResult result = installed.finish();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("advice failure suppressed during window");
            assertThat(result.invalidReasons()).contains("unpaired entry/exit events: " + Point.HIKARI_CLOSE);
            assertThat(result.events()).filteredOn(event -> event.point() == Point.HIKARI_GET_CONNECTION_NO_ARG).isEmpty();
            assertThatThrownBy(() -> result.acceptedCount(Point.HIKARI_CLOSE, Kind.ENTRY))
                    .isInstanceOf(ObservationInvalidException.class);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpControl(installed, primary);
        }
    }

    /**
     * H5: induced fault in the constructor exit advice (normal completion only); real construction and close complete.
     * Constructor exceptional completion and pre-super behavior stay out of scope.
     */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "H5")
    void controlH5_constructorExitFaultPreservesConstruction() throws Exception {
        assertNoPendingFaults();
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(HIKARI_POINTS, HIKARI_POINTS);
        Throwable primary = null;
        try {
            NativeLifecycleObservation.induceExitAdviceFaultOnce(Point.HIKARI_NO_ARG_CONSTRUCTOR);

            Object dataSource = Class.forName(HIKARI, false, getClass().getClassLoader()).getConstructor().newInstance();
            assertThat(dataSource).isNotNull();
            ((AutoCloseable) dataSource).close();

            assertExitFaultConsumed();
            assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isEqualTo("true");
            FinalResult result = installed.finish();
            assertThat(result.status()).isEqualTo(Status.INVALID);
            assertThat(result.invalidReasons()).contains("advice failure suppressed during window");
            assertThat(result.invalidReasons()).doesNotContain("unpaired entry/exit events: " + Point.HIKARI_CLOSE);
            assertThat(result.events()).filteredOn(event -> event.kind() == Kind.CONSTRUCTOR_COMPLETED).isEmpty();
            assertThat(result.events())
                    .filteredOn(event -> event.point() == Point.HIKARI_CLOSE)
                    .extracting(NativeLifecycleObservation.Event::kind)
                    .containsExactly(Kind.ENTRY, Kind.RETURN_VOID);
            assertThatThrownBy(() -> result.acceptedCount(Point.HIKARI_NO_ARG_CONSTRUCTOR, Kind.CONSTRUCTOR_COMPLETED))
                    .isInstanceOf(ObservationInvalidException.class);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanUpControl(installed, primary);
        }
    }

    /** Before any T1/H2-H5 control: no advice failure flag and no pending induced fault from an earlier step. */
    private static void assertNoPendingFaults() {
        assertThat(System.getProperty(NativeLifecycleAdvice.ADVICE_FAILURE_PROPERTY)).isNull();
        assertThat(NativeLifecycleInstrumentation.inducedTransformFailurePending()).isFalse();
        assertThat(NativeLifecycleObservation.exitAdviceFaultPending()).isFalse();
    }

    private static void assertExitFaultConsumed() {
        assertThat(NativeLifecycleObservation.exitAdviceFaultHits()).isEqualTo(1);
        assertThat(NativeLifecycleObservation.exitAdviceFaultPending()).isFalse();
    }

    /**
     * T1/H2-H5 cleanup: clears any still-pending induced fault and finishes the installation (a no-op returning the
     * published result if the body already finished it). The original finish throwable and any pending-fault problem
     * are attached to the primary failure as suppressed; without a primary the finish throwable is rethrown as is
     * (carrying the pending-fault problem as suppressed), or the pending-fault problem is thrown. A cleanup problem is
     * never treated as a pass. Best effort only: under resource exhaustion allocation or addSuppressed may itself fail.
     * The advice failure flag is left set: each control runs in its own JVM.
     */
    private static void cleanUpControl(NativeLifecycleInstrumentation installed, Throwable primary) {
        List<String> problems = new ArrayList<>();
        if (NativeLifecycleInstrumentation.clearInducedTransformFailure()) {
            problems.add("induced transform failure was still pending");
        }
        if (NativeLifecycleObservation.clearExitAdviceFault()) {
            problems.add("exit advice fault was still pending");
        }
        Throwable finishFailure = null;
        try {
            installed.finish();
        } catch (Throwable failure) {
            finishFailure = failure;
        }
        AssertionError cleanupFailure = problems.isEmpty() ? null
                : new AssertionError("control cleanup problems: " + problems);
        if (primary != null) {
            if (finishFailure != null && finishFailure != primary) {
                primary.addSuppressed(finishFailure);
            }
            if (cleanupFailure != null) {
                primary.addSuppressed(cleanupFailure);
            }
            return;
        }
        if (finishFailure != null) {
            if (cleanupFailure != null) {
                finishFailure.addSuppressed(cleanupFailure);
            }
            if (finishFailure instanceof Error error) {
                throw error;
            }
            if (finishFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("finish during cleanup failed", finishFailure);
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    /** Bounded throwable description for diagnostics: class, top frame and cause classes; never the message. */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getName());
        StackTraceElement[] trace = failure.getStackTrace();
        if (trace.length > 0) {
            text.append(" at ").append(trace[0].getClassName()).append('.').append(trace[0].getMethodName());
        }
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 8; depth++) {
            text.append(" <- ").append(cause.getClass().getName());
            cause = cause.getCause();
        }
        return text.toString();
    }

    /** Uninstrumented baseline for E: same call, no transformer, separate JVM. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "N3")
    void controlN3_uninstrumentedNullUrlException() throws Exception {
        assertThat(driverLoadedByName()).isFalse();
        Throwable thrown = catchThrowable(() -> newPgDriver().connect(null, new Properties()));
        assertNativeNullUrlContract(thrown);
        assertThat(NativeLifecycleObservation.unattributedEvents()).isZero();
    }

    /** Same assertion in E, H and N3: exact class java.sql.SQLException, no cause, no SQLState. Message not logged. */
    private static void assertNativeNullUrlContract(Throwable thrown) {
        assertThat(thrown).isNotNull();
        assertThat(thrown.getClass()).isEqualTo(SQLException.class);
        assertThat(thrown.getCause()).isNull();
        assertThat(((SQLException) thrown).getSQLState()).isNull();
    }

    /** Uninstrumented baseline for C: same JVM shape and agent, no transformer installed. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "N1")
    void controlN1_uninstrumentedPgDriverConnectWithUnsupportedUrl() throws Exception {
        assertThat(driverLoadedByName()).isFalse();
        assertThat(newPgDriver().connect(UNSUPPORTED_URL, new Properties())).isNull();
        assertThat(NativeLifecycleObservation.unattributedEvents()).isZero();
    }

    /** Uninstrumented baseline for B: no-argument construction and close only. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "N2")
    void controlN2_uninstrumentedHikariConstructionAndClose() throws Exception {
        Object dataSource = Class.forName(HIKARI, false, getClass().getClassLoader()).getConstructor().newInstance();
        ((AutoCloseable) dataSource).close();
        assertThat(NativeLifecycleObservation.unattributedEvents()).isZero();
    }

    private boolean driverLoadedByName() {
        return !NativeLifecycleInstrumentation.loadedTargets(
                NativeLifecycleInstrumentation.startupInstrumentation(), Set.of(PG_DRIVER)).isEmpty();
    }

    private Driver newPgDriver() throws ReflectiveOperationException {
        return (Driver) Class.forName(PG_DRIVER, true, getClass().getClassLoader()).getConstructor().newInstance();
    }
}
