package com.buildgraph.prototype.part;

import java.time.OffsetDateTime;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class ManufacturerReleaseDemoController {
    @GetMapping(value = "/manufacturer-release-feed.xml", produces = MediaType.APPLICATION_XML_VALUE)
    String manufacturerReleaseFeed() {
        String publishedAt = OffsetDateTime.parse("2026-07-01T09:00:00+09:00").toString();
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0">
                  <channel>
                    <title>BuildGraph Demo Manufacturer Releases</title>
                    <link>http://localhost:8080/api/demo/manufacturer-release-feed.xml</link>
                    <description>Local demo feed for manufacturer release intake.</description>
                    <item>
                      <title>ASUS launches ROG Astral GeForce RTX 5090 OC 32GB graphics card</title>
                      <link>http://localhost:8080/api/demo/manufacturer-release-post/rtx-5090-oc-32gb</link>
                      <pubDate>%s</pubDate>
                      <description>Official demo release note for a new GeForce RTX 5090 GPU candidate. Designed to show scan, product classification, Naver candidate search, and admin approval flow.</description>
                    </item>
                  </channel>
                </rss>
                """.formatted(publishedAt);
    }
}
