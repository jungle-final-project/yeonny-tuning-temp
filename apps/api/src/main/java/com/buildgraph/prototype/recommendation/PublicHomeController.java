package com.buildgraph.prototype.recommendation;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicHomeController {
    private final PublicHomeService publicHomeService;

    public PublicHomeController(PublicHomeService publicHomeService) {
        this.publicHomeService = publicHomeService;
    }

    @GetMapping("/home")
    Map<String, Object> home() {
        return publicHomeService.home();
    }
}