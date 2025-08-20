package com.example.springexample;



import com.example.grpc.DataTransferService;
import com.example.grpc.NotifyServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

@Service
public class Notification_gRPC_Client {
    @GrpcClient("NotifyService")
    private NotifyServiceGrpc.NotifyServiceBlockingStub stub;
    public void notifyme(NotificationDTO notification) {
        DataTransferService.Notification notification1= DataTransferService.Notification.newBuilder()
                .setContent(notification.getText())
                .setType(notification.getType())
                .addAllUserId(notification.getUser_id())
                .setAuthorId(notification.getAuthorId())
                .build();
        stub.transfernotification(notification1);
    }
}
