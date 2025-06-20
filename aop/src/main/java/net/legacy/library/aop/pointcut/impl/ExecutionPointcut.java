package net.legacy.library.aop.pointcut.impl;

import net.legacy.library.aop.pointcut.Pointcut;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.regex.Pattern;

/**
 * Pointcut implementation that matches method executions based on method signatures.
 *
 * <p>Supports patterns like:
 * <ul>
 *   <li>{@code * *(..)}: All methods</li>
 *   <li>{@code public * net.legacy..*.*(..)}: All public methods in net.legacy packages</li>
 *   <li>{@code * *Service.save*(..)}: All save* methods in classes ending with Service</li>
 * </ul>
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:15
 */
public class ExecutionPointcut implements Pointcut {
    private final Pattern modifierPattern;
    private final Pattern returnTypePattern;
    private final Pattern classPattern;
    private final Pattern methodPattern;
    private final Pattern parameterPattern;
    
    // Parsed components
    private String modifierPart;
    private String returnTypePart;
    private String classPart;
    private String methodPart;
    private String parameterPart;
    
    public ExecutionPointcut(String expression) {
        parseExpression(expression);
        
        // Initialize patterns based on parsed expression
        this.modifierPattern = createModifierPattern();
        this.returnTypePattern = createReturnTypePattern();
        this.classPattern = createClassPattern();
        this.methodPattern = createMethodPattern();
        this.parameterPattern = createParameterPattern();
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation matches methods based on the execution expression pattern,
     * considering modifiers, return type, class name, method name, and parameters.
     *
     * @param method {@inheritDoc}
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        // Check modifiers
        if (!matchesModifiers(method)) {
            return false;
        }
        
        // Check return type
        if (!matchesReturnType(method)) {
            return false;
        }
        
        // Check class name
        if (!matchesClassName(targetClass)) {
            return false;
        }
        
        // Check method name
        if (!matchesMethodName(method)) {
            return false;
        }
        
        // Check parameters
        return matchesParameters(method);
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation performs a coarse-grained filter based on the class pattern
     * extracted from the execution expression.
     *
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matchesClass(Class<?> targetClass) {
        return classPattern == null || matchesClassName(targetClass);
    }
    
    /**
     * Parses the execution expression to extract pattern components.
     *
     * <p>This method analyzes the expression to identify modifiers, return type,
     * class pattern, method pattern, and parameter patterns.
     *
     * <p>Expected format: {@code [modifiers] return-type [declaring-type].method-name(parameters)}
     *
     * @param expression the execution expression to parse
     */
    private void parseExpression(String expression) {
        // Remove leading/trailing whitespace
        String trimmed = expression.trim();
        
        // Find parameter section first (everything in parentheses)
        int openParen = trimmed.indexOf('(');
        int closeParen = trimmed.lastIndexOf(')');
        
        if (openParen == -1 || closeParen == -1 || closeParen < openParen) {
            throw new IllegalArgumentException(
                String.format("Invalid execution expression '%s': missing or malformed parentheses. " +
                    "Expected format: [modifiers] return-type [declaring-type].method-name(parameters)",
                    expression));
        }
        
        // Extract parameter part
        this.parameterPart = trimmed.substring(openParen + 1, closeParen).trim();
        
        // Get the part before parameters
        String beforeParams = trimmed.substring(0, openParen).trim();
        
        if (beforeParams.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("Invalid execution expression '%s': missing method signature before parameters",
                    expression));
        }
        
        // Find the last dot to separate method name from class/type
        int lastDot = beforeParams.lastIndexOf('.');
        
        if (lastDot == -1) {
            // No class specified, only method pattern
            parseModifierAndReturnType(beforeParams);
            this.classPart = "*";
            int lastSpace = beforeParams.lastIndexOf(' ');
            if (lastSpace == -1) {
                throw new IllegalArgumentException(
                    String.format("Invalid execution expression '%s': missing return type. " +
                        "Expected format: [modifiers] return-type method-name(parameters)",
                        expression));
            }
            this.methodPart = beforeParams.substring(lastSpace + 1);
        } else {
            // Extract method name
            this.methodPart = beforeParams.substring(lastDot + 1).trim();
            
            // Get the part before method name
            String beforeMethod = beforeParams.substring(0, lastDot).trim();
            
            // Parse modifiers and return type
            parseModifierAndReturnType(beforeMethod);
            
            // Extract class pattern (everything after return type)
            int lastSpace = beforeMethod.lastIndexOf(' ');
            if (lastSpace != -1 && hasReturnType(beforeMethod)) {
                this.classPart = beforeMethod.substring(lastSpace + 1).trim();
            } else {
                this.classPart = beforeMethod;
            }
        }
    }
    
