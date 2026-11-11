package com.zhiya.preschool.admin.controller;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestControllerAdvice(basePackageClasses = AdminErrors.class)
public class AdminErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> businessError(ResponseStatusException error) {
        String message = error.getReason() == null ? "管理请求未完成" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }
}
