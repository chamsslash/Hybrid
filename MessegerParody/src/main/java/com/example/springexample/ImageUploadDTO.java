package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadDTO {
    String targetType;
    String targetId;
    String objectKey;
}
