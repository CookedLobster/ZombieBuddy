package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposerDevToolsTest {

    @Exposer.RequiresDevTools
    private static final class DevOnlyApiNotExposed {}

    @Exposer.RequiresDevTools
    private static final class DevOnlyApiExposed {}

    @Exposer.LuaClass
    private static final class NormalApi {}

    @BeforeEach
    @AfterEach
    void clearArguments() {
        Agent.arguments.clear();
    }

    @Test
    void devOnlyClassIsNotExposedWithoutDevtools() {
        Exposer.exposeClass(DevOnlyApiNotExposed.class);
        assertFalse(Exposer.isClassExposed(DevOnlyApiNotExposed.class));
    }

    @Test
    void devOnlyClassIsExposedWithExperimentalAndDevtools() {
        Agent.arguments.put("experimental", "");
        Agent.arguments.put("devtools", "true");
        Exposer.exposeClass(DevOnlyApiExposed.class);
        assertTrue(Exposer.isClassExposed(DevOnlyApiExposed.class));
    }

    @Test
    void normalClassIsExposedWithoutDevtools() {
        Exposer.exposeClass(NormalApi.class);
        assertTrue(Exposer.isClassExposed(NormalApi.class));
    }
}
