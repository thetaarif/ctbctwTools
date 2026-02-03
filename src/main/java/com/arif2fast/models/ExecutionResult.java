package com.arif2fast.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {
    private int exitCode;
    private String output;
    private String error;
    private boolean success;
}
