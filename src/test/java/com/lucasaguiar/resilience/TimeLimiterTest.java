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
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        Callable<Integer> wait42 = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> sleep(42));

        Try<Integer> dream = Try.ofCallable(wait42);

        Assertions.assertThat(dream).contains(42);
    }

    @Test
    void durationMore_ShouldThrowException() {
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100))
                .build());

        Callable<Integer> wait666 = TimeLimiter.decorateFutureSupplier(timeLimiter, () -> sleep(666));

        Try<Integer> dream = Try.ofCallable(wait666);

        Assertions.assertThat(dream).isEmpty();
        Assertions.assertThat(dream.getCause()).isInstanceOf(TimeoutException.class);
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
