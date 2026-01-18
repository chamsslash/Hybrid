package com.example.springexample;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

@Configuration
public class RequestDataValueProcessorConfig implements BeanDefinitionRegistryPostProcessor {

    private static final String BEAN_NAME = "requestDataValueProcessor";

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            registry.removeBeanDefinition(BEAN_NAME);
        }

        BeanDefinition beanDefinition = BeanDefinitionBuilder
                .genericBeanDefinition(RequestDataValueProcessor.class, CsrfRequestDataValueProcessor::new)
                .getBeanDefinition();

        registry.registerBeanDefinition(BEAN_NAME, beanDefinition);
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // no-op
    }
}

