package me.zed_0xff.zombie_buddy.frontend;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TinyfdModApprovalFrontendTest {

    @Test
    void missingTinyFileDialogsFailsClosed() {
        // The unit-test classpath has no TinyFileDialogs classes; the approval
        // dialog cannot be shown and the result must be null (caller denies).
        assertNull(TinyfdModApprovalFrontend.tinyfdYesNo("test"));
    }
}
