package com.lucasaguiar.resilience;

import com.lucasaguiar.resilience.client.BookClient;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.bulkhead.event.BulkheadEvent;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.event.RateLimiterEvent;
import io.vavr.collection.Stream;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
    private List<BulkheadEvent> events = new ArrayList<>();

    private static final int NUMBER_OF_BOOKS = 5;
    private static final int NUMBER_OF_PEOPLE = 20;
    private AtomicLong availableBooks;

    @BeforeEach
    void setUp() {
        availableBooks = new AtomicLong(NUMBER_OF_BOOKS);
    }

//    @AfterEach
    void afterEach(){
        this.events.stream().forEach(event -> log.info(event.toString()));
    }

    private <T> void everybodyInTheLibrary(Runnable task) throws InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(NUMBER_OF_PEOPLE);
        List<Callable<Object>> tasks = Stream.continually(task)
                .map(Executors::callable)
                .take(20).toJavaList();
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
    void unlimited() throws InterruptedException {
        everybodyInTheLibrary(this::takeBook);
    }

    @Test
    void synchronize() throws InterruptedException {
        everybodyInTheLibrary(this::takeBookSync);
    }

    synchronized private void takeBookSync() {
        takeBook();
    }

    @Test
    void threadPoolBulkhead() throws InterruptedException {
        //Only 5 Threads will be the hability to execute a code
        ThreadPoolBulkhead threadPoolBulkhead = ThreadPoolBulkhead.of("threads", ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(5)
                .maxThreadPoolSize(5)
                .build());

        //Decorating the execution of the method and the configuration of the threadPool
        Supplier<CompletionStage<Void>> withBulkhead = ThreadPoolBulkhead.decorateRunnable(threadPoolBulkhead, this::takeBook);

        //Creating a new ExecutorService
        ExecutorService service = Executors.newFixedThreadPool(NUMBER_OF_PEOPLE);

        //Wrapping the execution of the bulkhead inside  a task
        Runnable task = () -> {
            log.info("Try to get a book");
            Try.ofSupplier(withBulkhead);
        };

        //Creating N threads to execute the task
        List<Callable<Object>> tasks = Stream.continually(task)
                .map(Executors::callable)
                .take(NUMBER_OF_BOOKS).toJavaList();

        //Running all the threads
        service.invokeAll(tasks, 500, TimeUnit.MILLISECONDS);
    }

    @Test
    void semaphore() throws InterruptedException {
        Bulkhead bulkhead = Bulkhead.of("semaphore", BulkheadConfig.custom()
                .maxConcurrentCalls(NUMBER_OF_BOOKS)
                .build());
        Runnable withBulkhead = Bulkhead.decorateRunnable(bulkhead, this::takeBook);

        this.captureEvents(bulkhead.getEventPublisher());

        everybodyInTheLibrary(withBulkhead);
    }

    public void captureEvents(Bulkhead.EventPublisher eventPublisher) {
        eventPublisher.onEvent(events::add);
//        eventPublisher.onCallPermitted(e -> log.info(e.toString()));
//        eventPublisher.onCallRejected(e -> log.info(e.toString()));
    }

}
