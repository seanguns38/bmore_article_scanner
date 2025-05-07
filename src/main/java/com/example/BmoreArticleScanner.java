package com.example;

// This is a Java-based article scraper built for The Baltimore Banner.
// It uses Selenium to load JavaScript-rendered pages in a headless browser,
// then uses Jsoup to parse and extract article metadata like headline length,
// body word count, posting date, and image count. All results are appended to a CSV.

// --- Required dependencies ---
// Add these to your pom.xml for Selenium and ChromeDriver to function:
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

// --- HTML parsing tools ---
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

// --- ChromeDriver setup ---
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

// --- Wait conditions ---
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

// --- I/O and file handling ---
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

// --- Misc utils ---
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class BmoreArticleScanner {
    // Default URL if no command-line arg is passed
    private static final String SITE_DEFAULT = "https://www.thebaltimorebanner.com";

    public static void main(String[] args) throws IOException {
        // Use command-line URL if provided, else use default site
        String site = args.length > 0 ? args[0] : SITE_DEFAULT;

        // Set up Selenium's ChromeDriver in headless mode
        WebDriverManager.chromedriver().setup(); // auto-downloads compatible Chrome binary
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu"); // no UI browser window
        WebDriver driver = new ChromeDriver(options);

        // Get the sitemap XML and extract all article URLs
        Set<String> seen = new LinkedHashSet<>(); // maintain order and avoid duplicates
        Document sitemap = Jsoup.connect(site + "/arc/outboundfeeds/sitemap?outputType=xml")
                                 .ignoreContentType(true) // fetch non-HTML response
                                 .get();
        Elements locs = sitemap.select("loc"); // select <loc> tags from XML
        for (Element e : locs) {
            String url = e.text().trim(); // extract URL text
            if (url.startsWith(site)) seen.add(url); // only keep internal links
        }
        List<String> urls = new ArrayList<>(seen); // convert to indexed list
        System.out.println("URLs to process: " + urls.size());

        // Prepare the output CSV file (create and write header if needed)
        Path out = Paths.get("articles.csv");
        if (Files.notExists(out)) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
                pw.println("url,headline_words,body_words,post_date,image_count"); // column names
            }
        }

        // Visit each article URL, extract data, and append row to CSV
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            try {
                Data d = scrapeWithSelenium(driver, url); // extract metadata from page
                appendRow(out, url, d); // write to file
                System.out.printf("✓ [%d/%d] %s -> %s%n", i+1, urls.size(), url, d);
            } catch (Exception e) {
                System.err.printf("✗ [%d/%d] %s -> %s%n", i+1, urls.size(), url, e.getMessage());
            }
            // Pause briefly after every 10 URLs to avoid hammering the server
            if ((i+1) % 10 == 0) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }

        // Close the browser when done
        driver.quit();
    }

    // Load the page using Selenium and extract article data
    private static Data scrapeWithSelenium(WebDriver driver, String url) {
        driver.get(url); // navigate to article URL

        // Wait for up to 10 seconds for the article container to be present
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("article, div[data-component*=article], div[class*=article-body]")));
        } catch (Exception e) {
            // continue scraping even if wait times out
        }

        // After page is rendered, get its HTML source
        String rendered = driver.getPageSource();
        Document doc = Jsoup.parse(rendered); // parse HTML with Jsoup

        // Extract headline and count words
        Element h1 = doc.selectFirst("h1");
        String headline = h1 != null ? h1.text().trim() : "";
        int headlineWords = headline.isEmpty() ? 0 : headline.split("\\s+").length;

        // Extract article body text with fallback selectors
        Element bodyEl = doc.selectFirst("article");
        if (bodyEl == null) bodyEl = doc.selectFirst("div[data-component*=article-body]");
        if (bodyEl == null) bodyEl = doc.selectFirst("div[class*=article-body]");
        if (bodyEl == null) {
            throw new RuntimeException("Could not find article body container");
        }
        String bodyText = bodyEl.text().trim();
        int bodyWords = bodyText.isEmpty() ? 0 : bodyText.split("\\s+").length;

        // Try multiple strategies to get the article's publish date
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

        // Count number of images inside the article body
        Elements imgs = bodyEl.select("img");
        int imgCount = imgs.size();

        return new Data(headlineWords, bodyWords, date, imgCount); // return a data record
    }

    // Write a row of data to the CSV file
    private static void appendRow(Path out, String url, Data d) throws IOException {
        String row = String.format("\"%s\",%d,%d,%s,%d",
                                   url, d.headlineWords, d.bodyWords, d.postDate, d.imageCount);
        try (BufferedWriter bw = Files.newBufferedWriter(out, StandardOpenOption.APPEND)) {
            bw.write(row);
            bw.newLine();
        }
    }

    // Simple container class to hold article stats
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
