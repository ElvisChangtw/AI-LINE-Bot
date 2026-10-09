package com.elvischang.ailinebot.factcheck;

public class InvalidModelOutputException extends RuntimeException {

    public InvalidModelOutputException(String reason) {
        super(reason);
    }
}
