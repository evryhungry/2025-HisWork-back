package com.hiswork.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hiswork.backend.domain.Document;
import com.hiswork.backend.domain.DocumentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {
    private Long id;
    private Long templateId;
    private String templateName;
    private String title;
    private JsonNode data;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deadline;
    private Boolean isRejected;
    private List<TaskInfo> tasks;
    private List<DocumentStatusLogResponse> statusLogs;
    
    // Template 정보 추가
    private TemplateInfo template;
    
    // 폴더 정보 추가
    private String folderId;
    private String folderName;
    
    public static DocumentResponse from(Document document) {
        List<TaskInfo> taskInfos = document.getDocumentRoles().stream()
                .map(TaskInfo::from)
                .collect(Collectors.toList());
        
        List<DocumentStatusLogResponse> statusLogResponses = document.getStatusLogs().stream()
                .map(DocumentStatusLogResponse::from)
                .collect(Collectors.toList());
        
        TemplateInfo templateInfo = TemplateInfo.from(document.getTemplate());
        
        return DocumentResponse.builder()
                .id(document.getId())
                .templateId(document.getTemplate().getId())
                .templateName(document.getTemplate().getName())
                .title(document.getTitle())
                .data(document.getData())
                .status(document.getStatus().name())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .deadline(document.getDeadline())
                .isRejected(document.getIsRejected() != null ? document.getIsRejected() : false)
                .tasks(taskInfos)
                .statusLogs(statusLogResponses)
                .template(templateInfo)
                .folderId(document.getFolder() != null ? document.getFolder().getId().toString() : null)
                .folderName(document.getFolder() != null ? document.getFolder().getName() : null)
                .build();
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskInfo {
        private Long id;
        private String role;
        private String assignedUserName;
        private String assignedUserEmail;
        private LocalDateTime lastViewedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Boolean isNew;
        private LocalDateTime tokenExpiresAt; // 서명자 토큰 만료일 (SIGNER만 해당)

        public static TaskInfo from(DocumentRole documentRole) {
            return TaskInfo.builder()
                    .id(documentRole.getId())
                    .role(documentRole.getTaskRole().name())
                    .assignedUserName(documentRole.getPendingName())
                    .assignedUserEmail(documentRole.getPendingEmail())
                    .lastViewedAt(documentRole.getLastViewedAt())
                    .createdAt(documentRole.getCreatedAt())
                    .updatedAt(documentRole.getUpdatedAt())
                    .isNew(documentRole.isNew())
                    .build();
        }

        public static TaskInfo from(DocumentRole documentRole, LocalDateTime tokenExpiresAt) {
            return TaskInfo.builder()
                    .id(documentRole.getId())
                    .role(documentRole.getTaskRole().name())
                    .assignedUserName(documentRole.getPendingName())
                    .assignedUserEmail(documentRole.getPendingEmail())
                    .lastViewedAt(documentRole.getLastViewedAt())
                    .createdAt(documentRole.getCreatedAt())
                    .updatedAt(documentRole.getUpdatedAt())
                    .isNew(documentRole.isNew())
                    .tokenExpiresAt(tokenExpiresAt)
                    .build();
        }
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateInfo {
        private Long id;
        private String name;
        private String description;
        private Boolean isPublic;
        private String pdfFilePath;
        private String pdfImagePath;
        private String pdfImagePaths;
        private String coordinateFields; // JSON 형태로 저장된 좌표 필드 정보
        private LocalDateTime deadline; // 만료일 추가
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Boolean isMultiPage;
        private Integer totalPages;
        private String pdfPagesData; // PDF 페이지별 데이터 (JSON 형태)

        
        public static TemplateInfo from(com.hiswork.backend.domain.Template template) {
            return TemplateInfo.builder()
                    .id(template.getId())
                    .name(template.getName())
                    .description(template.getDescription())
                    .isPublic(template.getIsPublic())
                    .pdfFilePath(template.getPdfFilePath())
                    .pdfImagePath(template.getPdfImagePath())
                    .pdfImagePaths(template.getPdfImagePaths())
                    .coordinateFields(template.getCoordinateFields())
                    .deadline(template.getDeadline()) // 만료일 추가
                    .createdAt(template.getCreatedAt())
                    .updatedAt(template.getUpdatedAt())
                    .isMultiPage(template.getIsMultiPage())
                    .totalPages(template.getTotalPages())
                    .pdfPagesData(template.getPdfPagesData())
                    .build();
        }
    }
} 