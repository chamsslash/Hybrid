package com.example.springexample.Utils;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@AllArgsConstructor
@Data
public class RefreshSession {
    String sub;
    String sid;
    String refreshJti;
    String accessJti;
    FpSimilarityScore.ClientMeta meta;
    long createdAt;
    long lastSeenAt;
    long rotatedAt;
    String status;
}
