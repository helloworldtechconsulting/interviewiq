package com.interviewiq.ui;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "ui.e2e", matches = "true")
@Disabled("TODO: implement once login flow + Jobs page selectors are stable")
class JobCreateFlowUITest {

    @Test
    void loginNavigateCreateJob_seesNewRow() {
        // 1. Open base url, sign in.
        // 2. Navigate to /jobs.
        // 3. Click "Create Job", fill the form, submit.
        // 4. Assert the new row title appears in the list.
    }
}
