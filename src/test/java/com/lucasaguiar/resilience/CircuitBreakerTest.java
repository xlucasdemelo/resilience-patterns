package com.lucasaguiar.resilience;

import com.lucasaguiar.resilience.client.BookClient;
import feign.Feign;
import feign.Logger;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.vavr.collection.Array;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public class CircuitBreakerTest {

    private BookClient bookClient;

    @Rule
    public MockWebServer mockWebServer = new MockWebServer();
    private List<CircuitBreakerEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        this.bookClient = Feign.builder()
                .client(new OkHttpClient())
                .encoder(new GsonEncoder())
                .decoder(new GsonDecoder())
                .logger(new Slf4jLogger(BookClient.class))
                .logLevel(Logger.Level.FULL)
                .target(BookClient.class, mockWebServer.url("/").toString());

        this.events = new ArrayList<>();
    }

    @AfterEach
    void after(){
        this.events.forEach( event -> log.warn(event.getEventType().toString()));
    }

    @Test
    public void countBased_example() throws Exception {
        MockResponse mockResponse = new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json;charset=utf-8");

        CircuitBreaker circuitBreaker = CircuitBreaker.of("books", CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .permittedNumberOfCallsInHalfOpenState(2)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .build());

        this.captureEvents(circuitBreaker.getEventPublisher());

        Supplier<String> withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, bookClient::findAll);

        this.repeat(10, withCircuitBreaker, mockResponse);
        Thread.sleep(1000);
        this.repeat(4, withCircuitBreaker, mockResponse);

        // then
        Assertions.assertThat(this.events)
                .extracting(CircuitBreakerEvent::getEventType)
                .containsExactly(
                        // try x10
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR, // ringBufferInClosedState is full
                        CircuitBreakerEvent.Type.FAILURE_RATE_EXCEEDED,
                        CircuitBreakerEvent.Type.STATE_TRANSITION, // from CLOSED to OPEN
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        // sleep waitDurationInOpenState
                        CircuitBreakerEvent.Type.STATE_TRANSITION, // from OPEN to HALF_OPEN
                        // try x4
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR, // ringBufferInHalfOpenState is full
                        CircuitBreakerEvent.Type.STATE_TRANSITION, // from HALF_OPEN to OPEN
                        CircuitBreakerEvent.Type.NOT_PERMITTED,
                        CircuitBreakerEvent.Type.NOT_PERMITTED
                );
    }

    @Test
    public void circuitBreaker_recover(){
        MockResponse mockResponse = new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json;charset=utf-8");

        CircuitBreaker circuitBreaker = CircuitBreaker.of("books", CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .build());

        this.captureEvents(circuitBreaker.getEventPublisher());
        Supplier<String> withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, bookClient::findAll);

        this.enqueue(1, mockResponse);

        String result = Try.ofSupplier(withCircuitBreaker)
                .recoverWith(throwable -> Try.of(this::sendToQueue)).get();
    }

    @Test
    public void retry(){
        MockResponse mockResponse = new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json;charset=utf-8");

        MockResponse mockResponseSuccess = new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json;charset=utf-8");

        List<MockResponse> responseList = Array.of( mockResponse, mockResponse, mockResponse, mockResponseSuccess).collect(Collectors.toList());

        CircuitBreaker circuitBreaker = CircuitBreaker.of("books", CircuitBreakerConfig.custom()
                .waitDurationInOpenState(Duration.ofMillis(100))
                .build());

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(4)
                .waitDuration(Duration.ofMillis(2000))
                .build();

        // Create a RetryRegistry with a custom global configuration
        RetryRegistry registry = RetryRegistry.of(config);

        Retry retry = registry.retry("books", config);

        this.captureEvents(circuitBreaker.getEventPublisher());
        Supplier<String> withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, bookClient::findAll);

        withCircuitBreaker = Retry
                .decorateSupplier(retry, withCircuitBreaker);

        this.enqueueList(responseList);

        Try.ofSupplier(withCircuitBreaker).get();

        // then
        Assertions.assertThat(this.events)
                .extracting(CircuitBreakerEvent::getEventType)
                .containsExactly(
                        // try x10
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.ERROR,
                        CircuitBreakerEvent.Type.SUCCESS
                );
    }

    private String sendToQueue(){
        log.warn("Recovered in Kafka!");
        return "";
    }

    public void captureEvents(CircuitBreaker.EventPublisher eventPublisher) {
        eventPublisher.onEvent(events::add);
        eventPublisher.onSuccess(e -> log.info(e.toString()));
        eventPublisher.onError(e -> log.error(e.toString()));
        eventPublisher.onCallNotPermitted(e -> log.warn(e.toString()));
        eventPublisher.onStateTransition(e -> log.info(e.toString()));
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
