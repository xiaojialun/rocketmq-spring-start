package com.xjl.rocketmq.spring.config;

import com.xjl.rocketmq.spring.anno.RocketMQListener;
import com.xjl.rocketmq.spring.core.RocketMQMessageConverter;
import com.xjl.rocketmq.spring.core.RocketMQMessageListener;
import com.xjl.rocketmq.spring.core.SpringDefaultRocketMqConsumer;
import org.apache.rocketmq.client.AccessChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Configurable
public class RocketMQListenerConfig implements ApplicationContextAware , EnvironmentAware, SmartInitializingSingleton {

    private final static Logger log = LoggerFactory.getLogger(RocketMQListenerConfig.class);

    private ApplicationContext applicationContext;

    private Environment environment;

    private RocketMQProperties rocketMQProperties;

    private AtomicInteger counter = new AtomicInteger(0);

    private RocketMQMessageConverter rocketMQMessageConverter;

    public RocketMQListenerConfig(RocketMQProperties rocketMQProperties,RocketMQMessageConverter rocketMQMessageConverter) {
        this.rocketMQMessageConverter = rocketMQMessageConverter;
        this.rocketMQProperties = rocketMQProperties;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }


    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(RocketMQListener.class).entrySet().stream().filter(entry -> !ScopedProxyUtils.isScopedTarget(entry.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        beans.forEach(this::registerContainer);
    }

    void registerContainer(String beanName,Object bean){
        Class<?> clazz = AopProxyUtils.ultimateTargetClass(bean);

        RocketMQListener annotation = clazz.getAnnotation(RocketMQListener.class);

        String consumerGroup = this.environment.resolvePlaceholders(annotation.consumerGroup());
        String topic = this.environment.resolvePlaceholders(annotation.topic());

        boolean listenerEnabled =
                (boolean) rocketMQProperties.getConsumer().getListeners().getOrDefault(consumerGroup, Collections.EMPTY_MAP)
                        .getOrDefault(topic, true);

        if (!listenerEnabled) {
            log.debug(
                    "Consumer Listener (group:{},topic:{}) is not enabled by configuration, will ignore initialization.",
                    consumerGroup, topic);
            return;
        }
        // TODO: 2020/5/8 bean校验

        String containerBeanName = String.format("%s_%s", SpringDefaultRocketMqConsumer.class.getName(),
                counter.incrementAndGet());
        GenericApplicationContext genericApplicationContext = (GenericApplicationContext) applicationContext;

        genericApplicationContext.registerBean(containerBeanName, SpringDefaultRocketMqConsumer.class,
                () -> createRocketMessageMQListener(containerBeanName, bean, annotation));
        SpringDefaultRocketMqConsumer listener = genericApplicationContext.getBean(containerBeanName,
                SpringDefaultRocketMqConsumer.class);
        if (!listener.isRunning()) {
            try {
                listener.start();
            } catch (Exception e) {
                log.error("Started container failed. {}", listener, e);
                throw new RuntimeException(e);
            }
        }

        log.info("Register the listener to container, listenerBeanName:{}, containerBeanName:{}", beanName, containerBeanName);
    }

    private SpringDefaultRocketMqConsumer createRocketMessageMQListener(String name, Object bean, RocketMQListener annotation) {
        SpringDefaultRocketMqConsumer consumer = new SpringDefaultRocketMqConsumer(annotation,applicationContext);

        String nameServer = environment.resolvePlaceholders(annotation.nameServer());
        nameServer = StringUtils.isEmpty(nameServer) ? rocketMQProperties.getNameServer() : nameServer;
        String accessChannel = environment.resolvePlaceholders(annotation.accessChannel());
        consumer.setNameServer(nameServer);
        if (!StringUtils.isEmpty(accessChannel)) {
            consumer.setAccessChannel(AccessChannel.valueOf(accessChannel));
        }
        consumer.setTopic(environment.resolvePlaceholders(annotation.topic()));
        String tags = environment.resolvePlaceholders(annotation.selectorExpression());
        if (!StringUtils.isEmpty(tags)) {
            consumer.setSelectorExpression(tags);
        }
        consumer.setConsumerGroup(environment.resolvePlaceholders(annotation.consumerGroup()));
        consumer.setMqMessageListener((RocketMQMessageListener) bean);
        consumer.setMessageConverter(rocketMQMessageConverter.getMessageConverter());
        return consumer;
    }
}
