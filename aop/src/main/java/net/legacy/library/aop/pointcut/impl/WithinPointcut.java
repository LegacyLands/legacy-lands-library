package net.legacy.library.aop.pointcut.impl;

import lombok.Getter;
import net.legacy.library.aop.pointcut.Pointcut;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Pointcut implementation that matches all methods within specified types or packages.
 *
 * <p>Supports patterns like:
 * <ul>
 *   <li>{@code net.legacy.library.service.*}: All types in the service package</li>
 *   <li>{@code net.legacy.library.service..*}: All types in service and sub-packages</li>
 *   <li>{@code *Service}: All types ending with Service</li>
 * </ul>
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:20
 */
@Getter
public class WithinPointcut implements Pointcut {
    private final String typePattern;
    private final Pattern compiledPattern;
    
    public WithinPointcut(String typePattern) {
        this.typePattern = typePattern;
        this.compiledPattern = compilePattern(typePattern);
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>Within pointcuts match all methods in the specified types, so this
     * implementation simply delegates to {@link #matchesClass(Class)}.
     *
     * @param method {@inheritDoc}
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return matchesClass(targetClass);
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation matches classes based on their fully qualified names
     * against the type pattern.
     *
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matchesClass(Class<?> targetClass) {
        String className = targetClass.getName();
        return compiledPattern.matcher(className).matches();
    }
    
    private Pattern compilePattern(String pattern) {
        // Convert the pattern to a regex
        String regex = pattern
            .replace("$", "\\$")           // Escape dollar signs first (for inner classes)
            .replace(".", "\\.")           // Escape dots
            .replace("..", "\\.[\\w\\.]*") // .. means any number of packages
            .replace("*", "[\\w]*");       // * means any characters (but not dots)
            
        // If pattern doesn't start with *, anchor it to the beginning
        if (!pattern.startsWith("*")) {
            regex = "^" + regex;
        }
        
        // If pattern doesn't end with *, anchor it to the end
        if (!pattern.endsWith("*") && !pattern.endsWith("..")) {
            regex = regex + "$";
        }
        
        return Pattern.compile(regex);
    }
}