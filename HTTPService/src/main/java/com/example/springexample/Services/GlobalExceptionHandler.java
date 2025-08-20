//package com.example.springexample.Services;
//
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.stereotype.Service;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.context.request.WebRequest;
//
//import java.time.LocalDateTime;
//import java.util.LinkedHashMap;
//import java.util.Map;
//@Service
//@RestControllerAdvice
//public class GlobalExceptionHandler {
//
//    @ExceptionHandler(Exception.class)
//    public ResponseEntity<Object> handleAllExceptions(Exception ex, WebRequest request) {
//        Map<String, Object> errorDetails = new LinkedHashMap<>();
//        errorDetails.put("timestamp", LocalDateTime.now());
//        errorDetails.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
//        errorDetails.put("error", "Internal Server Error");
//        errorDetails.put("exception", ex.getClass().getName());
//        errorDetails.put("message", ex.getMessage());
//        errorDetails.put("path", request.getDescription(false).replace("uri=", ""));
//
//        return new ResponseEntity<>(errorDetails, HttpStatus.INTERNAL_SERVER_ERROR);
//    }
//
//    @ExceptionHandler(IllegalArgumentException.class)
//    public ResponseEntity<Object> handleBadRequest(IllegalArgumentException ex, WebRequest request) {
//        Map<String, Object> errorDetails = new LinkedHashMap<>();
//        errorDetails.put("timestamp", LocalDateTime.now());
//        errorDetails.put("status", HttpStatus.BAD_REQUEST.value());
//        errorDetails.put("error", "Bad Request");
//        errorDetails.put("exception", ex.getClass().getName());
//        errorDetails.put("message", ex.getMessage());
//        errorDetails.put("path", request.getDescription(false).replace("uri=", ""));
//
//        return new ResponseEntity<>(errorDetails, HttpStatus.BAD_REQUEST);
//    }
//
//    // Добавь свои кастомные обработчики тут при необходимости
//}