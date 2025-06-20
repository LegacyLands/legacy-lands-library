package net.legacy.library.aop.pointcut.impl;

import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.pointcut.Pointcut;
import net.legacy.library.aop.pointcut.PointcutExpressionParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Composite pointcut that combines multiple pointcuts with AND/OR operations.
 *
 * <p>This allows complex pointcut expressions like:
 * <ul>
 *   <li>{@code execution(* *Service.*(..)) && @annotation(Monitored)}</li>
 *   <li>{@code within(net.legacy.service..*) || within(net.legacy.controller..*)}</li>
 * </ul>
 *
 * <p>The composite evaluates pointcuts in the order they were added, with proper
 * precedence for AND/OR operations. Note that mixed operators in a single expression
 * are evaluated left-to-right without parentheses support.
 *
 * @author qwq-dev
 * @version 1.0
 * @see Pointcut
 * @see PointcutExpressionParser
 * @since 2025-06-20 19:30
 */
public class CompositePointcut implements Pointcut {
    private final List<PointcutEntry> pointcuts = new ArrayList<>();
    
    /**
     * Adds a pointcut to the composite with the specified operation.
     *
     * @param pointcut the pointcut to add
     * @param isAnd true for AND operation, false for OR operation
     */
    public void addPointcut(Pointcut pointcut, boolean isAnd) {
        pointcuts.add(new PointcutEntry(pointcut, isAnd));
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation evaluates all contained pointcuts with their
     * respective AND/OR operations in the order they were added.
     *
     * @param method {@inheritDoc}
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        if (pointcuts.isEmpty()) {
            return false;
        }
        
        // Start with the first pointcut
        boolean result = pointcuts.getFirst().pointcut.matches(method, targetClass);
        
        // Apply subsequent pointcuts with their operations
        for (int i = 1; i < pointcuts.size(); i++) {
            PointcutEntry entry = pointcuts.get(i);
            boolean matches = entry.pointcut.matches(method, targetClass);
            
            if (entry.isAnd) {
                result = result && matches;
            } else {
                result = result || matches;
            }
        }
        
        return result;
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation uses conservative matching: returns true if any
     * OR condition might match, or if all AND conditions might match.
     *
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matchesClass(Class<?> targetClass) {
        if (pointcuts.isEmpty()) {
            return false;
        }
        
        // For class matching, we need to be more conservative
        // If any OR pointcut might match, return true
        // If all AND pointcuts might match, return true
        
        boolean hasAnd = false;
        boolean hasOr = false;
        
        for (PointcutEntry entry : pointcuts) {
            if (entry.isAnd) {
                hasAnd = true;
                if (!entry.pointcut.matchesClass(targetClass)) {
                    return false; // One AND condition failed
                }
            } else {
                hasOr = true;
                if (entry.pointcut.matchesClass(targetClass)) {
                    return true; // One OR condition succeeded
                }
            }
        }
        
        // If we only had AND conditions and all passed, return true
        // If we only had OR conditions and none passed, return false
        return hasAnd && !hasOr;
    }
    
    /**
     * Internal class to hold a pointcut and its operation type.
     */
    @RequiredArgsConstructor
    private static class PointcutEntry {
        final Pointcut pointcut;
        final boolean isAnd;
    }
}