package com.interviewiq.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.WebDriver;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ui.e2e", matches = "true")
class DashboardSmokeUITest {

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
    void rootUrl_returnsHtml() {
        driver.get(SeleniumTestSupport.frontendBaseUrl());
        assertThat(driver.getPageSource()).isNotBlank();
        assertThat(driver.getTitle()).isNotNull();
    }
}
