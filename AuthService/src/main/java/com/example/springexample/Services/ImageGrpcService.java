package com.example.springexample.Services;



import com.example.grpc.DataTransferService;
import com.example.grpc.ImageTransferServiceGrpc;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ImageGrpcService {
    @GrpcClient("ImageTransferService")
    private ImageTransferServiceGrpc.ImageTransferServiceBlockingStub stub;
    public String SaveImage(String B64_image, String mimeType , String Extension, String imagename){
        DataTransferService.ImageDTO dto = DataTransferService.ImageDTO.newBuilder()
                .setBase64Image(B64_image).setImageName(imagename)
                .setExtension(Extension).setMimeType(mimeType).build();
        String savedimageurl  =stub.saveImageToDrive(dto).getUrl();
        log.info("image with id "+ savedimageurl+" saved successfully");
        return savedimageurl;
    }
    }

