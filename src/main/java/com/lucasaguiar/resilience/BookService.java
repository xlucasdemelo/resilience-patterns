package com.lucasaguiar.resilience;

import com.lucasaguiar.resilience.client.BookClient;
import com.lucasaguiar.resilience.entity.Book;
import feign.Feign;
import feign.Logger;
import feign.Response;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class BookService {

    private BookClient bookClient;

    public BookService(String url){
        this.bookClient = Feign.builder()
                .client(new OkHttpClient())
                .encoder(new GsonEncoder())
                .decoder(new GsonDecoder())
                .logger(new Slf4jLogger(BookClient.class))
                .logLevel(Logger.Level.FULL)
                .target(BookClient.class, url);
    }

    public Book findByIsbn(String isbn){
        String response = this.bookClient.findByIsbn(isbn);

        return new Book();
    }

}
