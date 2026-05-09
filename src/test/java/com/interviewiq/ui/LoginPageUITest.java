package com.interviewiq.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.WebDriver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless Chrome smoke test of the login page.
 *
 * <p>Skipped unless {@code -Pselenium} (sets {@code ui.e2e=true}) AND a frontend
 * is reachable at {@code interviewiq.frontend.base-url}. The CI workflow brings
 * up the full stack via docker-compose before invoking this profile.
 */
@EnabledIfSystemProperty(named = "ui.e2e", matches = "true")
class LoginPageUITest {

    private WebDriver driver;

    @BeforeEach
    void setUp() {
        driver = SeleniumTestSupport.newHeadlessChromeDriver();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
    }

    @Test
    void loginPage_loads_andHasEmailField() {
        driver.get(SeleniumTestSupport.frontendBaseUrl() + "/login");
        assertThat(driver.getPageSource()).containsIgnoringCase("email");
    }
}
