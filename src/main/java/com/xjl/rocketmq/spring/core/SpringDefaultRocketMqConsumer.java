package com.xjl.rocketmq.spring.core;

import com.xjl.rocketmq.spring.exception.MessageListenerNullException;
import com.xjl.rocketmq.spring.anno.ConsumeMode;
import com.xjl.rocketmq.spring.anno.RocketMQListener;
import com.xjl.rocketmq.spring.anno.SelectorType;
import com.xjl.rocketmq.spring.util.RocketMQUtil;
import org.apache.rocketmq.client.AccessChannel;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.MessageSelector;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.remoting.RPCHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.SmartMessageConverter;
import org.springframework.messaging.support.MessageBuilder;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

public class SpringDefaultRocketMqConsumer implements InitializingBean {

    private final static Logger log = LoggerFactory.getLogger(SpringDefaultRocketMqConsumer.class);

    private boolean running = false;

    private DefaultMQPushConsumer consumer;

    private RocketMQMessageListener mqMessageListener;

    private RocketMQListener rocketMQListenerAnn;

    private ApplicationContext applicationContext;

    private String consumerGroup;

    private String nameServer;

    private AccessChannel accessChannel = AccessChannel.LOCAL;

    private String topic;

    private int consumeThreadMax = 64;

    private Type messageType;

    private MessageConverter messageConverter;

    private MethodParameter methodParameter;

    private long suspendCurrentQueueTimeMillis = 1000;


    // The following properties came from @RocketMQMessageListener.
    private ConsumeMode consumeMode;
    private SelectorType selectorType;
    private String selectorExpression;
    private MessageModel messageModel;
    private long consumeTimeout;


    /**
     * Message consume retry strategy<br> -1,no retry,put into DLQ directly<br> 0,broker control retry frequency<br>
     * >0,client control retry frequency.
     */
    private int delayLevelWhenNextConsume = 0;

    private String charset = "UTF-8";

    public MessageConverter getMessageConverter() {
        return messageConverter;
    }

    public void setMessageConverter(MessageConverter messageConverter) {
        this.messageConverter = messageConverter;
    }

    public void setSelectorExpression(String selectorExpression) {
        this.selectorExpression = selectorExpression;
    }

