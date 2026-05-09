package com.interviewiq.ui;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;

/**
 * Shared support for Selenium UI tests.
 *
 * <p>This base class deliberately does NOT extend {@link com.interviewiq.support.AbstractIntegrationTest}.
 * Hosting the React frontend from inside a JUnit test is impractical (no Vite,
 * no SSR), so the tests instead drive the URL configured by
 * {@code interviewiq.frontend.base-url} (default {@code http://localhost:3000})
 * which is expected to be running via {@code docker compose up} in CI.
 *
 * <p>UI tests are gated on the system property {@code ui.e2e=true}, set by the
 * {@code selenium} Maven profile. They are skipped in the default suite.
 */
public final class SeleniumTestSupport {

    private SeleniumTestSupport() { /* utility */ }

    public static String frontendBaseUrl() {
        return System.getProperty(
                "interviewiq.frontend.base-url",
                System.getenv().getOrDefault("INTERVIEWIQ_FRONTEND_BASE_URL",
                        "http://localhost:3000"));
    }

    public static WebDriver newHeadlessChromeDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1280,800");
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        return driver;
    }
}
