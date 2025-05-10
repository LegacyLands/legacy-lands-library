package net.legacy.library.commons.task;

import io.fairyproject.mc.scheduler.MCScheduler;
import io.fairyproject.mc.scheduler.MCSchedulers;
import io.fairyproject.scheduler.ScheduledTask;
import io.fairyproject.scheduler.repeat.RepeatPredicate;
import io.fairyproject.scheduler.response.TaskResponse;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The {@code TaskInterface} provides a high-level abstraction for scheduling and running tasks
 * using the {@link MCScheduler} (usually provided by {@link MCSchedulers}). It also offers
 * methods for executing tasks using Java's virtual threads via {@link ExecutorService} and
 * {@link ScheduledExecutorService}.
 *
 * <p>This interface simplifies task scheduling by providing convenient methods with consistent
 * naming and parameter orders, both for traditional {@link MCScheduler} tasks and for tasks
 * utilizing virtual threads.
 *
 * <p>By default, the scheduler returned from {@link #getMCScheduler()} is the asynchronous scheduler
 * ({@link MCSchedulers#getAsyncScheduler()}); however, implementations can override this method
 * to return other schedulers if needed. Methods related to virtual threads leverage
 * {@link Executors#newVirtualThreadPerTaskExecutor()}.
 *
 * <p>This interface is intended for classes that encapsulate their own internal scheduling logic.
 * For instance, a class implementing this interface might define how and when tasks start by
 * overriding the {@link #start()} method, potentially using either the {@link MCScheduler} or
 * virtual thread execution methods.
 *
 * @param <R> the type of the result returned by the task, which could be {@link ScheduledTask},
 *            {@link Future}, {@link ScheduledFuture}, or another type depending on the implementation
 * @author qwq-dev
 * @see MCSchedulers
 * @see MCScheduler
 * @see io.fairyproject.scheduler.Scheduler
 * @since 2024-12-14 12:30
 */
public interface TaskInterface<R> {
    /**
     * Starts the task. Implementations should define the logic of the task within this method.
     *
     * <p>By default, overriding this method will schedule the task asynchronously, depending on the
     * {@link MCScheduler} provided by {@link #getMCScheduler()}. This method could, for example,
     * schedule periodic tasks or a single one-time task.
     *
     * @return an object representing the started task
     */
    R start();

    /**
     * Provides the {@link MCScheduler} instance used for scheduling tasks.
     *
     * <p>By default, this returns {@code MCSchedulers.getAsyncScheduler()}, but implementations can
     * override it to return a different scheduler (e.g., {@link MCSchedulers#getGlobalScheduler()}, or a custom one).
     *
     * @return the {@link MCScheduler} scheduler instance
     */
    default MCScheduler getMCScheduler() {
        return MCSchedulers.getAsyncScheduler();
    }

    /**
     * Provides an {@link ExecutorService} that uses a virtual thread per task execution model.
     *
     * <p>This method returns a new {@link ExecutorService} instance where each submitted task is
     * executed in its own virtual thread. Virtual threads are lightweight and allow for high concurrency,
     * making them suitable for tasks that are I/O-bound or involve waiting.
     *
     * <p>The returned executor is intended for scenarios where task isolation and concurrency are
     * important, without the overhead associated with traditional threads.
     *
     * @return a virtual thread-per-task {@link ExecutorService} instance
     */
    default ExecutorService getVirtualThreadPerTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Submits a task for execution in a virtual thread.
     *
     * @param task the task to be executed in a virtual thread
     */
    default void executeWithVirtualThread(Runnable task) {
        getVirtualThreadPerTaskExecutor().execute(task);
    }

    /**
     * Submits a task for execution in a virtual thread and returns a {@link Future} result.
     *
     * @param task the task to be executed in a virtual thread
     * @return a {@link Future} that can be used to retrieve the result of the task
     */
    default <T> Future<T> submitWithVirtualThread(Callable<T> task) {
        return getVirtualThreadPerTaskExecutor().submit(task);
    }

    /**
     * Submits a task for execution in a virtual thread and returns a {@link Future} with a preset result.
     *
     * @param task   the task to be executed in a virtual thread
     * @param result the result to be returned upon the task's completion
     * @param <T>    the type of the result
     * @return a {@link Future} representing the task with the preset result
     */
    default <T> Future<T> submitWithVirtualThread(Runnable task, T result) {
        return getVirtualThreadPerTaskExecutor().submit(task, result);
    }

    /**
     * Submits a {@link Runnable} task for execution in a virtual thread and returns a {@link Future} result.
     *
     * @param runnable the {@link Runnable} task to be executed in a virtual thread
     * @return a {@link Future} representing the pending completion of the task
     */
    default Future<?> submitWithVirtualThread(Runnable runnable) {
        return getVirtualThreadPerTaskExecutor().submit(runnable);
    }

    /**
     * Submits a value-returning task for execution in a virtual thread and returns a {@link CompletableFuture}.
     *
     * @param task the task to be executed
     * @param <T>  the type of the result
     * @return a {@link CompletableFuture<T>} that will be completed with the task's result
     * @throws RuntimeException if the callable task throws any Throwable during execution
     */
    default <T> CompletableFuture<T> submitWithVirtualThreadAsync(Callable<T> task) throws RuntimeException {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }, getVirtualThreadPerTaskExecutor());
    }

    /**
     * Submits a task for execution in a virtual thread with a preset result and returns a {@link CompletableFuture}.
     *
     * @param task   the task to be executed
     * @param result the result to return upon completion
     * @param <T>    the type of the result
     * @return a {@link CompletableFuture<T>} that will be completed with the preset result
     */
    default <T> CompletableFuture<T> submitWithVirtualThreadAsync(Runnable task, T result) {
        return CompletableFuture.runAsync(task, getVirtualThreadPerTaskExecutor())
                .thenApply(v -> result);
    }

    /**
     * Submits a {@link Runnable} task for execution in a virtual thread and returns a {@link CompletableFuture}.
     *
     * @param runnable the task to be executed
     * @return a {@link CompletableFuture<Void>} representing the pending completion of the task
     */
    default CompletableFuture<Void> submitWithVirtualThreadAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, getVirtualThreadPerTaskExecutor());
    }

    /**
     * Schedules a one-time {@link Runnable} task to be executed in a virtual thread after a specified delay,
     * using ScheduledExecutorService for scheduling and virtual threads for execution.
     *
     * @param task  the task to be executed
     * @param delay the delay before execution
     * @param unit  the time unit of the delay parameter
     * @return a {@link VirtualThreadScheduledFuture} representing the pending completion of the task
     */
    default VirtualThreadScheduledFuture scheduleWithVirtualThread(Runnable task, long delay, TimeUnit unit) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService virtualThreadExecutor = getVirtualThreadPerTaskExecutor();
        ScheduledFuture<?> scheduledFuture =
                scheduler.schedule(() -> virtualThreadExecutor.execute(task), delay, unit);
        return new VirtualThreadScheduledFuture(scheduledFuture, scheduler);
    }

    /**
     * Schedules a one-time {@link Callable} task to be executed in a virtual thread after a specified delay,
     * using ScheduledExecutorService for scheduling and virtual threads for execution.
     *
     * @param task  the task to be executed
     * @param delay the delay before execution
     * @param unit  the time unit of the delay parameter
     * @return a {@link VirtualThreadScheduledFuture} representing the pending completion of the task
     */
    default <T> VirtualThreadScheduledFuture scheduleWithVirtualThread(Callable<T> task, long delay, TimeUnit unit) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService virtualThreadExecutor = getVirtualThreadPerTaskExecutor();
        ScheduledFuture<T> scheduledFuture =
                scheduler.schedule(() -> virtualThreadExecutor.submit(task).get(), delay, unit);
        return new VirtualThreadScheduledFuture(scheduledFuture, scheduler);
    }

    /**
     * Schedules a recurring {@link Runnable} task to be executed in a virtual thread with a fixed rate,
     * using ScheduledExecutorService for scheduling and virtual threads for execution.
     *
     * @param task         the task to be executed
     * @param initialDelay the delay before the first execution
     * @param period       the period between successive executions
     * @param unit         the time unit for initialDelay and period
     * @return a {@link VirtualThreadScheduledFuture} representing the pending completion of the task
     */
    default VirtualThreadScheduledFuture scheduleAtFixedRateWithVirtualThread(Runnable task, long initialDelay, long period, TimeUnit unit) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService virtualThreadExecutor = getVirtualThreadPerTaskExecutor();
        ScheduledFuture<?> scheduledFuture =
                scheduler.scheduleAtFixedRate(() -> virtualThreadExecutor.execute(task), initialDelay, period, unit);
        return new VirtualThreadScheduledFuture(scheduledFuture, scheduler);
    }

    /**
     * Schedules a recurring {@link Runnable} task to be executed in a virtual thread with
     * a fixed delay between the end of the execution of one task and the start of the next,
     * using ScheduledExecutorService for scheduling and virtual threads for execution.
     *
     * @param task         the task to be executed
     * @param initialDelay the delay before the first execution
     * @param delay        the delay between the end of one execution and the start of the next
     * @param unit         the time unit for initialDelay and delay
     * @return a {@link VirtualThreadScheduledFuture} representing the pending completion of the task
     */
    default VirtualThreadScheduledFuture scheduleWithFixedDelayWithVirtualThread(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService virtualThreadExecutor = getVirtualThreadPerTaskExecutor();
        ScheduledFuture<?> scheduledFuture =
                scheduler.scheduleWithFixedDelay(() -> virtualThreadExecutor.execute(task), initialDelay, delay, unit);
        return new VirtualThreadScheduledFuture(scheduledFuture, scheduler);
    }

    /**
     * Schedule a one-time {@link Runnable} task with a specified delay in ticks.
     *
     * @param task       the task to be executed
     * @param delayTicks the delay in ticks before the task is executed
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> schedule(Runnable task, long delayTicks) {
        return getMCScheduler().schedule(task, delayTicks);
    }

    /**
     * Schedule a periodic {@link Runnable} task with a fixed delay and interval in ticks.
     *
     * @param task          the task to be executed
     * @param delayTicks    the initial delay in ticks before the first execution
     * @param intervalTicks the interval in ticks between consecutive executions
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> scheduleAtFixedRate(Runnable task, long delayTicks, long intervalTicks) {
        return getMCScheduler().scheduleAtFixedRate(task, delayTicks, intervalTicks);
    }

    /**
     * Schedule a periodic {@link Runnable} task with a fixed delay and interval in ticks, and a custom
     * {@link RepeatPredicate}.
     *
     * <p>The {@link RepeatPredicate} can be used to dynamically determine whether the task should continue
     * running or be terminated based on the logic you define.
     *
     * @param task          the task to be executed
     * @param delayTicks    the initial delay in ticks before the first execution
     * @param intervalTicks the interval in ticks between consecutive executions
     * @param predicate     the {@link RepeatPredicate} to control repetition
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> scheduleAtFixedRate(Runnable task, long delayTicks, long intervalTicks, RepeatPredicate<?> predicate) {
        return getMCScheduler().scheduleAtFixedRate(task, delayTicks, intervalTicks, predicate);
    }

    /**
     * Schedule a one-time {@link Callable} task with a specified delay in ticks.
     *
     * <p>The returned {@link ScheduledTask} will also carry the result of the callable upon completion.
     *
     * @param task       the callable task to be executed
     * @param delayTicks the delay in ticks before the task is executed
     * @param <T>        the return type of the callable
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> schedule(Callable<T> task, long delayTicks) {
        return getMCScheduler().schedule(task, delayTicks);
    }

    /**
     * Schedule a periodic {@link Callable} task with a fixed delay and interval in ticks.
     *
     * <p>The callable is expected to return a {@link TaskResponse} which defines whether the task
     * should continue or terminate.
     *
     * @param task          the callable task to be executed
     * @param delayTicks    the initial delay in ticks before the first execution
     * @param intervalTicks the interval in ticks between consecutive executions
     * @param <T>           the return type wrapped by {@link TaskResponse}
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> scheduleAtFixedRate(Callable<TaskResponse<T>> task, long delayTicks, long intervalTicks) {
        return getMCScheduler().scheduleAtFixedRate(task, delayTicks, intervalTicks);
    }

    /**
     * Schedule a periodic {@link Callable} task with a fixed delay and interval in ticks, and a custom
     * {@link RepeatPredicate}.
     *
     * <p>The {@link RepeatPredicate} will control the repetition logic using the callable's return value.
     *
     * @param task          the callable task to be executed
     * @param delayTicks    the initial delay in ticks before the first execution
     * @param intervalTicks the interval in ticks between consecutive executions
     * @param predicate     the {@link RepeatPredicate} to control repetition
     * @param <T>           the return type wrapped by {@link TaskResponse}
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> scheduleAtFixedRate(Callable<TaskResponse<T>> task, long delayTicks, long intervalTicks, RepeatPredicate<T> predicate) {
        return getMCScheduler().scheduleAtFixedRate(task, delayTicks, intervalTicks, predicate);
    }

    /**
     * Schedule a one-time {@link Runnable} task without any initial delay (i.e., run as soon as possible).
     *
     * @param task the task to be executed
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> schedule(Runnable task) {
        return getMCScheduler().schedule(task);
    }

    /**
     * Schedule a one-time {@link Runnable} task with a specified {@link Duration} delay.
     *
     * @param task  the task to be executed
     * @param delay the duration before the task is executed
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> schedule(Runnable task, Duration delay) {
        return getMCScheduler().schedule(task, delay);
    }

    /**
     * Schedule a periodic {@link Runnable} task using {@link Duration} based initial delay and interval.
     *
     * @param task     the task to be executed
     * @param delay    the initial delay before the first execution
     * @param interval the interval between consecutive executions
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> scheduleAtFixedRate(Runnable task, Duration delay, Duration interval) {
        return getMCScheduler().scheduleAtFixedRate(task, delay, interval);
    }

    /**
     * Schedule a periodic {@link Runnable} task using {@link Duration} based initial delay and interval,
     * with a custom {@link RepeatPredicate}.
     *
     * @param task      the task to be executed
     * @param delay     the initial delay before the first execution
     * @param interval  the interval between consecutive executions
     * @param predicate the {@link RepeatPredicate} to control repetition
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default ScheduledTask<?> scheduleAtFixedRate(Runnable task, Duration delay, Duration interval, RepeatPredicate<?> predicate) {
        return getMCScheduler().scheduleAtFixedRate(task, delay, interval, predicate);
    }

    /**
     * Schedule a one-time {@link Callable} task with no initial delay.
     *
     * @param task the callable task to be executed
     * @param <T>  the return type of the callable
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> schedule(Callable<T> task) {
        return getMCScheduler().schedule(task);
    }

    /**
     * Schedule a one-time {@link Callable} task with a specified {@link Duration} delay.
     *
     * @param task  the callable task to be executed
     * @param delay the duration before the task is executed
     * @param <T>   the return type of the callable
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> schedule(Callable<T> task, Duration delay) {
        return getMCScheduler().schedule(task, delay);
    }

    /**
     * Schedule a periodic {@link Callable} task using {@link Duration} based initial delay and interval.
     *
     * <p>The callable is expected to return a {@link TaskResponse} that informs whether the task should continue.
     *
     * @param task     the callable task to be executed
     * @param delay    the initial delay before the first execution
     * @param interval the interval between consecutive executions
     * @param <T>      the return type wrapped by {@link TaskResponse}
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> scheduleAtFixedRate(Callable<TaskResponse<T>> task, Duration delay, Duration interval) {
        return getMCScheduler().scheduleAtFixedRate(task, delay, interval);
    }

    /**
     * Schedule a periodic {@link Callable} task using {@link Duration} based initial delay and interval,
     * with a custom {@link RepeatPredicate}.
     *
     * @param task      the callable task to be executed
     * @param delay     the initial delay before the first execution
     * @param interval  the interval between consecutive executions
     * @param predicate the {@link RepeatPredicate} to control repetition
     * @param <T>       the return type wrapped by {@link TaskResponse}
     * @return a {@link ScheduledTask} representing the scheduled task
     */
    default <T> ScheduledTask<T> scheduleAtFixedRate(Callable<TaskResponse<T>> task, Duration delay, Duration interval, RepeatPredicate<T> predicate) {
        return getMCScheduler().scheduleAtFixedRate(task, delay, interval, predicate);
    }

    /**
     * Check if the current thread is the same thread used by the scheduler returned
     * by {@link #getMCScheduler()}.
     *
     * @return {@code true} if the current thread is the scheduler's thread, {@code false} otherwise
     */
    default boolean isCurrentThread() {
        return getMCScheduler().isCurrentThread();
    }

    /**
     * Execute a {@link Runnable} task immediately, typically using the {@link MCScheduler#execute(Runnable)}
     * method of the configured scheduler. This is useful for tasks you want to run as soon as possible,
     * without any delay or periodic repetition.
     *
     * @param task the task to be executed
     */
    default void execute(Runnable task) {
        getMCScheduler().execute(task);
    }
}
