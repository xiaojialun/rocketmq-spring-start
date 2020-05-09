package com.xjl.rocketmq.spring.config;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@Configurable
@EnableConfigurationProperties({RocketMQProperties.class})
@ConditionalOnProperty(prefix = "rocketmq", value = "name-server", matchIfMissing = true)
@Import({RocketMQListenerConfig.class,MessageConverterConfiguration.class})
@AutoConfigureAfter({MessageConverterConfiguration.class})
public class RocketMQAutoConfig {
}
