package com.flightaggregator.mockgds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The three mock providers behind one HTTP server. */
@SpringBootApplication
public class MockGdsApplication {

  public static void main(String[] args) {
    SpringApplication.run(MockGdsApplication.class, args);
  }
}
