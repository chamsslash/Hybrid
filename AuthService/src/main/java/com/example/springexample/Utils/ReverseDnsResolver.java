package com.example.springexample.Utils;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
@Slf4j
public class ReverseDnsResolver {
    public String getPTR(String ip){
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.getHostName();
        }catch (UnknownHostException E){
            log.error("Cannot get PRT addr",E);
            return null;
        }
    }
}
