package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for distributed transaction management in enterprise environments.
 *
 * <p>Methods annotated with {@code @DistributedTransaction} will be wrapped with
 * distributed transaction management logic to ensure consistency across multiple
 * services and data sources. This annotation supports various transaction propagation
 * behaviors and isolation levels suitable for microservice architectures.
 *
 * <p>The transaction coordinator implements a two-phase commit protocol with
 * compensation mechanisms for handling failures and ensuring data consistency.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedTransaction {

    /**
     * Specifies the transaction propagation behavior.
     *
     * <p>Defines how transactions should be propagated when nested transactional
     * methods are called. This controls the transaction boundary behavior.
     *
     * @return the transaction propagation behavior
     * @see Propagation
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * Specifies the transaction isolation level.
     *
     * <p>Defines the degree of isolation between concurrent transactions.
     * Higher isolation levels provide better consistency but may impact performance.
     *
     * @return the transaction isolation level
     * @see Isolation
     */
    Isolation isolation() default Isolation.DEFAULT;

    /**
     * Specifies the transaction timeout in seconds.
     *
     * <p>If the transaction execution exceeds this timeout, it will be automatically
     * rolled back to prevent long-running transactions from blocking system resources.
     *
     * <p>Default value is 30 seconds, which provides a reasonable balance between
     * allowing complex operations to complete and preventing resource starvation.
     *
     * @return the transaction timeout in seconds
     */
    int timeout() default 30;

    /**
     * Specifies whether the transaction is read-only.
     *
     * <p>Read-only transactions can be optimized by the underlying transaction
     * manager as they don't need to acquire write locks or perform write operations.
     *
     * @return true if the transaction is read-only
     */
    boolean readOnly() default false;

    /**
     * Specifies exception types that should trigger transaction rollback.
     *
     * <p>When an exception of any of these types is thrown, the transaction
     * will be marked for rollback. If not specified, runtime exceptions will
     * trigger rollback by default.
     *
     * @return array of exception types that should trigger rollback
     */
    Class<? extends Throwable>[] rollbackFor() default {};

    /**
     * Specifies exception types that should NOT trigger transaction rollback.
     *
     * <p>When an exception of any of these types is thrown, the transaction
     * will NOT be rolled back, allowing for business-level exception handling.
     *
     * @return array of exception types that should not trigger rollback
     */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /**
     * Specifies the transaction name for monitoring and logging.
     *
     * <p>If not specified, the method name will be used as the transaction name.
     * This name is used in transaction logs, monitoring metrics, and tracing.
     *
     * @return the transaction name
     */
    String name() default "";

    /**
     * Specifies whether to enable distributed tracing for this transaction.
     *
     * <p>When enabled, the transaction will be traced across service boundaries,
     * providing complete visibility into the transaction execution path.
     *
     * @return true if distributed tracing should be enabled
     */
    boolean enableTracing() default true;

    /**
     * Enumeration of transaction propagation behaviors.
     *
     * <p>Each propagation behavior defines how transactional boundaries should be
     * handled when one transactional method calls another.
     */
    enum Propagation {
        /**
         * Support a current transaction, create a new one if none exists.
         * This is the most common setting.
         */
        REQUIRED,

        /**
         * Create a new transaction, suspending the current transaction if one exists.
         */
        REQUIRES_NEW,

        /**
         * Execute within a nested transaction if a current transaction exists.
         */
        NESTED,

        /**
         * Support a current transaction, execute non-transactionally if none exists.
         */
        SUPPORTS,

        /**
         * Execute non-transactionally, suspend the current transaction if one exists.
         */
        NOT_SUPPORTED,

        /**
         * Execute non-transactionally, throw an exception if a transaction exists.
         */
        NEVER,

        /**
         * Support a current transaction, throw an exception if none exists.
         */
        MANDATORY
    }

    /**
     * Enumeration of transaction isolation levels.
     *
     * <p>Isolation levels define the degree of isolation between concurrent
     * transactions, affecting consistency and performance characteristics.
     */
    enum Isolation {
        /**
         * Use the default isolation level of the underlying datastore.
         */
        DEFAULT,

        /**
         * The lowest isolation level. Allows dirty reads, non-repeatable reads, and phantom reads.
         */
        READ_UNCOMMITTED,

        /**
         * Prevents dirty reads, but allows non-repeatable reads and phantom reads.
         */
        READ_COMMITTED,

        /**
         * Prevents dirty reads and non-repeatable reads, but allows phantom reads.
         */
        REPEATABLE_READ,

        /**
         * The highest isolation level. Prevents dirty reads, non-repeatable reads, and phantom reads.
         */
        SERIALIZABLE
    }

}
