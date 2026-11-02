package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDevModeTest {

    @BeforeEach
    @AfterEach
    void clearArguments() {
        Agent.arguments.clear();
    }

    @Test
    void devModeIsOffByDefault() {
        assertFalse(Agent.isDevMode());
        assertFalse(Agent.isDevToolsEnabled());
    }

    @Test
    void devModeRequiresExplicitTrue() {
        Agent.arguments.put("dev", "true");
        assertTrue(Agent.isDevMode());

        Agent.arguments.put("dev", "false");
        assertFalse(Agent.isDevMode());

        Agent.arguments.put("dev", "");
        assertFalse(Agent.isDevMode());
    }

    @Test
    void devToolsRequiresExperimentalAndExplicitTrue() {
        Agent.arguments.put("devtools", "true");
        assertFalse(Agent.isDevToolsEnabled());

        Agent.arguments.put("experimental", "");
        assertTrue(Agent.isExperimental());
        assertTrue(Agent.isDevToolsEnabled());

        Agent.arguments.put("devtools", "false");
        assertFalse(Agent.isDevToolsEnabled());
    }

    @Test
    void experimentalFalseDisablesExperimentalFeatures() {
        Agent.arguments.put("experimental", "false");
        assertFalse(Agent.isExperimental());

        Agent.arguments.put("experimental", "0");
        assertFalse(Agent.isExperimental());

        Agent.arguments.put("experimental", "no");
        assertFalse(Agent.isExperimental());

        Agent.arguments.put("experimental", "true");
        assertTrue(Agent.isExperimental());
    }
}
