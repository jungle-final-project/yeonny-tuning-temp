package com.buildgraph.prototype.config.cache;

import java.util.concurrent.TimeUnit;


import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    @ConditionalOnProperty(
        name = "spring.cache.type",
        havingValue = "caffeine",
        matchIfMissing = true
    )
    public CaffeineCacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(caffeineCacheBuilder());
        manager.setAsyncCacheMode(true);
        return manager;
    }

    @Bean
    @ConditionalOnProperty(
        name = "spring.cache.type",
        havingValue = "caffeine",
        matchIfMissing = true
    )
    /* 설정: 캐시 생명(10분), 저장크기(1,000개) */
 	public Caffeine<Object, Object> caffeineCacheBuilder() {
		return Caffeine.newBuilder()
			.expireAfterWrite(10, TimeUnit.MINUTES)
			.maximumSize(1000);
	}  
    
    @Bean
    @ConditionalOnProperty(
        name = "spring.cache.type",
        havingValue = "none"
    )
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }
}