    /**
     * Parses modifiers and return type from the expression prefix.
     *
     * @param prefix the part of expression before class.method
     */
    private void parseModifierAndReturnType(String prefix) {
        String[] parts = prefix.split("\\s+");
        
        if (parts.length == 0) {
            this.modifierPart = null;
            this.returnTypePart = "*";
            return;
        }
        
        // Check for modifiers
        String firstPart = parts[0];
        if (isModifier(firstPart)) {
            this.modifierPart = firstPart;
            if (parts.length > 1) {
                this.returnTypePart = parts[1];
            } else {
                this.returnTypePart = "*";
            }
        } else {
            this.modifierPart = null;
            this.returnTypePart = firstPart;
        }
    }
    
    /**
     * Checks if a string represents a method modifier.
     *
     * @param str the string to check
     * @return true if it's a modifier
     */
    private boolean isModifier(String str) {
        return "public".equals(str) || "protected".equals(str) || 
               "private".equals(str) || "static".equals(str) ||
               "final".equals(str) || "abstract".equals(str) ||
               "synchronized".equals(str) || "native".equals(str);
    }
    
    /**
     * Checks if the expression contains a return type.
     *
     * @param expression the expression to check
     * @return true if return type is present
     */
    private boolean hasReturnType(String expression) {
        // Simple heuristic: if there are multiple parts and first is not a modifier
        String[] parts = expression.split("\\s+");
        return parts.length > 1 || !isModifier(parts[0]);
    }
    
    /**
     * Creates a pattern to match method modifiers based on the parsed expression.
     *
     * @return a Pattern for matching modifiers, or null to match any modifier
     */
    private Pattern createModifierPattern() {
        if (modifierPart == null || modifierPart.isEmpty() || "*".equals(modifierPart)) {
            return null; // Matches any modifier
        }
        
        // Support multiple modifiers like "public static"
        // Just store the modifiers, actual matching is done in matchesModifiers
        return Pattern.compile(modifierPart);
    }
    
    /**
     * Creates a pattern to match method return types.
     *
     * @return a Pattern for matching return types
     */
    private Pattern createReturnTypePattern() {
        if (returnTypePart == null || returnTypePart.equals("*")) {
            return Pattern.compile(".*");
        }
        
        // Convert the return type pattern to regex
        String pattern = returnTypePart
            .replace(".", "\\.")  // Escape dots
            .replace("*", ".*")   // * matches anything
            .replace("$", "\\$"); // Escape dollar signs for inner classes
            
        // Handle array types - escape square brackets for regex
        pattern = pattern.replace("[", "\\[")
            .replace("]", "\\]");
        
        return Pattern.compile(pattern);
    }
    
    /**
     * Creates a pattern to match class names including package wildcards.
     *
     * <p>This method handles various wildcard patterns:
     * <ul>
     *   <li>{@code ..} - matches any number of packages</li>
     *   <li>{@code *} - matches any characters in a single segment</li>
     * </ul>
     *
     * @return a Pattern for matching class names
     */
    private Pattern createClassPattern() {
        if (classPart == null || classPart.equals("*")) {
            return Pattern.compile(".*");
        }
        
        // Build regex pattern from parsed class part
        String pattern = classPart
            .replace("$", "\\$")    // Escape dollar signs first (for inner classes)
            .replace(".", "\\.");   // Escape dots
            
        // Handle .. wildcard for packages
        if (pattern.contains("\\.\\.")) {
            pattern = pattern.replace("\\.\\.", "(\\.[\\w\\.\\$]+)*");
        }
        
        // Handle * wildcard
        pattern = pattern.replace("*", "[\\w\\$]*");
        
        // If pattern doesn't start with *, anchor to word boundary
        if (!classPart.startsWith("*")) {
            pattern = "(^|\\.)?" + pattern;
        }
        
        // If pattern doesn't end with * or .., anchor to end
        if (!classPart.endsWith("*") && !classPart.endsWith("..")) {
            pattern = pattern + "$";
        }
        
        return Pattern.compile(pattern);
    }
    
