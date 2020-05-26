package com.lucasaguiar.resilience;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.vavr.control.Try;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.lang.Thread.sleep;

public class TimeLimiterTest {

    @Test
    void durarionLess_ShouldPass() {
        //Defines a timelimiter
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        //Will Decorate a function that takes 42ms to execute
        Callable<Integer> wait = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> sleep(42));

        //Execute the decorator
        Try<Integer> response = Try.ofCallable(wait);

        Assertions.assertThat(response).contains(42);
    }

    @Test
    void durationMore_ShouldThrowException() {
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        Callable<Integer> wait = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> sleep(777));

        Try<Integer> response = Try.ofCallable(wait);

        Assertions.assertThat(response).isEmpty();
        Assertions.assertThat(response.getCause()).isInstanceOf(TimeoutException.class);
    }

    private Future<Integer> sleep(int millis) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(millis);
                return millis;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
    }
}
