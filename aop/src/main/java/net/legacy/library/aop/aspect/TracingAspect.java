package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.Traced;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.tracing.TraceContext;
import net.legacy.library.aop.tracing.TraceService;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;

/**
 * Captures distributed tracing information for methods annotated with {@link Traced}.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@RequiredArgsConstructor
@AOPInterceptor(global = true, order = 30)
public class TracingAspect implements MethodInterceptor {

    private final TraceService traceService;

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        Traced traced = method.getAnnotation(Traced.class);

        if (traced == null || !traceService.shouldSample(traced.samplingRate(), traced.alwaysTrace())) {
            return invocation.proceed();
        }

        TraceContext previousContext = traceService.getCurrentTrace();
        TraceContext traceContext = createTraceContext(context, traced);

        if (traceContext.getNestingDepth() > traced.maxNestingDepth()) {
            return invocation.proceed();
        }

        traceService.setCurrentTrace(traceContext);
        long startTime = System.currentTimeMillis();

        Object result;
        boolean asyncExecution = false;

        try {
            addMethodParameters(traceContext, invocation.getArguments(), method.getParameterTypes(), traced.maxParameterSize());
            addCustomTags(traceContext, traced.tags());

            result = invocation.proceed();

            if (result instanceof CompletionStage<?> stage) {
                asyncExecution = true;
                return handleAsyncStage(stage, traceContext, traced, previousContext, startTime);
            }

            recordSuccess(traceContext, traced, result, startTime);
            traceService.completeCurrentTrace(TraceContext.TraceStatus.COMPLETED);
            return result;
        } catch (Throwable throwable) {
            recordFailure(traceContext, traced, throwable, startTime);
            traceService.completeCurrentTraceWithError(throwable);
            throw throwable;
        } finally {
            if (!asyncExecution) {
                restorePreviousContext(previousContext);
            }
        }
    }

    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(Traced.class);
    }

    @Override
    public int getOrder() {
        return 30;
    }

    private TraceContext createTraceContext(AspectContext context, Traced traced) {
        TraceContext currentTrace = traceService.getCurrentTrace();

        String operationName = traced.operationName().isEmpty()
                ? context.getMethod().getName()
                : traced.operationName();

        String serviceName = traced.serviceName().isEmpty()
                ? context.getTarget().getClass().getSimpleName()
                : traced.serviceName();

        if (currentTrace != null && !traced.forceNewSpan()) {
            return traceService.createChildSpan(operationName);
        }
        return traceService.startTrace(operationName, serviceName);
    }

    private void addMethodParameters(TraceContext context, Object[] arguments, Class<?>[] parameterTypes, int maxSize) {
        if (!context.getAttributes().isEmpty() || arguments == null || arguments.length == 0) {
            return;
        }

        IntStream.range(0, arguments.length)
                .forEach(index -> {
                    String typeName = parameterTypes != null && index < parameterTypes.length
                            ? parameterTypes[index].getSimpleName()
                            : "param";
                    String attributeKey = "param." + index + "." + typeName;
                    context.addAttribute(attributeKey, formatParameterValue(arguments[index], maxSize));
                });
    }

    private void addCustomTags(TraceContext context, String[] tags) {
        if (tags == null || tags.length == 0) {
            return;
        }

        Arrays.stream(tags)
                .map(tag -> tag.split("=", 2))
                .forEach(parts -> {
                    if (parts.length == 2) {
                        context.addAttribute(parts[0], parts[1]);
                    } else {
                        context.addAttribute("tag." + parts[0], "true");
                    }
                });
    }

    private String formatParameterValue(Object value, int maxSize) {
        if (value == null) {
            return "null";
        }

        String stringValue = value.toString();
        return stringValue.length() > maxSize ? stringValue.substring(0, Math.max(0, maxSize - 3)) + "..." : stringValue;
    }

    private CompletionStage<?> handleAsyncStage(CompletionStage<?> stage, TraceContext traceContext, Traced traced,
                                                TraceContext previousContext, long startTime) {
        return stage.whenComplete((value, throwable) -> {
            TraceContext before = traceService.getCurrentTrace();
            traceService.setCurrentTrace(traceContext);
            try {
                if (throwable == null) {
                    recordSuccess(traceContext, traced, value, startTime);
                    traceService.completeCurrentTrace(TraceContext.TraceStatus.COMPLETED);
                } else {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                            ? throwable.getCause() : throwable;
                    recordFailure(traceContext, traced, cause, startTime);
                    traceService.completeCurrentTraceWithError(cause);
                }
            } finally {
                restorePreviousContext(previousContext != null ? previousContext : before);
            }
        });
    }

    private void recordSuccess(TraceContext traceContext, Traced traced, Object result, long startTime) {
        if (traced.includeReturnValue() && result != null) {
            traceContext.addAttribute("return.value", formatParameterValue(result, traced.maxParameterSize()));
        }
        long duration = System.currentTimeMillis() - startTime;
        traceContext.addMetric("execution.duration", duration);
        traceContext.complete(TraceContext.TraceStatus.COMPLETED);
    }

    private void recordFailure(TraceContext traceContext, Traced traced, Throwable throwable, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        traceContext.addMetric("error.duration", duration);
        traceContext.addAttribute("error.type", throwable.getClass().getSimpleName());
        traceContext.addAttribute("error.message", throwable.getMessage());
        if (traced.includeStackTraces()) {
            traceContext.addAttribute("error.stack", Arrays.toString(throwable.getStackTrace()));
        }
        traceContext.completeWithError(throwable);
    }

    private void restorePreviousContext(TraceContext previousContext) {
        traceService.setCurrentTrace(previousContext);
    }

}