    /**
     * Creates a pattern to match method names.
     *
     * @return a Pattern for matching method names
     */
    private Pattern createMethodPattern() {
        if (methodPart == null || methodPart.equals("*")) {
            return Pattern.compile(".*");
        }
        
        // Convert method pattern to regex
        String pattern = methodPart.replace("*", ".*");
        
        // Anchor the pattern for exact matching
        if (!methodPart.contains("*")) {
            pattern = "^" + pattern + "$";
        } else {
            if (!methodPart.startsWith("*")) {
                pattern = "^" + pattern;
            }
            if (!methodPart.endsWith("*")) {
                pattern = pattern + "$";
            }
        }
        
        return Pattern.compile(pattern);
    }
    
    /**
     * Creates a pattern to match method parameters.
     *
     * <p>Currently supports:
     * <ul>
     *   <li>{@code (..)} - matches any parameters</li>
     *   <li>{@code ()} - matches no parameters</li>
     *   <li>{@code (String, int)} - matches specific parameter types</li>
     *   <li>{@code (List<String>, Map<?, ?>)} - matches generic types</li>
     *   <li>{@code (String, ..)} - matches String followed by any parameters</li>
     * </ul>
     *
     * @return a Pattern for matching parameters, or null for any parameters
     */
    private Pattern createParameterPattern() {
        if (parameterPart == null || parameterPart.equals("..")) {
            return null; // Matches any parameters
        }
        
        if (parameterPart.isEmpty()) {
            // Empty parentheses means no parameters
            return Pattern.compile("^$");
        }
        
        // Build pattern for parameter matching
        String[] params = splitParametersRespectingGenerics(parameterPart);
        StringBuilder patternBuilder = new StringBuilder();
        
        for (int i = 0; i < params.length; i++) {
            String param = params[i].trim();
            
            if (i > 0) {
                patternBuilder.append(",");
            }
            
            if (param.equals("..")) {
                // .. matches any remaining parameters
                patternBuilder.append(".*");
                break;
            } else if (param.equals("*")) {
                // * matches any single type
                patternBuilder.append("[^,]+");
            } else {
                // Handle generic types and convert to pattern
                String typePattern = createTypePattern(param);
                patternBuilder.append(typePattern);
            }
        }
        
        String pattern = patternBuilder.toString();
        io.fairyproject.log.Log.debug("Created parameter pattern: '%s' from expression: '%s'", pattern, parameterPart);
        
        return Pattern.compile(pattern);
    }
    
    /**
     * Splits parameter list respecting generic type brackets.
     *
     * @param params the parameter string
     * @return array of individual parameter types
     */
    private String[] splitParametersRespectingGenerics(String params) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        
        for (char c : params.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(current.toString());
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        
        return result.toArray(new String[0]);
    }
    
