package net.legacy.library.script.factory;

import lombok.experimental.UtilityClass;
import net.legacy.library.script.engine.NashornScriptEngine;
import net.legacy.library.script.engine.RhinoScriptEngine;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.engine.V8ScriptEngine;

/**
 * Factory for creating script engine instances.
 *
 * @author qwq-dev
 * @since 2025-03-12 20:34
 */
@UtilityClass
public class ScriptEngineFactory {
    /**
     * Creates a {@link NashornScriptEngine}.
     *
     * @return a new {@link NashornScriptEngine} instance
     * @see NashornScriptEngine
     */
    public static ScriptEngineInterface createNashornScriptEngine() {
        return new NashornScriptEngine();
    }

    /**
     * Creates a {@link RhinoScriptEngine}.
     *
     * @return a new {@link RhinoScriptEngine} instance
     * @see RhinoScriptEngine
     */
    public static ScriptEngineInterface createRhinoScriptEngine() {
        return new RhinoScriptEngine();
    }

    /**
     * Creates a {@link V8ScriptEngine}.
     *
     * @return a new {@link V8ScriptEngine} instance
     * @see V8ScriptEngine
     */
    public static ScriptEngineInterface createV8Engine() {
        return new V8ScriptEngine();
    }
}
