package com.example.springexample.Utils;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
@RequiredArgsConstructor
@Data
public class TokenException extends RuntimeException{
    private final HttpStatus status;
    private final String errorCode;
    private final String message;


}
