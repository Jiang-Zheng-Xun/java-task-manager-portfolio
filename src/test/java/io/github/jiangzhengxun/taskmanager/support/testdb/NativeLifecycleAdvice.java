package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.bytecode.Removal;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.TextConstant;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;

/**
 * Byte Buddy advice templates (inlined into target classes; never invoked directly).
 *
 * <p>Fault model. Every template uses {@code suppress = Throwable.class}, so no advice failure can change target
 * arguments, return values, throwables or control flow. Suppression is never silent: the exception handler built by
 * {@link #failureHandler()} replaces Byte Buddy's default {@code Removal.SINGLE} (verified by javap of
 * {@code Advice$ExceptionHandler$Default$1}) with code that consumes the suppressed throwable and then calls
 * {@code System.setProperty(ADVICE_FAILURE_PROPERTY, "true")}. That channel uses only {@code java.base}, so it still
 * works when the observation sink itself cannot be linked from the target class. Any set flag makes the final result
 * INVALID. Practical limit: if the handler itself fails (for example {@link OutOfMemoryError} or a security policy
 * rejecting the property write), that secondary error is not suppressed and propagates; this is not claimed to be
 * recoverable. The system property is JVM-global, which is acceptable only because each control runs in its own JVM.
 *
 * <p>The point id is a constant bound per point through {@code Advice.withCustomMapping().bind(PointId.class, id)};
 * void and value-returning methods use separate templates, so no template relies on {@code @Advice.Return} for void.
 */
final class NativeLifecycleAdvice {

    static final String ADVICE_FAILURE_PROPERTY = "io.github.jiangzhengxun.taskmanager.nativeObservation.adviceFailure";

    /** Bound to the exact point id at transformation time. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface PointId {
    }

    private NativeLifecycleAdvice() {
    }

    /** Stack on entry: [suppressed throwable]. Pops it, sets the failure property, pops the previous value. */
    static StackManipulation failureHandler() {
        try {
            return new StackManipulation.Compound(
                    Removal.SINGLE,
                    new TextConstant(ADVICE_FAILURE_PROPERTY),
                    new TextConstant("true"),
                    MethodInvocation.invoke(new MethodDescription.ForLoadedMethod(
                            System.class.getMethod("setProperty", String.class, String.class))),
                    Removal.SINGLE);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("System.setProperty(String, String) unavailable", e);
        }
    }

    /** Methods with a non-void return type. */
    static final class ValueMethodAdvice {

        private ValueMethodAdvice() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@PointId String pointId) {
            NativeLifecycleObservation.onEntry(pointId);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@PointId String pointId,
                @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC) Object returned,
                @Advice.Thrown(readOnly = true) Throwable thrown) {
            NativeLifecycleObservation.onValueExit(pointId, returned == null, thrown == null ? null : thrown.getClass());
        }
    }

    /** Methods returning void. */
    static final class VoidMethodAdvice {

        private VoidMethodAdvice() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter(@PointId String pointId) {
            NativeLifecycleObservation.onEntry(pointId);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        static void exit(@PointId String pointId, @Advice.Thrown(readOnly = true) Throwable thrown) {
            NativeLifecycleObservation.onVoidExit(pointId, thrown == null ? null : thrown.getClass());
        }
    }

    /**
     * Constructors: normal completion only. No enter advice, because constructor enter advice would run before the
     * superclass constructor and must not be reported as construction. No exceptional exit is observed for
     * constructors; a throwing constructor produces no event (documented gap).
     */
    static final class ConstructorAdvice {

        private ConstructorAdvice() {
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@PointId String pointId) {
            NativeLifecycleObservation.onConstructorCompleted(pointId);
        }
    }
}