    public void setAccessChannel(AccessChannel accessChannel) {
        this.accessChannel = accessChannel;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public RocketMQMessageListener getMqMessageListener() {
        return mqMessageListener;
    }

    public void setMqMessageListener(RocketMQMessageListener mqMessageListener) {
        this.mqMessageListener = mqMessageListener;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public SpringDefaultRocketMqConsumer(RocketMQListener rocketMQListener, ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.rocketMQListenerAnn = rocketMQListener;
        this.consumeMode = rocketMQListener.consumeMode();
        this.selectorType = rocketMQListener.selectorType();
        this.consumeThreadMax = rocketMQListener.consumeThreadMax();
        this.consumeTimeout = rocketMQListener.consumeTimeout();
        this.messageModel = rocketMQListener.messageModel();
    }

    @PostConstruct
    public void init() {
        log.debug("postConstruct is running");
    }


    @PreDestroy
    public void destroy() {
        if (this.running && consumer != null) {
            consumer.shutdown();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void start() throws MQClientException {
        if (!running && consumer != null) {
            consumer.start();
            running = true;
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        if (mqMessageListener!=null){
            this.messageType = getMessageType();
            this.methodParameter = getMethodParameter();
        }

        RPCHook rpcHook = RocketMQUtil.getRPCHookByAkSk(applicationContext.getEnvironment(),
                this.rocketMQListenerAnn.accessKey(), this.rocketMQListenerAnn.secretKey());
        boolean enableMsgTrace = rocketMQListenerAnn.enableMsgTrace();
        if (Objects.nonNull(rpcHook)) {
            consumer = new DefaultMQPushConsumer(consumerGroup, rpcHook, new AllocateMessageQueueAveragely(),
                    enableMsgTrace, this.applicationContext.getEnvironment().
                    resolveRequiredPlaceholders(this.rocketMQListenerAnn.customizedTraceTopic()));
            consumer.setVipChannelEnabled(false);
            consumer.setInstanceName(RocketMQUtil.getInstanceName(rpcHook, consumerGroup));
        } else {
            log.debug("Access-key or secret-key not configure in " + this + ".");
            consumer = new DefaultMQPushConsumer(consumerGroup, enableMsgTrace,
                    this.applicationContext.getEnvironment().
                            resolveRequiredPlaceholders(this.rocketMQListenerAnn.customizedTraceTopic()));
        }

        String customizedNameServer = this.applicationContext.getEnvironment().resolveRequiredPlaceholders(this.rocketMQListenerAnn.nameServer());
        if (customizedNameServer != null) {
            consumer.setNamesrvAddr(customizedNameServer);
        } else {
            consumer.setNamesrvAddr(nameServer);
        }
        if (accessChannel != null) {
            consumer.setAccessChannel(accessChannel);
        }
        consumer.setConsumeThreadMax(consumeThreadMax);
        if (consumeThreadMax < consumer.getConsumeThreadMin()) {
            consumer.setConsumeThreadMin(consumeThreadMax);
        }
        consumer.setConsumeTimeout(consumeTimeout);

        switch (messageModel) {
            case BROADCASTING:
                consumer.setMessageModel(org.apache.rocketmq.common.protocol.heartbeat.MessageModel.BROADCASTING);
                break;
            case CLUSTERING:
                consumer.setMessageModel(org.apache.rocketmq.common.protocol.heartbeat.MessageModel.CLUSTERING);
                break;
            default:
                throw new IllegalArgumentException("Property 'messageModel' was wrong.");
        }

        switch (selectorType) {
            case TAG:
                consumer.subscribe(topic, selectorExpression);
                break;
            case SQL92:
                consumer.subscribe(topic, MessageSelector.bySql(selectorExpression));
                break;
            default:
                throw new IllegalArgumentException("Property 'selectorType' was wrong.");
        }

        switch (consumeMode) {
            case ORDERLY:
                consumer.setMessageListener(new DefaultMessageListenerOrderly());
                break;
            case CONCURRENTLY:
                consumer.setMessageListener(new DefaultMessageListenerConcurrently());
                break;
            default:
                throw new IllegalArgumentException("Property 'consumeMode' was wrong.");
        }

        this.start();
//        if (rocketMQListener instanceof RocketMQPushConsumerLifecycleListener) {
//            ((RocketMQPushConsumerLifecycleListener) rocketMQListener).prepareStart(consumer);
//        } else if (rocketMQReplyListener instanceof RocketMQPushConsumerLifecycleListener) {
//            ((RocketMQPushConsumerLifecycleListener) rocketMQReplyListener).prepareStart(consumer);
//        }
    }

    public class DefaultMessageListenerConcurrently implements MessageListenerConcurrently {

        @SuppressWarnings("unchecked")
        @Override
        public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
            for (MessageExt messageExt : msgs) {
                log.debug("received msg: {}", messageExt);
                try {
                    long now = System.currentTimeMillis();
                    handleMessage(messageExt);
                    long costTime = System.currentTimeMillis() - now;
                    log.debug("consume {} cost: {} ms", messageExt.getMsgId(), costTime);
                } catch (Exception e) {
                    log.warn("consume message failed. messageExt:{}, error:{}", messageExt, e);
                    context.setDelayLevelWhenNextConsume(delayLevelWhenNextConsume);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }

            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
    }

    public class DefaultMessageListenerOrderly implements MessageListenerOrderly {

        @SuppressWarnings("unchecked")
        @Override
        public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs, ConsumeOrderlyContext context) {
            for (MessageExt messageExt : msgs) {
                log.debug("received msg: {}", messageExt);
                try {
                    long now = System.currentTimeMillis();
                    handleMessage(messageExt);
                    long costTime = System.currentTimeMillis() - now;
                    log.info("consume {} cost: {} ms", messageExt.getMsgId(), costTime);
                } catch (Exception e) {
                    log.warn("consume message failed. messageExt:{}", messageExt, e);
                    context.setSuspendCurrentQueueTimeMillis(suspendCurrentQueueTimeMillis);
                    return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                }
            }

            return ConsumeOrderlyStatus.SUCCESS;
        }
    }

    private void handleMessage(MessageExt messageExt) {
        if (mqMessageListener != null) {
            mqMessageListener.onMessage(doConvertMessage(messageExt));
        }

    }

    private Object doConvertMessage(MessageExt messageExt) {
        if (Objects.equals(messageType, MessageExt.class)) {
            return messageExt;
        } else {
            String str = new String(messageExt.getBody(), Charset.forName(charset));
            if (Objects.equals(messageType, String.class)) {
                return str;
            } else {
                // If msgType not string, use objectMapper change it.
                try {
                    if (messageType instanceof Class) {
                        //if the messageType has not Generic Parameter
                        return this.getMessageConverter().fromMessage(MessageBuilder.withPayload(str).build(), (Class<?>) messageType);
                    } else {
                        //if the messageType has Generic Parameter, then use SmartMessageConverter#fromMessage with third parameter "conversionHint".
                        //we have validate the MessageConverter is SmartMessageConverter in this#getMethodParameter.
                        return ((SmartMessageConverter) this.getMessageConverter()).fromMessage(MessageBuilder.withPayload(str).build(), (Class<?>) ((ParameterizedType) messageType).getRawType(), methodParameter);
                    }
                } catch (Exception e) {
                    log.info("convert failed. str:{}, msgType:{}", str, messageType);
                    throw new RuntimeException("cannot convert message to " + messageType, e);
                }
            }
        }
    }

    private MethodParameter getMethodParameter() {
        Class<?> targetClass;
        if (mqMessageListener == null) {
            throw new MessageListenerNullException();
        }
        targetClass = AopProxyUtils.ultimateTargetClass(mqMessageListener);
        Type messageType = this.getMessageType();
        Class clazz = null;
        if (messageType instanceof ParameterizedType && messageConverter instanceof SmartMessageConverter) {
            clazz = (Class) ((ParameterizedType) messageType).getRawType();
        } else if (messageType instanceof Class) {
            clazz = (Class) messageType;
        } else {
            throw new RuntimeException("parameterType:" + messageType + " of onMessage method is not supported");
        }
        try {
            final Method method = targetClass.getMethod("onMessage", clazz);
            return new MethodParameter(method, 0);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
            throw new RuntimeException("parameterType:" + messageType + " of onMessage method is not supported");
        }
    }

    private Type getMessageType() {
        Class<?> targetClass;
        if (mqMessageListener == null) {
            throw new MessageListenerNullException();
        }
        targetClass = AopProxyUtils.ultimateTargetClass(mqMessageListener);
        Type matchedGenericInterface = null;
        while (Objects.nonNull(targetClass)) {
            Type[] interfaces = targetClass.getGenericInterfaces();
            if (Objects.nonNull(interfaces)) {
                for (Type type : interfaces) {
                    if (type instanceof ParameterizedType &&
                            Objects.equals(((ParameterizedType) type).getRawType(), RocketMQMessageListener.class)) {
                        matchedGenericInterface = type;
                        break;
                    }
                }
            }
            targetClass = targetClass.getSuperclass();
        }
        if (Objects.isNull(matchedGenericInterface)) {
            return Object.class;
        }

        Type[] actualTypeArguments = ((ParameterizedType) matchedGenericInterface).getActualTypeArguments();
        if (Objects.nonNull(actualTypeArguments) && actualTypeArguments.length > 0) {
            return actualTypeArguments[0];
        }
        return Object.class;
    }
}
