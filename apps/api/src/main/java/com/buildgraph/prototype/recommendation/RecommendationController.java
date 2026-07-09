package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecommendationController {
    private final RecommendationService recommendationService;
    private final CurrentUserService currentUserService;

    /* 새롭게 추가됨: 홈 부품 추천 */
    @GetMapping("/recommendations/home-parts")
    Map<String, Object> homeRecommendedParts(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return recommendationService.homeParts(user, limit);
    }

    /* 새롭게 추가됨: 추천 이벤트 저장 */
    @PostMapping("/recommendation-events")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> recordRecommendationEvent(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return recommendationService.recordEvent(request == null ? Map.of() : request, user);
    }
}
