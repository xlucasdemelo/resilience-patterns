package com.lucasaguiar.resilience;

import com.lucasaguiar.resilience.client.BookClient;
import feign.Feign;
import feign.Logger;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.event.RateLimiterEvent;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
public class RateLimiterTest {

    private BookClient bookClient;

    @Rule
    public MockWebServer mockWebServer = new MockWebServer();
    private List<RateLimiterEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        this.bookClient = Feign.builder()
                .client(new OkHttpClient())
                .encoder(new GsonEncoder())
                .decoder(new GsonDecoder())
                .logger(new Slf4jLogger(BookClient.class))
                .logLevel(Logger.Level.FULL)
                .target(BookClient.class, mockWebServer.url("/").toString());

        // Start the server.
    }

    @Test
    public void rateLimiterTest() {

        MockResponse mockResponse = new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json;charset=utf-8");

        //Resilience4J configuration for RateLimiter
        RateLimiterConfig config = RateLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100))
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(1)
                .build();

        // Create a RateLimiter
        RateLimiter rateLimiter = RateLimiter.of("books", config);

        this.captureEvents(rateLimiter.getEventPublisher());

        // Decorate your call to BackendService.doSomething()
        Supplier<String> restrictedSupplier = RateLimiter
                .decorateSupplier(rateLimiter, bookClient::findAll);

        //THis prepares the mock server to mock 2 requests
        this.enqueue(2, mockResponse);

        // First call is successful
        Try<String> firstTry = Try.ofSupplier(restrictedSupplier);
        Assertions.assertThat(firstTry.isSuccess()).isTrue();

        // Second call fails, because the call was not permitted
        Try<String> secondTry = Try.ofSupplier(restrictedSupplier);
        Assertions.assertThat(secondTry.isFailure()).isTrue();
        Assertions.assertThat(secondTry.getCause()).isInstanceOf(RequestNotPermitted.class);

        // then
        Assertions.assertThat(this.events)
                .extracting(RateLimiterEvent::getEventType)
                .containsExactly(
                        // try x10
                        RateLimiterEvent.Type.SUCCESSFUL_ACQUIRE,
                        RateLimiterEvent.Type.FAILED_ACQUIRE
                );
    }

    public void captureEvents(RateLimiter.EventPublisher eventPublisher) {
        eventPublisher.onEvent(events::add);
        eventPublisher.onSuccess(e -> log.info(e.toString()));
        eventPublisher.onFailure(e -> log.info(e.toString()));
    }

    public void enqueue(Integer queues, MockResponse mockResponse){
        for (var i = 0; i < queues; i++){
            this.mockWebServer.enqueue(mockResponse);
        }
    }

    public void enqueueList(List<MockResponse> responseList){
        responseList.forEach(response -> this.mockWebServer.enqueue(response));
    }

    public <T> List<Try<T>> repeat(int n, Supplier<T> action, MockResponse mockResponse) {
        this.enqueue(n, mockResponse);
        return Stream.<Supplier<T>>continually(action)
                .map(Try::ofSupplier)
                .take(n)
                .toJavaList();
    }
}
