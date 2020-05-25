package com.lucasaguiar.resilience;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;

@SpringBootApplication
@EnableCircuitBreaker
public class ResilienceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ResilienceApplication.class, args);
	}

}
