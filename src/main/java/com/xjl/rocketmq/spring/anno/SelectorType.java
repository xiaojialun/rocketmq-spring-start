package com.xjl.rocketmq.spring.anno;

import org.apache.rocketmq.common.filter.ExpressionType;

public enum SelectorType {

    /**
     * @see ExpressionType#TAG
     */
    TAG,

    /**
     * @see ExpressionType#SQL92
     */
    SQL92
}
