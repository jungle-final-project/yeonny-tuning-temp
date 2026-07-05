package com.buildgraph.prototype.part;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 데모 피드는 안전-기본으로 꺼져 있으므로(프로덕션 비노출) 테스트에서 명시적으로 켠다.
@WebMvcTest(ManufacturerReleaseDemoController.class)
@org.springframework.test.context.TestPropertySource(properties = "part.manufacturer-release-intake.demo-feed-enabled=true")
class ManufacturerReleaseDemoControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoManufacturerReleaseFeedReturnsStableRss() throws Exception {
        mockMvc.perform(get("/api/demo/manufacturer-release-feed.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("<rss version=\"2.0\">")))
                .andExpect(content().string(containsString("RTX 5090")))
                .andExpect(content().string(containsString("manufacturer-release-post/rtx-5090-oc-32gb")));
    }
}
