package me.zed_0xff.zombie_buddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

public class Exposer {

    /**
     * Marker annotation for classes that should be exposed to Lua.
     *
     * Usage:
     *   @Exposer.LuaClass
     *   public class MyApi { ... }  // Accessible as MyApi
     *
     *   @Exposer.LuaClass(name = "ZombieBuddy.Utils")
     *   public class Utils { ... }  // Accessible as ZombieBuddy.Utils
     *
     *   @Exposer.LuaClass(name = "ZB.API.Logger")
     *   public class MyLogger { ... }  // Accessible as ZB.API.Logger (nested tables)
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LuaClass {
        /** Optional Lua name. Dots create nested tables. Default: class simple name. */
        String name() default "";
    }

    /**
     * Marks a class whose Lua API is a development-only escape hatch (reflection, arbitrary
     * method invocation, etc.). It is only exposed when both {@code experimental} and
     * {@code devtools=true} are set.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface RequiresDevTools {}

    // Class -> Lua name (may contain dots for nesting, empty = use simple name)
    private static final HashMap<Class<?>, String> g_exposed_classes = new HashMap<>();
    private static final HashMap<Class<?>, HashSet<String>> g_exposed_methods = new HashMap<>();
    private static final HashSet<Class<?>> g_classesWithGlobalLuaMethod = new HashSet<>();

    static {
        Callbacks.afterExposeAll.register(Exposer::afterExposeAll);
    }

    static boolean hasGlobalLuaMethod(Class<?> cls) {
        for (Method m : Reflect.on(cls).declaredMethods()) {
            LuaMethod ann = m.getAnnotation(LuaMethod.class);
            if (ann != null && ann.global()) {
                return true;
            }
        }
        return false;
    }

    static void addClassWithGlobalLuaMethod(Class<?> cls) {
        if (cls != null && hasGlobalLuaMethod(cls)) {
            g_classesWithGlobalLuaMethod.add(cls);
        }
    }

    static List<Class<?>> getClassesWithGlobalLuaMethod() {
        return new ArrayList<>(g_classesWithGlobalLuaMethod);
    }

    @Deprecated(since = "2026-05-01", forRemoval = true)
    public static void exposeClassToLua(Class<?> cls) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        exposeClass(cls, null);
    }

    @Deprecated(since = "2026-05-01", forRemoval = true)
    public static boolean exposeClassToLua(String className) {
        Logger.warn("exposeClassToLua() method is deprecated, use exposeClass() or @LuaClass annotation instead");
        return exposeClass(className);
    }

    public static void exposeClass(Class<?> cls) {
        LuaClass ann = cls.getAnnotation(LuaClass.class);
        exposeClass(cls, ann != null ? ann.name() : null);
    }

    public static void exposeClass(Class<?> cls, String name) {
        if (cls == null) {
            return;
        }
        if (requiresDevTools(cls) && !Agent.isDevToolsEnabled()) {
            Logger.warn("Not exposing development-only Lua API " + cls.getName()
                + ": requires experimental=true and devtools=true");
            return;
        }
        if (g_exposed_classes.containsKey(cls)) {
            return;
        }
        g_exposed_classes.put(cls, name != null ? name : "");

        // trigger class loading, to ensure class static initializers run
        try {
            ClassLoader loader = cls.getClassLoader() != null ? cls.getClassLoader() : ClassLoader.getSystemClassLoader();
            Class.forName(cls.getName(), true, loader);
        } catch (ClassNotFoundException e) {
            Logger.error("exposeClass: Failed to load class " + cls.getName() + ": " + e.getMessage());
        }

        // If exposer is already available, expose immediately (for mods loaded after initial exposure)
        if (LuaManager.exposer != null && LuaManager.env != null) {
            exposeClassNow(cls);
        }
    }

    private static void exposeClassNow(Class<?> cls) {
        var exposer = LuaManager.exposer;
        var env = LuaManager.env;
        String name = g_exposed_classes.get(cls);
        String simpleName = cls.getSimpleName();

        if (Utils.isBlank(name)) {
            Logger.info("Exposing class to Lua: " + simpleName);
            exposer.setExposed(cls);
            exposer.exposeLikeJavaRecursively(cls, env);
        } else {
            Logger.info("Exposing class to Lua: " + simpleName + " as " + name);
            var staticBase = getOrCreateParentTable(env, name);
            if (staticBase == null) {
                Logger.error("Failed to create parent table for " + name);
                return;
            }
            exposer.setExposed(cls);
            exposer.exposeLikeJavaRecursively(cls, staticBase);
            if (!name.endsWith("." + simpleName)) {
                String newSimpleName = leafName(name);
                staticBase.rawset(newSimpleName, staticBase.rawget(simpleName));
                staticBase.rawset(simpleName, null);
            }
        }
    }

    public static boolean exposeClass(String className) {
        Class<?> cls = Reflect.on(className).getType();
        if (cls == null) {
            Logger.warn("exposeClass(\"" + className + "\"): class not found");
            return false;
        }
        exposeClass(cls);
        return true;
    }

    public static void exposeMethod(Class<?> cls, String methodName) {
        if (cls == null || Utils.isBlank(methodName)) {
            Logger.error("exposeMethod(): cls and methodName must be /non-empty:", cls, methodName);
            return;
        }
        g_exposed_methods.computeIfAbsent(cls, k -> new HashSet<>()).add(methodName);
        // If exposer is already available, expose immediately (for mods loaded after initial exposure)
        if (LuaManager.exposer != null && LuaManager.env != null) {
            exposeMethodNow(cls, methodName);
        }
    }

    public static void exposeMethod(String className, String methodName) {
        if (Utils.isBlank(className) || Utils.isBlank(methodName)) {
            Logger.error("exposeMethod(): className and methodName must be non-empty:", className, methodName);
            return;
        }
        Class<?> cls = Reflect.on(className).getType();
        if (cls == null) {
            Logger.warn("exposeMethod(\"" + className + "\", \"" + methodName + "\"): class not found");
            return;
        }
        exposeMethod(cls, methodName);
    }

    public static List<Class<?>> getExposedClasses() {
        return new ArrayList<>(g_exposed_classes.keySet());
    }

    public static boolean isClassExposed(Class<?> cls) {
        return g_exposed_classes.containsKey(cls);
    }

    /** True when the class, or any enclosing class, is marked development-only. */
    static boolean requiresDevTools(Class<?> cls) {
        for (Class<?> current = cls; current != null; current = current.getEnclosingClass()) {
            if (current.isAnnotationPresent(RequiresDevTools.class)) {
                return true;
            }
        }
        return false;
    }

    public static void afterExposeAll() {
        var exposer = LuaManager.exposer;
        if (exposer == null) {
            Logger.error("LuaManager.exposer is null!");
            return;
        }
        var staticBase = LuaManager.env;
        if (staticBase == null) {
            Logger.error("LuaManager.env is null!");
            return;
        }

        // expose non-renamed classes first
        for (var entry : g_exposed_classes.entrySet()) {
            Class<?> cls = entry.getKey();
            String name = entry.getValue();
            if (Utils.isBlank(name)) {
                exposeClassNow(cls);
            }
        }

        // expose renamed classes second
        for (var entry : g_exposed_classes.entrySet()) {
            Class<?> cls = entry.getKey();
            String name = entry.getValue();
            if (!Utils.isBlank(name)) {
                exposeClassNow(cls);
            }
        }

        // Expose global functions
        for (Class<?> cls : g_classesWithGlobalLuaMethod) {
            Object instance;
            try {
                instance = newInstanceForGlobalLuaMethods(cls);
            } catch (ReflectiveOperationException e) {
                Logger.error("Cannot expose global Lua functions from " + cls.getName() + ": " + e.getMessage());
                continue;
            }
            try {
                Logger.info("Exposing global functions from class: " + cls.getName());
                exposer.exposeGlobalFunctions(instance);
            } catch (Throwable t) {
                Logger.error("exposeGlobalFunctions(" + cls.getName() + "): " + t.getMessage());
            }
        }

        // Expose individual methods (for @HiddenFromLua overrides)
        for (var entry : g_exposed_methods.entrySet()) {
            Class<?> cls = entry.getKey();
            for (String methodName : entry.getValue()) {
                exposeMethodNow(cls, methodName);
            }
        }
    }

    private static void exposeMethodNow(Class<?> cls, String methodName) {
        var exposer    = LuaManager.exposer;
        var staticBase = LuaManager.env;

        for (var method : Reflect.on(cls).declaredMethods()) {
            if (method.getName().equals(methodName)) {
                try {
                    if (isStatic(method)) {
                        Logger.info("Exposing static method " + cls.getName() + "." + methodName + "()");
                        KahluaTable container = (KahluaTable) staticBase.rawget(cls.getSimpleName());
                        if (container == null) {
                            // replicate LuaJavaClassExposer.exposeStatics() logic
                            String[] packageStructure = cls.getName().replaceAll("\\$", ".").split("\\.");
                            // private KahluaTable createTableStructure(KahluaTable base, String[] structure)
                            container = (KahluaTable) Reflect
                                .fastcall(() -> Reflect.on(exposer).getMethodHandle(KahluaTable.class, new Class<?>[] {KahluaTable.class, String[].class}, "createTableStructure"))
                                .invoke(exposer, staticBase, packageStructure);

                            if (container == null) {
                                Logger.error("Failed to create table structure for static method exposure of " + cls.getName());
                                continue;
                            }
                        }
                        exposer.exposeGlobalClassFunction(container, cls, method, method.getName());
                    } else {
                        Logger.info("Exposing method " + cls.getName() + "." + methodName + "()");
                        exposer.exposeMethod(cls, method, method.getName(), staticBase);
                    }
                } catch (Throwable t) {
                    Logger.error("error exposing method", t);
                }
            }
        }
    }

    private static boolean isStatic(Member member) {
        return Modifier.isStatic(member.getModifiers());
    }

    private static KahluaTable getOrCreateParentTable(KahluaTable root, String path) {
        if (root == null || path == null) return null;

        String[] parts = path.split("\\.");
        KahluaTable current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.rawget(parts[i]);
            if (next == null) {
                next = LuaManager.platform.newTable();
                current.rawset(parts[i], next);
            }
            if (next instanceof KahluaTable) {
                current = (KahluaTable) next;
            } else {
                Logger.error("Cannot create nested table at " + parts[i] + " - already exists as non-table");
                return null;
            }
        }

        return current;
    }

    private static String leafName(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private static Object newInstanceForGlobalLuaMethods(Class<?> cls) throws ReflectiveOperationException {
        if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) {
            throw new ReflectiveOperationException("class must be concrete");
        }
        var ctor = cls.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    static void exposeAnnotatedClasses(String packageName) {
        Path jarPath = Utils.getCurrentJarPath();
        if (jarPath == null) {
            Logger.error("exposeAnnotatedClasses: current jar path unknown");
            return;
        }

        String pkgPath = packageName.replace('.', '/') + '/';
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
                JarEntry entry = en.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || !name.startsWith(pkgPath) || entry.isDirectory()) {
                    continue;
                }

                String className = name.substring(0, name.length() - 6).replace('/', '.');
                exposeClassIfAnnotated(className, packageName, Exposer.class.getClassLoader());
            }
        } catch (Exception e) {
            Logger.error("exposeAnnotatedClasses: " + e.getMessage());
            Logger.printStackTrace(e);
        }
    }

    static void exposeClassesFromPackage(ClassLoader loader, Collection<String> classNames, String packageName) {
        for (String className : classNames) {
            if (!className.startsWith(packageName + ".")) {
                continue;
            }

            exposeClassIfAnnotated(className, packageName, loader);
        }
    }

    private static void exposeClassIfAnnotated(String className, String packageName, ClassLoader loader) {
        try {
            Class<?> cls = loader.loadClass(className);
            String clsPackage = cls.getPackage() != null ? cls.getPackage().getName() : "";
            if (!clsPackage.equals(packageName)) {
                return;
            }

            if (requiresDevTools(cls) && !Agent.isDevToolsEnabled()) {
                Logger.warn("Not exposing development-only Lua API " + cls.getName()
                    + ": requires experimental=true and devtools=true");
                return;
            }

            LuaClass ann = cls.getAnnotation(LuaClass.class);
            if (ann != null) {
                exposeClass(cls, ann.name());
            }
            if (hasGlobalLuaMethod(cls)) {
                addClassWithGlobalLuaMethod(cls);
            }
        } catch (Throwable t) {
            Logger.debug("exposeClassIfAnnotated(" + className + "): " + t.getMessage());
        }
    }
}
