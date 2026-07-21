package com.example.springexample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Контракт сообщения топика "Images" (beads 6s0, дизайн n5y):
 * { "targetType": "userimage"|"chatimage", "targetId": "&lt;id&gt;", "objectKey": "&lt;key&gt;" }.
 * Совпадает с продюсером HTTPService и консюмером MessegerParody.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageUploadDTO {
    String targetType;
    String targetId;
    String objectKey;
}
