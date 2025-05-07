package com.example;

// NOTE: Add these dependencies to your pom.xml:
//
// <dependency>
//   <groupId>org.seleniumhq.selenium</groupId>
//   <artifactId>selenium-java</artifactId>
//   <version>4.14.0</version>
// </dependency>
// <dependency>
//   <groupId>io.github.bonigarcia</groupId>
//   <artifactId>webdrivermanager</artifactId>
//   <version>5.4.0</version>
// </dependency>

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class BmoreArticleScanner {
    private static final String SITE_DEFAULT = "https://www.thebaltimorebanner.com";

    public static void main(String[] args) throws IOException {
        String site = args.length > 0 ? args[0] : SITE_DEFAULT;

        // Setup headless Chrome via WebDriverManager
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu");
        WebDriver driver = new ChromeDriver(options);

        // Collect URLs via sitemap
        Set<String> seen = new LinkedHashSet<>();
        Document sitemap = Jsoup.connect(site + "/arc/outboundfeeds/sitemap?outputType=xml")
                                 .ignoreContentType(true)
                                 .get();
        Elements locs = sitemap.select("loc");
        for (Element e : locs) {
            String url = e.text().trim();
            if (url.startsWith(site)) seen.add(url);
        }
        List<String> urls = new ArrayList<>(seen);
        System.out.println("URLs to process: " + urls.size());

        // Prepare CSV header
        Path out = Paths.get("articles.csv");
        if (Files.notExists(out)) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
                pw.println("url,headline_words,body_words,post_date,image_count");
            }
        }

        // Process each URL with Selenium-rendered HTML
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                Data d = scrapeWithSelenium(driver, url);
                appendRow(out, url, d);
                System.out.printf("✓ [%d/%d] %s -> %s%n", i+1, urls.size(), url, d);
            } catch (Exception e) {
                System.err.printf("✗ [%d/%d] %s -> %s%n", i+1, urls.size(), url, e.getMessage());
            }
            // batch throttle every 10
            if ((i+1) % 10 == 0) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        driver.quit();
    }

    private static Data scrapeWithSelenium(WebDriver driver, String url) {
        // Load page
        driver.get(url);
        // Wait up to 10s for article container
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("article, div[data-component*=article], div[class*=article-body]")));
        } catch (Exception e) {
            // continue even if timeout
        }
        // Get rendered HTML
        String rendered = driver.getPageSource();
        Document doc = Jsoup.parse(rendered);

        // Headline
        Element h1 = doc.selectFirst("h1");
        String headline = h1 != null ? h1.text().trim() : "";
        int headlineWords = headline.isEmpty() ? 0 : headline.split("\\s+").length;

        // Article body
        Element bodyEl = doc.selectFirst("article");
        if (bodyEl == null) bodyEl = doc.selectFirst("div[data-component*=article-body]");
        if (bodyEl == null) bodyEl = doc.selectFirst("div[class*=article-body]");
        if (bodyEl == null) {
            throw new RuntimeException("Could not find article body container");
        }
        String bodyText = bodyEl.text().trim();
        int bodyWords = bodyText.isEmpty() ? 0 : bodyText.split("\\s+").length;

        // Date posted (multiple fallbacks)
        String date = "";
        Element timeEl = doc.selectFirst("time[datetime]");
        if (timeEl != null && !timeEl.attr("datetime").isEmpty()) {
            date = timeEl.attr("datetime");
        } else if (timeEl != null) {
            date = timeEl.text().trim();
        } else {
            Element metaPub = doc.selectFirst("meta[property=article:published_time]");
            if (metaPub != null) {
                date = metaPub.attr("content");
            } else {
                Element time2 = doc.selectFirst("time");
                if (time2 != null) {
                    date = time2.text().trim();
                }
            }
        }

        // Images inside bodyEl
        Elements imgs = bodyEl.select("img");
        int imgCount = imgs.size();

        return new Data(headlineWords, bodyWords, date, imgCount);
    }

    private static void appendRow(Path out, String url, Data d) throws IOException {
        String row = String.format("\"%s\",%d,%d,%s,%d",
                                   url, d.headlineWords, d.bodyWords, d.postDate, d.imageCount);
        try (BufferedWriter bw = Files.newBufferedWriter(out, StandardOpenOption.APPEND)) {
            bw.write(row);
            bw.newLine();
        }
    }

    private static class Data {
        final int headlineWords, bodyWords, imageCount;
        final String postDate;
        Data(int h, int b, String d, int i) {
            headlineWords = h;
            bodyWords = b;
            postDate = d;
            imageCount = i;
        }
        @Override public String toString() {
            return String.format("%dw/%dw/%s/%dimg",
                                 headlineWords, bodyWords, postDate, imageCount);
        }
    }
}
