package com.xjl.rocketmq.spring.config;

import com.xjl.rocketmq.spring.core.RocketMQMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean({RocketMQMessageConverter.class})
public class MessageConverterConfiguration {

    @Bean
    public RocketMQMessageConverter rocketMQMessageConverter(){
        return new RocketMQMessageConverter();
    }
}
