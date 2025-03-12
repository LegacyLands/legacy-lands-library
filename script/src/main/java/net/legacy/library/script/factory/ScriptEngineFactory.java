package net.legacy.library.script.factory;

import lombok.experimental.UtilityClass;
import net.legacy.library.script.engine.NashornScriptEngine;
import net.legacy.library.script.engine.ScriptEngineInterface;

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
}
