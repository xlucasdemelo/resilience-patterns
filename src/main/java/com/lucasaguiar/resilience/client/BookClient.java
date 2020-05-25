package com.lucasaguiar.resilience.client;

import com.lucasaguiar.resilience.entity.Book;
import com.lucasaguiar.resilience.entity.BookResource;
import feign.Headers;
import feign.Param;
import feign.RequestLine;

import java.util.List;

public interface BookClient {
    @RequestLine("GET /{isbn}")
    String findByIsbn(@Param("isbn") String isbn);

    @RequestLine("GET")
    String findAll();

    @RequestLine("POST")
    @Headers("Content-Type: application/json")
    void create(Book book);
}
