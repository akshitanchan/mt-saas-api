package com.akshitanchan.saas.web;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// central error mapping so every failure looks like the python service's:
// ApiException -> its own status with {"detail": "<string>"}
// bad-request-shape exceptions (validation, unreadable body, path type mismatch) -> 422 with {"detail": [...]}
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", e.getDetail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (var fieldError : e.getBindingResult().getFieldErrors()) {
            errors.add(errorItem(List.of("body", fieldError.getField()), fieldError.getDefaultMessage(), "value_error"));
        }
        return unprocessable(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return unprocessable(List.of(errorItem(List.of("body"), "malformed request body", "value_error")));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException e) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (var violation : e.getConstraintViolations()) {
            errors.add(errorItem(List.of("path", violation.getPropertyPath().toString()), violation.getMessage(), "value_error"));
        }
        return unprocessable(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        return unprocessable(List.of(errorItem(List.of("path", name), "invalid " + name, "type_error")));
    }

    private static Map<String, Object> errorItem(List<String> loc, String msg, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("loc", loc);
        item.put("msg", msg);
        item.put("type", type);
        return item;
    }

    private static ResponseEntity<Map<String, Object>> unprocessable(List<Map<String, Object>> errors) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", errors));
    }
}
