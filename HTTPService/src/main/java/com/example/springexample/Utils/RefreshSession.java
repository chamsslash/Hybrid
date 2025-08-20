package com.example.springexample.Utils;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@AllArgsConstructor
@Data
public class RefreshSession {
    String sub;
    FpSimilarityScore.ClientMeta meta;
    String accessId;


}
