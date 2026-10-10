package com.elvischang.ailinebot.factcheck;

public class OutOfCreditException extends RuntimeException {

    public OutOfCreditException(Throwable cause) {
        super("OpenAI credit balance is empty", cause);
    }
}
