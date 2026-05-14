package com.project.inventory.dto;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmbeddingRequest {
    private String text;
}
