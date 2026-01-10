package com.example.springexample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaProducer {
    @Autowired
    KafkaTemplate<String,String> tmpl;
    public void send(String event){tmpl.send("Images",event);}
}
