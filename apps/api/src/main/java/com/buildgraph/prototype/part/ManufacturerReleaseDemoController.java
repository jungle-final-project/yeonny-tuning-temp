package com.buildgraph.prototype.part;

import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 인증 없는 데모 피드가 프로덕션에도 무조건 배포·공개되던 것을 안전-기본으로 격리한다(감사 보안).
// 기본 false — 로컬/데모 스택(compose)에서만 env로 켠다.
@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "part.manufacturer-release-intake", name = "demo-feed-enabled", havingValue = "true")
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
