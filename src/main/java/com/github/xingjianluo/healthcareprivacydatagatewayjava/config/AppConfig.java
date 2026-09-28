package com.github.xingjianluo.healthcareprivacydatagatewayjava.config;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.store.LocalDataStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Bean 装配：时钟、本地数据仓库、去标识化密钥。 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public LocalDataStore localDataStore() {
        return new LocalDataStore();
    }

    @Bean
    public String pseudonymSecret() {
        return "local-dev-pseudonym-secret";
    }
}
