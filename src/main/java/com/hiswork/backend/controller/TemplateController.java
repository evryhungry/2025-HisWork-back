package com.hiswork.backend.controller;

import com.hiswork.backend.domain.Folder;
import com.hiswork.backend.domain.Position;
import com.hiswork.backend.domain.Role;
import com.hiswork.backend.domain.Template;
import com.hiswork.backend.domain.User;
import com.hiswork.backend.dto.TemplateResponse;
import com.hiswork.backend.repository.FolderRepository;
import com.hiswork.backend.repository.UserRepository;
import com.hiswork.backend.service.PdfService;
import com.hiswork.backend.service.TemplateService;
import com.hiswork.backend.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/templates")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class TemplateController {

    private final TemplateService templateService;
    private final PdfService pdfService;
    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final AuthUtil authUtil;

//    @PostMapping
//    public ResponseEntity<?> createTemplate(
//            @Valid @RequestBody TemplateCreateRequest request,
//            HttpServletRequest httpRequest) {
//
//        try {
//            User user = getCurrentUser(httpRequest);
//            Template template = Template.builder()
//                    .name(request.getName())
//                    .description(request.getDescription())
//                    .isPublic(request.getIsPublic())
//                    .pdfFilePath(request.getPdfFilePath())
//                    .pdfImagePath(request.getPdfImagePath())
//                    .coordinateFields(request.getCoordinateFields())  // 추가
//                    .createdBy(user)
//                    .build();
//            template = templateService.savePdfTemplate(template);
//
//            log.info("템플릿 생성 성공: {} by {}", template.getName(), user.getEmail());
//            return ResponseEntity.status(HttpStatus.CREATED)
//                    .body(TemplateResponse.from(template));
//        } catch (Exception e) {
//            log.error("템플릿 생성 실패: {}", e.getMessage(), e);
//            return ResponseEntity.badRequest()
//                    .body(Map.of("error", e.getMessage()));
//        }
//    }

    @PostMapping("/upload-pdf")
    public ResponseEntity<?> uploadPdfTemplate(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String templateName,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "isPublic", defaultValue = "false") Boolean isPublic,
            @RequestParam(value = "coordinateFields", required = false) String coordinateFields,
            @RequestParam(value = "deadline", required = false) String deadline,
            @RequestParam(value = "defaultFolderId", required = false) String defaultFolderId,
            @RequestParam(value = "isMultiPage", defaultValue = "false") Boolean isMultiPage,
            @RequestParam(value = "totalPages", defaultValue = "1") Integer totalPages,
            @RequestParam(value = "pdfPagesData", required = false) String pdfPagesData,

            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);

            // PDF 파일 업로드 및 이미지 변환
            PdfService.PdfUploadResult uploadResult = pdfService.uploadPdfTemplate(file);

            // 기본 폴더 처리
            Folder defaultFolder = null;
            if (defaultFolderId != null && !defaultFolderId.trim().isEmpty()) {
                try {
                    UUID folderId = UUID.fromString(defaultFolderId);
                    defaultFolder = folderRepository.findById(folderId).orElse(null);
                    if (defaultFolder == null) {
                        log.warn("지정된 폴더를 찾을 수 없습니다: {}", defaultFolderId);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("잘못된 폴더 ID 형식: {}", defaultFolderId);
                }
            }

            // 만료일 파싱
            LocalDateTime deadlineDateTime = null;
            if (deadline != null && !deadline.trim().isEmpty()) {
                try {
                    deadlineDateTime = LocalDateTime.parse(deadline);
                } catch (Exception e) {
                    log.warn("잘못된 만료일 형식: {}", deadline, e);
                }
            }

            // PDF 기반 템플릿 생성
            Template template = Template.builder()
                    .name(templateName)
                    .description(description)
                    .isPublic(isPublic)
                    .pdfFilePath(uploadResult.getPdfFilePath())
                    .pdfImagePath(uploadResult.getPdfImagePaths().get(0)) // 첫 페이지 이미지 경로
                    .pdfImagePaths(uploadResult.getPdfImagePaths().toString())
                    .coordinateFields(coordinateFields)  // coordinateFields 추가
                    .deadline(deadlineDateTime)  // 만료일 추가
                    .defaultFolder(defaultFolder)  // 기본 폴더 추가
                    .isMultiPage(isMultiPage)
                    .totalPages(totalPages)
                    .pdfPagesData(pdfPagesData)
                    .createdBy(user)
                    .build();

            Template savedTemplate = templateService.savePdfTemplate(template);

            log.info("PDF 템플릿 생성 성공: {} by {}", savedTemplate.getName(), user.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of(
                            "template", TemplateResponse.from(savedTemplate),
                            "pdfImagePath", uploadResult.getPdfImagePaths().get(0),
                            "pdfImagePaths", uploadResult.getPdfImagePaths(),
                            "originalFilename", uploadResult.getOriginalFilename()
                    ));
        } catch (Exception e) {
            log.error("PDF 템플릿 생성 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<TemplateResponse>> getAllTemplates() {
        List<Template> templates = templateService.getAllTemplates();
        List<TemplateResponse> responses = templates.stream()
                .map(TemplateResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateResponse> getTemplate(@PathVariable Long id) {
        return templateService.getTemplateById(id)
                .map(template -> ResponseEntity.ok(TemplateResponse.from(template)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);

            // Content-Type 확인
            String contentType = httpRequest.getContentType();

            if (contentType != null && contentType.contains("multipart/form-data")) {
                // Multipart 요청 처리 (파일 포함)
                return handleMultipartUpdate(id, httpRequest, user);
            } else {
                // JSON 요청 처리 (메타데이터만)
                return handleJsonUpdate(id, httpRequest, user);
            }

        } catch (Exception e) {
            log.error("템플릿 수정 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> handleJsonUpdate(Long id, HttpServletRequest httpRequest, User user) throws Exception {
        // JSON 본문 읽기
        StringBuilder jsonBuilder = new StringBuilder();
        try (java.io.BufferedReader reader = httpRequest.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
        }

        // JSON 파싱
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(jsonBuilder.toString());

        // 기존 템플릿 조회
        Template existingTemplate = templateService.getTemplateById(id)
                .orElseThrow(() -> new RuntimeException("템플릿을 찾을 수 없습니다."));

        // 권한 확인 - 교직원이거나 템플릿 생성자인 경우 수정 가능
        if (!existingTemplate.getCreatedBy().getId().equals(user.getId()) 
                && user.getPosition() != Position.교직원) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "템플릿을 수정할 권한이 없습니다."));
        }

        // 기본 폴더 처리
        Folder defaultFolder = null;
        if (jsonNode.has("defaultFolderId") && !jsonNode.get("defaultFolderId").isNull()) {
            String defaultFolderId = jsonNode.get("defaultFolderId").asText();
            if (!defaultFolderId.trim().isEmpty()) {
                try {
                    UUID folderId = UUID.fromString(defaultFolderId);
                    defaultFolder = folderRepository.findById(folderId).orElse(null);
                    if (defaultFolder == null) {
                        log.warn("지정된 폴더를 찾을 수 없습니다: {}", defaultFolderId);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("잘못된 폴더 ID 형식: {}", defaultFolderId);
                }
            }
        }

        // 템플릿 정보 업데이트
        if (jsonNode.has("name")) {
            existingTemplate.setName(jsonNode.get("name").asText());
        }
        if (jsonNode.has("description")) {
            existingTemplate.setDescription(jsonNode.get("description").asText());
        }
        if (jsonNode.has("coordinateFields")) {
            existingTemplate.setCoordinateFields(jsonNode.get("coordinateFields").asText());
        }
        if (jsonNode.has("deadline")) {
            String deadlineStr = jsonNode.get("deadline").asText();
            if (deadlineStr != null && !deadlineStr.trim().isEmpty()) {
                try {
                    LocalDateTime deadlineDateTime = LocalDateTime.parse(deadlineStr);
                    existingTemplate.setDeadline(deadlineDateTime);
                } catch (Exception e) {
                    log.warn("잘못된 만료일 형식: {}", deadlineStr, e);
                }
            } else {
                existingTemplate.setDeadline(null);
            }
        }
        existingTemplate.setDefaultFolder(defaultFolder);

        Template updatedTemplate = templateService.savePdfTemplate(existingTemplate);

        log.info("템플릿 수정 성공 (JSON): {} by {}", updatedTemplate.getName(), user.getId());
        return ResponseEntity.ok(TemplateResponse.from(updatedTemplate));
    }

    private ResponseEntity<?> handleMultipartUpdate(Long id, HttpServletRequest httpRequest, User user)
            throws Exception {
        // Multipart 처리는 기존과 동일하게 유지
        log.info("Multipart 업데이트는 아직 구현되지 않음");
        return ResponseEntity.badRequest().body(Map.of("error", "Multipart 업데이트는 지원하지 않습니다."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            User user = getCurrentUser(httpRequest);
            templateService.deleteTemplate(id, user);

            log.info("템플릿 삭제 성공: {} by {}", id, user.getId());
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            log.error("템플릿 삭제 실패 - 참조 제약 조건 위반: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "이 템플릿으로 생성된 문서가 있어서 삭제할 수 없습니다. 관리자에게 문의하세요."));
        } catch (Exception e) {
            log.error("템플릿 삭제 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<?> duplicateTemplate(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String newName = request.get("name");
            String newDescription = request.get("description");
            String newFolderId = request.get("folderId");

            if (newName == null || newName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "템플릿 이름이 필요합니다."));
            }

            // 원본 템플릿 조회
            Template originalTemplate = templateService.getTemplateById(id)
                    .orElseThrow(() -> new RuntimeException("템플릿을 찾을 수 없습니다."));

            // 새 기본 폴더 처리
            Folder newDefaultFolder = null;
            if (newFolderId != null && !newFolderId.trim().isEmpty()) {
                try {
                    UUID folderId = UUID.fromString(newFolderId);
                    newDefaultFolder = folderRepository.findById(folderId).orElse(null);
                    if (newDefaultFolder == null) {
                        log.warn("지정된 폴더를 찾을 수 없습니다: {}", newFolderId);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("잘못된 폴더 ID 형식: {}", newFolderId);
                }
            }

            // 새 템플릿 생성 (복제)
            Template duplicatedTemplate = Template.builder()
                    .name(newName.trim())
                    .description(newDescription != null ? newDescription.trim() : originalTemplate.getDescription())
                    .isPublic(originalTemplate.getIsPublic())
                    .pdfFilePath(originalTemplate.getPdfFilePath()) // PDF 파일 경로 복사
                    .pdfImagePath(originalTemplate.getPdfImagePath()) // PDF 이미지 경로 복사
                    .coordinateFields(originalTemplate.getCoordinateFields()) // 좌표 필드 복사
                    .defaultFolder(newDefaultFolder != null ? newDefaultFolder
                            : originalTemplate.getDefaultFolder()) // 새 폴더 또는 원본 폴더
                    .createdBy(user) // 복제를 실행한 사용자로 설정
                    .build();

            Template savedTemplate = templateService.savePdfTemplate(duplicatedTemplate);

            log.info("템플릿 복제 성공: {} -> {} by {}", originalTemplate.getName(), savedTemplate.getName(), user.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(TemplateResponse.from(savedTemplate));

        } catch (Exception e) {
            log.error("템플릿 복제 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private User getCurrentUser(HttpServletRequest request) {
        try {
            // JWT 토큰에서 사용자 정보 추출 시도
            return authUtil.getCurrentUser(request);
        } catch (Exception e) {
            log.warn("JWT 토큰 추출 실패, 기본 사용자 사용: {}", e.getMessage());
            // 개발 환경용 fallback: 기본 사용자 사용
            return getUserOrCreate("test@example.com", "Test User", "1234");
        }
    }

    private User getUserOrCreate(String email, String defaultName, String defaultPassword) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .name(defaultName)
                            .email(email)
                            .position(Position.교직원)
                            .role(Role.USER)
                            .build();
                    return userRepository.save(newUser);
                });
    }
} 