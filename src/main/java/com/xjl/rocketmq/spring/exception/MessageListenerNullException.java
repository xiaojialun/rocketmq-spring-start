package com.xjl.rocketmq.spring.exception;

public class MessageListenerNullException extends RuntimeException {

    public MessageListenerNullException() {
        super("mqMessageListener is null");
    }
}
