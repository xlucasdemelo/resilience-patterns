package com.lucasaguiar.resilience;

import com.lucasaguiar.resilience.client.BookClient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.event.RateLimiterEvent;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public class BulkheadTest {

    private BookClient bookClient;

    @Rule
    public MockWebServer mockWebServer = new MockWebServer();
    private List<RateLimiterEvent> events = new ArrayList<>();

    private static final int NUMBER_OF_BOOKS = 5;
    private static final int NUMBER_OF_PEOPLE = 20;
    private AtomicLong availableBooks;

    @BeforeEach
    void setUp() {
        availableBooks = new AtomicLong(NUMBER_OF_BOOKS);
    }

    private <T> void everybodyInTheLibrary(Runnable task) throws InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(NUMBER_OF_PEOPLE);
        List<Callable<Object>> tasks = Stream.continually(task)
                .map(Executors::callable)
                .take(NUMBER_OF_PEOPLE).toJavaList();
        service.invokeAll(tasks, 500, TimeUnit.MILLISECONDS);
    }


    private void takeBook() {
        try {
            log.info("{} books left", availableBooks.decrementAndGet());
            Thread.sleep(100);
        } catch (InterruptedException e) {
            log.error(e.getMessage().toUpperCase());
            Thread.currentThread().interrupt();
        } finally {
            availableBooks.incrementAndGet();
        }
    }

    @Test
    void synchronize() throws InterruptedException {
        everybodyInTheLibrary(this::takeBookSync);
    }

    synchronized private void takeBookSync() {
        takeBook();
    }

    @Test
    void unlimited() throws InterruptedException {
        everybodyInTheLibrary(this::takeBook);
    }

    @Test
    void threadPoolBulkhead() throws InterruptedException {
        ThreadPoolBulkhead threadPoolBulkhead = ThreadPoolBulkhead.of("threads", ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(NUMBER_OF_BOOKS)
                .maxThreadPoolSize(NUMBER_OF_BOOKS)
//                .queueCapacity(1)
                .build());

        Supplier<CompletionStage<Void>> withBulkhead = ThreadPoolBulkhead.decorateRunnable(threadPoolBulkhead, this::takeBook);

        withBulkhead.get();
    }

    @Test
    void bulkhead() throws InterruptedException {
        Bulkhead bulkhead = Bulkhead.of("semaphore", BulkheadConfig.custom()
                .maxConcurrentCalls(NUMBER_OF_BOOKS)
                .build());
        Runnable withBulkhead = Bulkhead.decorateRunnable(bulkhead, this::takeBook);

        everybodyInTheLibrary(withBulkhead);
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
