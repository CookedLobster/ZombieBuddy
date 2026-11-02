package me.zed_0xff.zombie_buddy.patches.experimental;

import java.nio.file.Files;
import java.nio.file.Path;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;
import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.Lua.LuaManager;

public class Patch_LuaManager {
    public static boolean isInitialized = false;

    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public class Patch_init {
        @Patch.OnEnter
        public static void enter() {
            Callbacks.beforeLuaInit.run();
        }

        @Patch.OnExit
        public static void exit() {
            Callbacks.afterLuaInit.run();
            if (!isInitialized) {
                isInitialized = true;
                if (Agent.arguments.containsKey("lua_init_script")) {
                    runInitScript(Agent.arguments.get("lua_init_script"));
                }
            }
        }
    }

    private static void runInitScript(String initScript) {
        if (!Agent.isDevMode()) {
            Logger.warn("Ignoring lua_init_script: running an arbitrary Lua file at startup requires "
                + "the development-only argument dev=true.");
            return;
        }
        Path script = resolveAllowedScript(initScript);
        if (script == null) {
            Logger.error("Refusing lua_init_script outside the game/cache directory: " + initScript);
            return;
        }
        Logger.warn("DEVELOPMENT MODE: running lua_init_script " + script);
        // Use the resolved real path, not the raw argument, so the file that
        // was containment-checked is the file that gets executed.
        LuaManager.RunLua(script.toString());
    }

    /**
     * Restricts {@code lua_init_script} to an existing file inside the game working directory or
     * the Project Zomboid cache directory. Prevents a launch option from pointing the agent at an
     * arbitrary file elsewhere on the machine.
     */
    static Path resolveAllowedScript(String rawPath) {
        if (Utils.isBlank(rawPath)) {
            return null;
        }
        try {
            Path script = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) {
                return null;
            }
            Path realScript = script.toRealPath();
            Path workDir = Path.of("").toAbsolutePath().normalize();
            Path cachePath = Utils.getCachePath();
            if (isInside(realScript, workDir) || (cachePath != null && isInside(realScript, cachePath))) {
                return realScript;
            }
        } catch (Exception e) {
            Logger.warn("Invalid lua_init_script path '" + rawPath + "': " + e.getMessage());
        }
        return null;
    }

    private static boolean isInside(Path file, Path root) {
        try {
            Path realRoot = root.toRealPath();
            return file.startsWith(realRoot);
        } catch (Exception e) {
            return file.startsWith(root.toAbsolutePath().normalize());
        }
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "LoadDirBase")
    public class Patch_LoadDirBase {
        @Patch.OnEnter
        public static void enter(String sub, @Patch.Local("t0") long t0) {
            Logger.info("LuaManager.LoadDirBase(\"" + sub + "\") ...");
            t0 = System.nanoTime();
        }

        @Patch.OnExit
        public static void exit(String sub, @Patch.Local("t0") long t0) {
            long elapsedMS = (System.nanoTime() - t0) / 1_000_000L;
            if ( elapsedMS > 1000 ) Logger.info("LuaManager.LoadDirBase(\"" + sub + "\") took " + elapsedMS + " ms");
        }
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "RunLua")
    public class Patch_RunLua {
        @Patch.OnEnter
        public static void enter(String filename, @Patch.Local("t0") long t0) {
            // Logger.info("LuaManager.RunLua(\"" + filename + "\") ...");
            t0 = System.nanoTime();
        }

        @Patch.OnExit
        public static void exit(String filename, @Patch.Local("t0") long t0) {
            long elapsedMS = (System.nanoTime() - t0) / 1_000_000L;
            if ( elapsedMS > 1000 ) Logger.info("LuaManager.RunLua(\"" + filename + "\") took " + elapsedMS + " ms");
        }
    }
}
