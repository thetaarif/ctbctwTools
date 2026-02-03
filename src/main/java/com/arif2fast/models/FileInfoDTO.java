package com.arif2fast.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfoDTO {
    private String moduleName;
    private String version;
    private String fileName;
    private Path filePath;
}
