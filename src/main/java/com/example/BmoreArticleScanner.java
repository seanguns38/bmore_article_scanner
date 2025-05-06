package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

public class BmoreArticleScanner {

    public static void main(String[] args) throws IOException {
        String baseUrl = "https://www.thebaltimorebanner.com";
        Document home = Jsoup.connect(baseUrl).get();
        // find article links (adjust selector if needed)
        Elements links = home.select("a[href*=/story/]");
        for (Element link : links) {
            String url = link.absUrl("href");
            processArticle(url);
        }
    }

    private static void processArticle(String url) {
        try {
            Document doc = Jsoup.connect(url).get();

            // 1. Headline & its word count
            String headline = doc.selectFirst("h1").text();
            int headlineWords = headline.split("\\s+").length;

            // 2. Body text & total word count
            Element article = doc.selectFirst("article");
            String bodyText = article.text();
            int bodyWords = bodyText.split("\\s+").length;

            // 3. Date posted (from <time datetime="…">)
            String date = doc.selectFirst("time").attr("datetime");

            // 4. Images inside the article
            Elements imgs = article.select("img");
            int imgCount = imgs.size();

            System.out.printf(
              "URL: %s%nHeadline: %s (%d words)%nDate: %s%nBody: %d words%nImages: %d%n%n",
              url, headline, headlineWords, date, bodyWords, imgCount
            );
        } catch (Exception e) {
            System.err.println("Failed: " + url + " → " + e.getMessage());
        }
    }
}