    /**
     * Creates a regex pattern for a type including generic parameters.
     *
     * @param typeStr the type string (e.g., "List<String>")
     * @return regex pattern
     */
    private String createTypePattern(String typeStr) {
        // Handle generic types
        int genericStart = typeStr.indexOf('<');
        if (genericStart == -1) {
            // Simple type without generics in the pattern
            // But it should match types with or without generics
            String basePattern = createSimpleTypePattern(typeStr);
            // Allow optional generic parameters in the actual type
            String result = basePattern + "(<.*>)?";
            io.fairyproject.log.Log.debug("Type pattern for '%s': '%s'", typeStr, result);
            return result;
        }
        
        String baseType = typeStr.substring(0, genericStart).trim();
        String genericPart = typeStr.substring(genericStart);
        
        // Convert base type
        String basePattern = createSimpleTypePattern(baseType);
        
        // Convert generic part to regex
        String genericPattern = genericPart
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("?", "\\?")
            .replace(" ", "\\s*");  // Allow flexible whitespace
            
        // Handle type parameters inside generics
        // This is complex because we need to handle nested generics
        // For now, let's handle specific common cases
        if (genericPart.contains(",")) {
            // Multiple type parameters
            String[] parts = genericPart.substring(1, genericPart.length() - 1).split(",");
            StringBuilder newPattern = new StringBuilder("\\<");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) newPattern.append(",\\s*");
                String part = parts[i].trim();
                
                if (part.equals("*")) {
                    // Wildcard - match any type
                    newPattern.append("[^,<>]+");
                    newPattern.append("(<.*>)?");
                } else if (part.contains("<") && part.contains(">")) {
                    // Nested generic type like List<*> or List<String>
                    String nestedPattern = createTypePattern(part);
                    newPattern.append(nestedPattern);
                } else if (part.matches("[A-Z][A-Za-z0-9_$]*")) {
                    // Simple type name
                    newPattern.append("((.*\\.)?").append(part).append(")");
                    newPattern.append("(<.*>)?");
                } else {
                    // Keep as is
                    newPattern.append(part.replace(".", "\\."));
                    newPattern.append("(<.*>)?");
                }
            }
            newPattern.append("\\>");
            genericPattern = newPattern.toString();
        } else {
            // Single type parameter
            String innerType = genericPart.substring(1, genericPart.length() - 1).trim();
            if (innerType.equals("*")) {
                // Wildcard
                genericPattern = "\\<[^<>]+\\>";
            } else if (innerType.matches("[A-Z][A-Za-z0-9_$]*")) {
                genericPattern = "\\<((.*\\.)?" + innerType + ")(<.*>)?\\>";
            }
        }
        
        return basePattern + genericPattern;
    }
    
    /**
     * Creates a pattern for a simple (non-generic) type.
     *
     * @param type the simple type name
     * @return regex pattern
     */
    private String createSimpleTypePattern(String type) {
        String pattern = type.trim()
            .replace(".", "\\.")
            .replace("$", "\\$")
            .replace("[", "\\[")
            .replace("]", "\\]");
            
        // Allow both simple and fully qualified names
        if (!type.contains(".") && !type.equals("*")) {
            // For simple names like "List", "Map", etc.
            // Allow matching against both simple and fully qualified names
            pattern = "((.*\\.)?" + pattern + ")";
        } else if (type.contains(".")) {
            // For fully qualified names like java.util.List
            // Also match against simple names
            String simpleName = type.substring(type.lastIndexOf('.') + 1);
            pattern = "(" + pattern + "|" + simpleName + ")";
        }
        
        return pattern;
    }
    
    /**
     * Checks if the method's modifiers match the modifier pattern.
     *
     * @param method the method to check
     * @return true if modifiers match or no pattern is specified
     */
    private boolean matchesModifiers(Method method) {
        if (modifierPattern == null) {
            return true;
        }
        
        int modifiers = method.getModifiers();
        return switch (modifierPattern.pattern()) {
            case "public" -> Modifier.isPublic(modifiers);
            case "private" -> Modifier.isPrivate(modifiers);
            case "protected" -> Modifier.isProtected(modifiers);
            default -> true;
        };
    }
    
    /**
     * Checks if the method's return type matches the return type pattern.
     *
     * @param method the method to check
     * @return true if return type matches
     */
    private boolean matchesReturnType(Method method) {
        if (returnTypePattern == null) {
            return true;
        }
        
        Class<?> returnType = method.getReturnType();
        String returnTypeName = returnType.getName();
        String simpleTypeName = returnType.getSimpleName();
        
        // Try matching both fully qualified and simple name
        return returnTypePattern.matcher(returnTypeName).matches() ||
               returnTypePattern.matcher(simpleTypeName).matches();
    }
    
    /**
     * Checks if the target class name matches the class pattern.
     *
     * @param targetClass the class to check
     * @return true if class name matches
     */
    private boolean matchesClassName(Class<?> targetClass) {
        if (classPattern == null) {
            return true;
        }
        
        String className = targetClass.getName();
        
        // Special handling for simple patterns like *Service
        if (classPart != null && classPart.startsWith("*") && !classPart.contains(".")) {
            String suffix = classPart.substring(1);
            return targetClass.getSimpleName().endsWith(suffix);
        }
        
        // Try to match full class name
        return classPattern.matcher(className).matches();
    }
    
    /**
     * Checks if the method name matches the method pattern.
     *
     * @param method the method to check
     * @return true if method name matches
     */
    private boolean matchesMethodName(Method method) {
        if (methodPattern == null) {
            return true;
        }
        
        return methodPattern.matcher(method.getName()).matches();
    }
    
    /**
     * Checks if the method parameters match the parameter pattern.
     *
     * <p>Supports matching of:
     * <ul>
     *   <li>{@code null} pattern (from "..") matches any parameters</li>
     *   <li>Empty pattern matches no parameters</li>
     *   <li>Specific type patterns including generics</li>
     *   <li>Wildcards and ".." for variable arguments</li>
     * </ul>
     *
     * @param method the method to check
     * @return true if parameters match
     */
    private boolean matchesParameters(Method method) {
        if (parameterPattern == null) {
            return true; // (..) matches any parameters
        }

        // Get parameter types including generic information
        Type[] genericParamTypes = method.getGenericParameterTypes();
        Class<?>[] paramTypes = method.getParameterTypes();
        
        // Create parameter string with generic type information
        String paramString = createParameterStringWithGenerics(paramTypes, genericParamTypes);
        
        // Debug logging
        if (!paramString.isEmpty()) {
            io.fairyproject.log.Log.debug("Parameter string for %s: '%s', pattern: '%s'", 
                method.getName(), paramString, parameterPattern.pattern());
        }
        
        return parameterPattern.matcher(paramString).matches();
    }
    
    /**
     * Creates a string representation of parameter types including generic information.
     *
     * @param paramTypes the parameter types
     * @param genericParamTypes the generic parameter types
     * @return string representation of parameters
     */
    private String createParameterStringWithGenerics(Class<?>[] paramTypes, Type[] genericParamTypes) {
        if (paramTypes.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            
            // Get type representation including generics
            String typeStr = getTypeString(paramTypes[i], genericParamTypes[i]);
            sb.append(typeStr);
        }
        return sb.toString();
    }
    
    /**
     * Gets the string representation of a type including generic information.
     *
     * @param rawType the raw class type
     * @param genericType the generic type information
     * @return string representation of the type
     */
    private String getTypeString(Class<?> rawType, Type genericType) {
        if (genericType instanceof ParameterizedType pType) {
            StringBuilder result = new StringBuilder();
            
            // Get the raw type name
            result.append(((Class<?>) pType.getRawType()).getSimpleName());
            
            // Add generic parameters
            Type[] typeArgs = pType.getActualTypeArguments();
            if (typeArgs.length > 0) {
                result.append("<");
                for (int i = 0; i < typeArgs.length; i++) {
                    if (i > 0) {
                        result.append(",");
                    }
                    result.append(getTypeArgumentString(typeArgs[i]));
                }
                result.append(">");
            }
            
            return result.toString();
        } else if (genericType instanceof GenericArrayType arrayType) {
            return getTypeString(rawType.getComponentType(), arrayType.getGenericComponentType()) + "[]";
        } else {
            // Simple type or primitive
            return rawType.getSimpleName();
        }
    }
    
    /**
     * Gets the string representation of a type argument.
     *
     * @param type the type argument
     * @return string representation
     */
    private String getTypeArgumentString(Type type) {
        return switch (type) {
            case Class<?> aClass -> aClass.getSimpleName();
            case ParameterizedType pType -> getTypeString((Class<?>) pType.getRawType(), type);
            case WildcardType ignored -> "?";  // Simplified wildcard representation
            case TypeVariable<?> typeVariable -> typeVariable.getName();
            default -> type.toString();
        };
    }
}
