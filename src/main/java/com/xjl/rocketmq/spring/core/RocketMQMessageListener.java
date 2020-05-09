package com.xjl.rocketmq.spring.core;

public interface RocketMQMessageListener<T> {
    void onMessage(T message);
}
