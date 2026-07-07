package com.buildgraph.prototype;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @EnableScheduling은 com.buildgraph.prototype.config.SchedulingConfig로 이동했다
// (buildgraph.scheduling.enabled로 web-facing/worker 태스크별 게이팅 — 다중 인스턴스 N배 실행 방지).
@SpringBootApplication
public class PrototypeApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrototypeApplication.class, args);
    }
}
