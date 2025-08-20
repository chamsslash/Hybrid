package com.example.springexample.Services;


import com.example.grpc.AuthTransferServiceGrpc;
import com.example.grpc.DataTransferService;


import com.example.springexample.Metrics.GrpcRequestsMetric;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AuthGrpc {
    @Autowired
    GrpcRequestsMetric grpcRequestsMetric;
    DataTransferService.AuthResponse authResponse;
    @GrpcClient("AuthTransferService")
    private AuthTransferServiceGrpc.AuthTransferServiceBlockingStub authTransferServiceBlockingStub;
    public DataTransferService.AuthResponse authlogin(DataTransferService.UserDataRequest userData) {
        grpcRequestsMetric.increment();
        try{
            DataTransferService.AuthResponse authResponse1 = authTransferServiceBlockingStub.login(userData);
            if (authResponse1.getStatus().equals("404")){
                log.error("User does not exists");
                return null;

            }
            if (authResponse1 != null && authResponse1.getStatus().equals("200")) {
                log.warn(authResponse1.toString());
                return  authResponse1;
            }log.info("wrong answer from auth");
            return null;
        } catch (RuntimeException e) {
            log.info("passwordmissmatch error");
            return null;
        }

    }
    public  DataTransferService.AuthResponse authRegister(DataTransferService.UserDataRequest userDataRequest){
        try {
        DataTransferService.AuthResponse authResponse  =authTransferServiceBlockingStub.register(userDataRequest);
        if (authResponse != null && authResponse.getStatus().equals("200")) {
            return  authResponse;
        }
        if (authResponse.getStatus().equals("404")){
            log.error("Cannot register user error");
            return null;

        }log.info("wrong answer from auth");
        return null;
    } catch (RuntimeException e) {
        log.info("passwordmissmatch error",e);
        return null;
    }
    }

    public Mono<List<Long>> GetUsersByUnames(DataTransferService.RepeatedUsernames repeatedUsernames){
        grpcRequestsMetric.increment();
        return Mono.fromCallable(()->{
            DataTransferService.UserListResponse userListResponse = authTransferServiceBlockingStub.getUserByUsername(repeatedUsernames);
            List<DataTransferService.UserDataRequest> list = userListResponse.getUsersList();
            List<Long> resp = new ArrayList<>();
            for(DataTransferService.UserDataRequest userDataRequest : list){
                resp.add(userDataRequest.getId());

            }
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());



    }
//    public List<String> GetAllNamesByChat(DataTransferService.ChatData chatData){
//        grpcRequestsMetric.increment();
//        DataTransferService.ChatData resp =  authTransferServiceBlockingStub.getAllUsersByChatid(chatData);
//        List<String> names = new ArrayList<>();
//        for (DataTransferService.User user : resp.getUserList() ){
//            names.add(user.getUsername());
//        }
//        return names;
//    }
    public List<String> GetAllIdsByChat(DataTransferService.ChatData chatData){
        grpcRequestsMetric.increment();
        DataTransferService.UserListResponse response = authTransferServiceBlockingStub.getAllUsersByChatId(chatData);//r
        List<String> resp = new ArrayList<>();
        for (DataTransferService.UserDataRequest user : response.getUsersList()){
            resp.add(String.valueOf(user.getId()));
        }
            return resp;
    }
    public boolean checkJwtToken (DataTransferService.idToken idToken){
        try{
            grpcRequestsMetric.increment();
            DataTransferService.AccessResponse accessResponse = authTransferServiceBlockingStub.checkAuthorization(idToken);
            if (accessResponse!=null && !accessResponse.getAccess().isEmpty()){
                return true;
            }else return false;

        }catch (Exception e){
            log.error("error in getting success auth data",e);
            return false;

        }



    }
    public DataTransferService.User getUserBySub(String sub){
        grpcRequestsMetric.increment();
        try {
           DataTransferService.User usr=  authTransferServiceBlockingStub.getUserBySub(DataTransferService.Sub_Role.newBuilder().setSub(sub).build());
           if (usr.getUsername().isEmpty()){
               log.error("User with this sub is not exists");
               return null;
           }
           return usr;
        }catch (Exception e){
            log.error("User with this sub is not exists");
            return null;
        }
    }
    public DataTransferService.Sub_Role exchangeOneTimeToken(String oneTimeToken){
        grpcRequestsMetric.increment();
        try {
            DataTransferService.Sub_Role subRole=authTransferServiceBlockingStub.checkOneTimeCodeAndGetSubRole(DataTransferService.idToken.newBuilder().setId(oneTimeToken).build());
            log.info(subRole.toString());
            return subRole;
        }catch(Exception e) {
                log.error("access denied cause:",e);
                throw new RuntimeException("ACCESS DENIED");
                }

    }

}
