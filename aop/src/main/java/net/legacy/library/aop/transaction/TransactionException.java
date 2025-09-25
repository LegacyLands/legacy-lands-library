package net.legacy.library.aop.transaction;

import lombok.Getter;

/**
 * Exception thrown when a distributed transaction operation fails.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class TransactionException extends RuntimeException {

    private final String transactionId;
    private final TransactionStatus transactionStatus;

    /**
     * Constructs a new transaction exception with the specified message.
     *
     * @param message the error message
     */
    public TransactionException(String message) {
        super(message);
        this.transactionId = null;
        this.transactionStatus = null;
    }

    /**
     * Constructs a new transaction exception with the specified message and cause.
     *
     * @param message the error message
     * @param cause the cause of the exception
     */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
        this.transactionId = null;
        this.transactionStatus = null;
    }

    /**
     * Constructs a new transaction exception with transaction context.
     *
     * @param message the error message
     * @param transactionId the transaction ID
     * @param transactionStatus the transaction status
     */
    public TransactionException(String message, String transactionId, TransactionStatus transactionStatus) {
        super(message);
        this.transactionId = transactionId;
        this.transactionStatus = transactionStatus;
    }

    /**
     * Constructs a new transaction exception with transaction context and cause.
     *
     * @param message the error message
     * @param transactionId the transaction ID
     * @param transactionStatus the transaction status
     * @param cause the cause of the exception
     */
    public TransactionException(String message, String transactionId, TransactionStatus transactionStatus, Throwable cause) {
        super(message, cause);
        this.transactionId = transactionId;
        this.transactionStatus = transactionStatus;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TransactionException: ").append(getMessage());

        if (transactionId != null) {
            sb.append(" [transactionId=").append(transactionId);
        }

        if (transactionStatus != null) {
            sb.append(", status=").append(transactionStatus);
        }

        if (transactionId != null) {
            sb.append("]");
        }

        return sb.toString();
    }

}