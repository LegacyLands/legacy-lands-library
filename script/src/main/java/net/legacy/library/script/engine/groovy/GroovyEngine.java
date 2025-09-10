package net.legacy.library.script.engine.groovy;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import lombok.Getter;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.scope.ScriptScope;
import net.legacy.library.script.scope.groovy.GroovyScriptScope;
import org.codehaus.groovy.control.CompilerConfiguration;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groovy script engine implementation.
 *
 * <p>The engine uses Groovy's {@link GroovyShell} and {@link Binding} for script execution
 * and variable management. Closures are automatically detected and invoked using the
 * {@link Closure#call(Object...)} method.
 *
 * @author qwq-dev
 * @since 2025-09-11 03:41
 */
@Getter
public class GroovyEngine implements ScriptEngineInterface {

    private final CompilerConfiguration compilerConfig;
    private final Binding globalBinding;
    private final GroovyShell globalShell;
    private final GroovyClassLoader classLoader;
    private final Map<String, Class<?>> compiledScriptCache = new ConcurrentHashMap<>();

    public GroovyEngine() {
        this(new CompilerConfiguration());
    }

    public GroovyEngine(CompilerConfiguration config) {
        this.compilerConfig = config;
        this.globalBinding = new Binding();
        this.globalShell = new GroovyShell(globalBinding, compilerConfig);
        this.classLoader = new GroovyClassLoader(this.getClass().getClassLoader(), compilerConfig);
    }

    /**
     * {@inheritDoc}
     *
     * @param script      {@inheritDoc}
     * @param scriptScope {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object execute(String script, ScriptScope scriptScope) throws ScriptException {
        try {
            if (script == null) {
                throw new IllegalArgumentException("Script cannot be null");
            }

            Binding targetBinding = getGroovyBinding(scriptScope);
            GroovyShell currentShell = createShellForBinding(targetBinding);

            return currentShell.evaluate(script);
        } catch (Exception exception) {
            throw wrapGroovyException("Failed to execute script", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script       {@inheritDoc}
     * @param functionName {@inheritDoc}
     * @param scriptScope  {@inheritDoc}
     * @param args         {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeFunction(String script, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        try {
            Binding targetBinding = getGroovyBinding(scriptScope);
            GroovyShell currentShell = createShellForBinding(targetBinding);

            return (script == null || script.isEmpty()) ?
                    invokeBindingClosure(functionName, targetBinding, currentShell, args) :
                    invokeScriptFunction(
                            createScriptInstance(classLoader.parseClass(script), targetBinding), functionName, args
                    );
        } catch (Exception exception) {
            throw wrapGroovyException("Failed to invoke function '" + functionName + "'", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object compile(String script) throws ScriptException {
        try {
            String cacheKey = generateCacheKey(script);
            return compiledScriptCache.computeIfAbsent(cacheKey, k -> classLoader.parseClass(script));
        } catch (Exception exception) {
            throw wrapGroovyException("Failed to compile script", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param compiledScript {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object executeCompiled(Object compiledScript, ScriptScope scriptScope) throws ScriptException {
        Class<?> scriptClass = validateCompiledScript(compiledScript);

        try {
            return createScriptInstance(scriptClass, getGroovyBinding(scriptScope)).run();
        } catch (Exception exception) {
            throw wrapGroovyException("Failed to execute compiled script", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param compiledScript {@inheritDoc}
     * @param functionName   {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeCompiledFunction(Object compiledScript, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        Binding targetBinding = getGroovyBinding(scriptScope);
        GroovyShell currentShell = createShellForBinding(targetBinding);

        try {
            return compiledScript == null ?
                    invokeBindingClosure(functionName, targetBinding, currentShell, args) :
                    invokeScriptFunction(
                            createScriptInstance(validateCompiledScript(compiledScript), targetBinding),
                            functionName, args
                    );
        } catch (Exception exception) {
            throw wrapGroovyException("Failed to invoke function '" + functionName + "'", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Object getGlobalVariable(String name) {
        try {
            return globalBinding.getVariable(name);
        } catch (MissingPropertyException ignored) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     * @param value {@inheritDoc}
     */
    @Override
    public void setGlobalVariable(String name, Object value) {
        globalBinding.setVariable(name, value);
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     */
    @Override
    public void removeGlobalVariable(String name) {
        try {
            globalBinding.removeVariable(name);
        } catch (Exception ignored) {
            globalBinding.getVariables().remove(name);
        }
    }

    private Object invokeScriptFunction(Script scriptObject, String functionName, Object... args) {
        scriptObject.run();
        return scriptObject.invokeMethod(functionName, args);
    }

    private Object invokeBindingClosure(String functionName, Binding binding, GroovyShell currentShell, Object... args) {
        try {
            Object func = getVariableQuietly(binding, functionName);

            if (func instanceof Closure<?> closure) {
                return closure.call(args);
            }

            return currentShell.evaluate(buildFunctionCallScript(functionName, args));
        } catch (Exception exception) {
            throw new RuntimeException("Failed to invoke function '" + functionName + "': " + exception.getMessage(), exception);
        }
    }

    private Object getVariableQuietly(Binding binding, String variableName) {
        try {
            return binding.getVariable(variableName);
        } catch (MissingPropertyException ignored) {
            return null;
        }
    }

    private String buildFunctionCallScript(String functionName, Object... args) {
        if (args.length == 0) {
            return functionName + "()";
        }

        StringBuilder script = new StringBuilder(functionName).append("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                script.append(", ");
            }
            script.append(formatArgument(args[i]));
        }
        return script.append(")").toString();
    }

    private String formatArgument(Object arg) {
        return arg instanceof String ?
                "'" + arg.toString().replace("'", "\\'") + "'" :
                String.valueOf(arg);
    }

    private Class<?> validateCompiledScript(Object compiledScript) throws ScriptException {
        if (!(compiledScript instanceof Class<?> scriptClass)) {
            throw new ScriptException("Invalid compiled script object. Must be a Class<?> instance.");
        }
        return scriptClass;
    }

    private GroovyShell createShellForBinding(Binding binding) {
        return binding == globalBinding ? globalShell : new GroovyShell(binding, compilerConfig);
    }

    private String generateCacheKey(String script) {
        return String.valueOf(script.hashCode());
    }

    private Binding getGroovyBinding(ScriptScope scriptScope) {
        if (scriptScope == null) {
            return globalBinding;
        }

        if (scriptScope instanceof GroovyScriptScope groovyScope) {
            return groovyScope.getBinding();
        } else {
            throw new IllegalArgumentException("Unsupported ScriptScope implementation. Please use GroovyScriptScope.");
        }
    }

    private ScriptException wrapGroovyException(String message, Throwable cause) {
        return switch (cause) {
            case MissingMethodException mme -> new ScriptException(message + ": " + cause.getMessage(), cause);
            case MissingPropertyException mpe -> new ScriptException(message + ": " + cause.getMessage(), cause);
            default -> new ScriptException(message, cause);
        };
    }

    private Script createScriptInstance(Class<?> scriptClass, Binding binding) throws Exception {
        Constructor<?> constructor = scriptClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Script scriptObject = (Script) constructor.newInstance();
        scriptObject.setBinding(binding);
        return scriptObject;
    }

}