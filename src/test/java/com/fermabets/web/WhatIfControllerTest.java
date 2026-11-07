package com.fermabets.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhatIfControllerTest {

    @Test
    void blankOrMissingFieldStaysNull() {
        assertNull(WhatIfController.num(null));
        assertNull(WhatIfController.num(""));
        assertNull(WhatIfController.num("  "));
        assertEquals(32.5, WhatIfController.num("32,5"));
        assertEquals(18.0, WhatIfController.num("18"));
    }
}
