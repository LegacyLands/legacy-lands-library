package net.legacy.library.aop.pointcut;

import io.fairyproject.container.InjectableComponent;
import net.legacy.library.aop.pointcut.impl.AnnotationPointcut;
import net.legacy.library.aop.pointcut.impl.CompositePointcut;
import net.legacy.library.aop.pointcut.impl.ExecutionPointcut;
import net.legacy.library.aop.pointcut.impl.WithinPointcut;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for pointcut expressions, converting string expressions into Pointcut objects.
 *
 * <p>This parser supports multiple pointcut types including execution, within, and
 * annotation-based expressions. It provides a flexible way to define where aspects
 * should be applied using a familiar syntax inspired by AspectJ.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:10
 */
@InjectableComponent
public class PointcutExpressionParser {

    private static final Pattern EXECUTION_PATTERN = Pattern.compile(
            "execution\\s*\\(\\s*(.+)\\s*\\)"
    );

    private static final Pattern WITHIN_PATTERN = Pattern.compile(
            "within\\s*\\(\\s*(.+)\\s*\\)"
    );

    private static final Pattern ANNOTATION_PATTERN = Pattern.compile(
            "@annotation\\s*\\(\\s*(.+)\\s*\\)"
    );

    /**
     * Parses a pointcut expression string into a Pointcut object.
     *
     * @param expression the pointcut expression to parse
     * @return the parsed Pointcut
     * @throws IllegalArgumentException if the expression is invalid
     */
    public Pointcut parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("Pointcut expression cannot be null or empty");
        }

        expression = expression.trim();

        // Check for composite expressions (AND/OR)
        if (expression.contains(" && ") || expression.contains(" || ")) {
            return parseComposite(expression);
        }

        // Parse single expressions
        Matcher executionMatcher = EXECUTION_PATTERN.matcher(expression);
        if (executionMatcher.matches()) {
            return new ExecutionPointcut(executionMatcher.group(1).trim());
        }

        Matcher withinMatcher = WITHIN_PATTERN.matcher(expression);
        if (withinMatcher.matches()) {
            return new WithinPointcut(withinMatcher.group(1).trim());
        }

        Matcher annotationMatcher = ANNOTATION_PATTERN.matcher(expression);
        if (annotationMatcher.matches()) {
            return new AnnotationPointcut(annotationMatcher.group(1).trim());
        }

        throw new IllegalArgumentException("Invalid pointcut expression: " + expression);
    }

    /**
     * Parses composite pointcut expressions containing AND (&&) or OR (||) operators.
     *
     * <p>This method handles expressions that combine multiple pointcuts using logical
     * operators. It supports both conjunction (AND) and disjunction (OR) operations,
     * but not mixed operators in a single expression.
     *
     * @param expression the composite expression containing && or || operators
     * @return a CompositePointcut representing the logical combination
     */
    private Pointcut parseComposite(String expression) {
        CompositePointcut composite = new CompositePointcut();

        // Simple parsing for AND/OR operations
        String[] parts;
        boolean isAnd = true;

        if (expression.contains(" && ")) {
            parts = expression.split(" && ");
        } else {
            parts = expression.split(" \\|\\| ");
            isAnd = false;
        }

        for (String part : parts) {
            Pointcut pointcut = parse(part.trim());
            composite.addPointcut(pointcut, isAnd);
        }

        return composite;
    }

}