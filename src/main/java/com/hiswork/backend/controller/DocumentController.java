package com.hiswork.backend.controller;

import com.hiswork.backend.domain.Document;
import com.hiswork.backend.domain.Position;
import com.hiswork.backend.domain.User;
import com.hiswork.backend.dto.BulkCommitRequest;
import com.hiswork.backend.dto.BulkCommitResponse;
import com.hiswork.backend.dto.DocumentCreateRequest;
import com.hiswork.backend.dto.DocumentResponse;
import com.hiswork.backend.dto.DocumentUpdateRequest;
import com.hiswork.backend.dto.MailRequest;
import com.hiswork.backend.repository.UserRepository;
import com.hiswork.backend.service.BulkDocumentService;
import com.hiswork.backend.service.DocumentService;
import com.hiswork.backend.service.ExcelParsingService;
import com.hiswork.backend.service.MailService;
import com.hiswork.backend.service.PdfService;
import com.hiswork.backend.service.SigningTokenService;
import com.hiswork.backend.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final AuthUtil authUtil;
    private final PdfService pdfService;
    private final ExcelParsingService excelParsingService;
    private final BulkDocumentService bulkDocumentService;
    private final MailService mailService;
    private final SigningTokenService signingTokenService;

    @PostMapping
    public ResponseEntity<?> createDocument(
            @Valid @RequestBody DocumentCreateRequest request,
            HttpServletRequest httpRequest) {

        log.info("Document creation request: {}", request);

        try {
            User creator = getCurrentUser(httpRequest);
            log.info("Creator user: {}", creator.getId());

            // 스테이징 ID가 있으면 대량 문서 생성 (엑셀 업로드 후)
            if (request.getStagingId() != null && !request.getStagingId().trim().isEmpty()) {
                log.info("스테이징 ID 발견, 대량 문서 생성 실행: {}", request.getStagingId());
                log.info("요청자 정보 - ID: {}, 이메일: {}, deadline: {}", creator.getId(), creator.getEmail(),
                        request.getDeadline());

                BulkCommitRequest bulkRequest = new BulkCommitRequest();
                bulkRequest.setStagingId(request.getStagingId());
                bulkRequest.setOnDuplicate(BulkCommitRequest.OnDuplicateAction.SKIP); // 기본값
                bulkRequest.setDeadline(request.getDeadline()); // deadline 설정

                BulkCommitResponse bulkResponse = bulkDocumentService.commitBulkCreation(bulkRequest, creator);

                log.info("대량 문서 생성 완료 - 생성: {}, 건너뜀: {}, 실패: {}",
                        bulkResponse.getCreated(), bulkResponse.getSkipped(), bulkResponse.getFailed());

                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(bulkResponse);
            }
            // 기존 단일 문서 생성
            else {
                log.info("단일 문서 생성 실행");

                Document document = documentService.createDocument(
                        request.getTemplateId(),
                        creator,
                        request.getEditorEmail(),
                        request.getTitle(),
                        request.getDeadline()
                );

                log.info("Document created successfully with ID: {}", document.getId());
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(DocumentResponse.from(document));
            }

        } catch (Exception e) {
            log.error("Error creating document", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getAllDocuments(HttpServletRequest httpRequest) {
        try {
            User currentUser = getCurrentUser(httpRequest);
            List<Document> documents = documentService.getDocumentsByUser(currentUser);
            List<DocumentResponse> responses = documents.stream()
                    .map(document -> documentService.getDocumentResponse(document.getId()))
                    .filter(response -> response != null)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("Error getting all documents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // 처리 해야 할 문서 리스트 조회 
    @GetMapping("/todo")
    public ResponseEntity<List<DocumentResponse>> getTodoDocuments(HttpServletRequest httpRequest) {
        try {
            User currentUser = getCurrentUser(httpRequest);
            List<Document> documents = documentService.getTodoDocumentsByUser(currentUser);
            List<DocumentResponse> responses = documents.stream()
                    .map(document -> documentService.getDocumentResponse(document.getId()))
                    .filter(response -> response != null)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(responses);
        } catch (Exception e) {
            log.error("Error getting todo documents", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocument(@PathVariable Long id) {
        try {
            DocumentResponse response = documentService.getDocumentResponse(id);
            if (response != null) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Error getting document {}", id, e);
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateDocument(
            @PathVariable Long id,
            @Valid @RequestBody DocumentUpdateRequest request,
            HttpServletRequest httpRequest) {

        try {
            log.info("Updating document {} with data: {}", id, request.getData());

            User user = getCurrentUser(httpRequest);
            Document document = documentService.updateDocumentData(id, request, user);

            log.info("Document updated successfully: {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error updating document {}", id, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 문서 만료일 업데이트 - 생성자만 가능
     */
    @PutMapping("/{id}/deadline")
    public ResponseEntity<?> updateDocumentDeadline(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String deadlineStr = request.get("deadline");
            
            LocalDateTime deadline = null;
            if (deadlineStr != null && !deadlineStr.isEmpty()) {
                // ISO 8601 형식 (UTC 타임존 포함) 파싱
                // 예: "2025-11-15T07:08:00.000Z"
                try {
                    // ZonedDateTime으로 파싱 후 LocalDateTime으로 변환
                    java.time.ZonedDateTime zonedDateTime = java.time.ZonedDateTime.parse(deadlineStr);
                    deadline = zonedDateTime.toLocalDateTime();
                } catch (Exception e) {
                    // ISO Local DateTime 형식으로 재시도
                    deadline = LocalDateTime.parse(deadlineStr);
                }
            }
            
            log.info("문서 만료일 업데이트 요청 - 문서 ID: {}, 사용자: {}, 만료일: {}", 
                    id, user.getEmail(), deadline);
            
            Document document = documentService.updateDocumentDeadline(id, deadline, user);
            
            log.info("문서 만료일 업데이트 성공 - 문서 ID: {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("문서 만료일 업데이트 실패 - 문서 ID: {}, 오류: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/assign-editor")
    public ResponseEntity<?> assignEditor(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            String editorEmail = request.get("editorEmail");
            User user = getCurrentUser(httpRequest);

            Document document = documentService.assignEditor(id, editorEmail, user);
            log.info("Editor assigned successfully to document {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error assigning editor to document {}", id, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 검토자 지정
     */
    @PostMapping("/{id}/assign-reviewer")
    public ResponseEntity<?> assignReviewer(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            String reviewerEmail = request.get("reviewerEmail");
            User user = getCurrentUser(httpRequest);

            log.info("검토자 할당 요청 - 문서 ID: {}, 검토자: {}, 요청자: {}",
                    id, reviewerEmail, user.getEmail());

            Document document = documentService.assignReviewer(id, reviewerEmail, user);
            log.info("Reviewer assigned successfully to document {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error assigning reviewer to document {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 서명자 지정
     */
    @PostMapping("/{id}/assign-signer")
    public ResponseEntity<?> assignSigner(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            String signerEmail = request.get("signerEmail");
            User user = getCurrentUser(httpRequest);

            log.info("서명자 할당 요청 - 문서 ID: {}, 서명자: {}, 요청자: {}",
                    id, signerEmail, user.getEmail());

            Document document = documentService.assignSigner(id, signerEmail, user);
            log.info("Signer assigned successfully to document {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error assigning signer to document {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 서명자 일괄 지정
     */
    @PostMapping("/{id}/assign-signers-batch")
    public ResponseEntity<?> assignSignersBatch(
            @PathVariable Long id,
            @RequestBody Map<String, List<String>> request,
            HttpServletRequest httpRequest) {

        try {
            List<String> signerEmails = request.get("signerEmails");
            User user = getCurrentUser(httpRequest);

            log.info("서명자 일괄 할당 요청 - 문서 ID: {}, 서명자 수: {}, 요청자: {}",
                    id, signerEmails != null ? signerEmails.size() : 0, user.getEmail());

            if (signerEmails == null || signerEmails.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "서명자 이메일 목록이 비어있습니다."));
            }

            Document document = documentService.assignSignersBatch(id, signerEmails, user);
            log.info("Signers assigned successfully to document {} - count: {}", id, signerEmails.size());
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error assigning signers to document {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 검토자 제거
     */
    @DeleteMapping("/{id}/remove-reviewer")
    public ResponseEntity<?> removeReviewer(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            String reviewerEmail = request.get("reviewerEmail");
            User user = getCurrentUser(httpRequest);

            log.info("검토자 제거 요청 - 문서 ID: {}, 검토자: {}, 요청자: {}",
                    id, reviewerEmail, user.getEmail());

            Document document = documentService.removeReviewer(id, reviewerEmail, user);
            log.info("Reviewer removed successfully from document {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error removing reviewer from document {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 서명자 제거
     */
    @DeleteMapping("/{id}/remove-signer")
    public ResponseEntity<?> removeSigner(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        try {
            String signerEmail = request.get("signerEmail");
            User user = getCurrentUser(httpRequest);

            log.info("서명자 제거 요청 - 문서 ID: {}, 서명자: {}, 요청자: {}",
                    id, signerEmail, user.getEmail());

            Document document = documentService.removeSigner(id, signerEmail, user);
            log.info("Signer removed successfully from document {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error removing signer from document {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/submit-for-review")
    public ResponseEntity<?> submitForReview(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            Document document = documentService.submitForReview(id, user);
            log.info("Document submitted for review successfully: {}", id);
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("Error submitting document for review {}", id, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/download-pdf")
    public ResponseEntity<?> downloadPdf(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            User user = getCurrentUser(httpRequest);

            // 문서 조회
            Document document = documentService.getDocumentById(id)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            // PDF 기반 템플릿인지 확인 (pdfFilePath가 있는지로 판단)
            if (document.getTemplate().getPdfFilePath() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "PDF 다운로드는 PDF 기반 템플릿만 지원됩니다."));
            }

            // PDF 생성
            String completedPdfPath = pdfService.generateCompletedPdf(
                    document.getTemplate().getPdfFilePath(),
                    null, // coordinateFields는 더 이상 사용하지 않음
                    document.getData(),
                    document.getTemplate().getName()
            );

            log.info("PDF 다운로드 요청 - 문서 ID: {}, 상태: {}", id, document.getStatus());
            log.info("템플릿 파일 경로: {}", document.getTemplate().getPdfFilePath());
            log.info("문서 데이터: {}", document.getData());

            // 생성된 PDF 파일을 바이트 배열로 읽기
            byte[] pdfBytes = Files.readAllBytes(Paths.get(completedPdfPath));

            // 파일명 설정 (한글 파일명 지원)
            String filename = document.getTemplate().getName() + "_완성본.pdf";
            String encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
                    .replaceAll("\\+", "%20");

            // PDF 파일 반환
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFilename)
                    .body(pdfBytes);

        } catch (Exception e) {
            log.error("PDF 다운로드 실패: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{documentId}/start-editing")
    public ResponseEntity<?> startEditing(
            @PathVariable Long documentId,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            log.info("편집 시작 요청 - 문서 ID: {}, 사용자: {}", documentId, user.getId());

            Document document = documentService.startEditing(documentId, user);

            log.info("편집 시작 성공 - 문서 ID: {}, 상태: {}", documentId, document.getStatus());
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("편집 시작 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{documentId}/complete-editing")
    public ResponseEntity<?> completeEditing(
            @PathVariable Long documentId,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            log.info("편집 완료 요청 - 문서 ID: {}, 사용자: {}", documentId, user.getId());

            // 문서 존재 확인
            Document document = documentService.getDocumentById(documentId)
                    .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다"));
            log.info("문서 상태 확인 - 현재 상태: {}", document.getStatus());

            Document updatedDocument = documentService.completeEditing(documentId, user);

            log.info("편집 완료 성공 - 문서 ID: {}, 새 상태: {}", documentId, updatedDocument.getStatus());
            return ResponseEntity.ok(DocumentResponse.from(updatedDocument));
        } catch (Exception e) {
            log.error("편집 완료 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 검토자 지정 완료 및 검토 단계로 이동
     */
    @PostMapping("/{documentId}/complete-reviewer-assignment")
    public ResponseEntity<?> completeReviewerAssignment(
            @PathVariable Long documentId,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            boolean skipReview = false;
            
            if (requestBody != null && requestBody.containsKey("skipReview")) {
                skipReview = (Boolean) requestBody.get("skipReview");
            }
            
            log.info("검토자 지정 완료 요청 - 문서 ID: {}, 사용자: {}, 검토 건너뛰기: {}", 
                    documentId, user.getId(), skipReview);

            Document document = documentService.completeReviewerAssignment(documentId, user, skipReview);

            log.info("검토자 지정 완료 성공 - 문서 ID: {}, 새 상태: {}", documentId, document.getStatus());
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("검토자 지정 완료 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 서명자 지정 완료 및 검토 단계로 이동
     * - 템플릿 생성자를 자동으로 검토자로 지정
     * - 템플릿 생성자에게 검토 알림 발송
     * - 문서 상태: READY_FOR_REVIEW → REVIEWING
     */
    @PostMapping("/{documentId}/complete-signer-assignment")
    public ResponseEntity<?> completeSignerAssignment(
            @PathVariable Long documentId,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            log.info("서명자 지정 완료 요청 - 문서 ID: {}, 사용자: {}", documentId, user.getId());

            Document document = documentService.completeSignerAssignment(documentId, user);

            log.info("서명자 지정 완료 성공 - 문서 ID: {}, 새 상태: {}, 검토자: 템플릿 생성자 자동 지정", 
                    documentId, document.getStatus());
            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("서명자 지정 완료 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 검토 승인 (REVIEWING -> SIGNING 또는 서명자 지정 대기)
     */
    @PostMapping("/{documentId}/review/approve")
    public ResponseEntity<DocumentResponse> approveReview(
            @PathVariable Long documentId,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String comment = requestBody != null ? (String) requestBody.get("comment") : null;

            log.info("검토 승인 요청 - 문서 ID: {}, 검토자: {}", documentId, user.getEmail());
            Document document = documentService.approveReview(documentId, user, comment);

            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("검토 승인 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(DocumentResponse.builder().build());
        }
    }

    /**
     * 검토 반려 (REVIEWING -> EDITING)
     */
    @PostMapping("/{documentId}/review/reject")
    public ResponseEntity<DocumentResponse> rejectReview(
            @PathVariable Long documentId,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String reason = (String) requestBody.get("reason");

            log.info("검토 반려 요청 - 문서 ID: {}, 검토자: {}", documentId, user.getEmail());
            Document document = documentService.rejectReview(documentId, user, reason);

            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("검토 반려 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(DocumentResponse.builder().build());
        }
    }
    
    /**
     * 서명 승인 (SIGNING -> COMPLETED 또는 다른 서명자 대기)
     */
    @PostMapping("/{documentId}/approve")
    public ResponseEntity<DocumentResponse> approveDocument(
            @PathVariable Long documentId,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String signatureData = (String) requestBody.get("signatureData");

            log.info("서명 승인 요청 - 문서 ID: {}, 서명자: {}", documentId, user.getEmail());
            Document document = documentService.approveDocument(documentId, user, signatureData);

            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("서명 승인 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(DocumentResponse.builder().build());
        }
    }

    /**
     * 서명 반려 (SIGNING -> EDITING)
     */
    @PostMapping("/{documentId}/reject")
    public ResponseEntity<DocumentResponse> rejectDocument(
            @PathVariable Long documentId,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest httpRequest) {

        try {
            User user = getCurrentUser(httpRequest);
            String reason = (String) requestBody.get("reason");

            log.info("서명 반려 요청 - 문서 ID: {}, 서명자: {}", documentId, user.getEmail());
            Document document = documentService.rejectDocument(documentId, user, reason);

            return ResponseEntity.ok(DocumentResponse.from(document));
        } catch (Exception e) {
            log.error("서명 반려 실패 - 문서 ID: {}, 오류: {}", documentId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(DocumentResponse.builder().build());
        }
    }

    /**
     * 검토 권한 확인
     */
    @GetMapping("/{documentId}/can-review")
    public ResponseEntity<Boolean> canReview(@PathVariable Long documentId, HttpServletRequest httpRequest) {
        try {
            User user = getCurrentUser(httpRequest);
            boolean canReview = documentService.canReview(documentId, user);
            return ResponseEntity.ok(canReview);
        } catch (Exception e) {
            log.error("Error checking review permission for document {}", documentId, e);
            return ResponseEntity.ok(false);
        }
    }
    
    /**
     * 서명 권한 확인
     */
    @GetMapping("/{documentId}/can-sign")
    public ResponseEntity<Boolean> canSign(@PathVariable Long documentId, HttpServletRequest httpRequest) {
        try {
            User user = getCurrentUser(httpRequest);
            boolean canSign = documentService.canSign(documentId, user);
            return ResponseEntity.ok(canSign);
        } catch (Exception e) {
            log.error("Error checking sign permission for document {}", documentId, e);
            return ResponseEntity.ok(false);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            User user = getCurrentUser(httpRequest);
            log.info("🗑️ 문서 삭제 API 호출 - 문서 ID: {}, 사용자: {}", id, user.getEmail());

            documentService.deleteDocument(id, user);

            log.info("✅ 문서 삭제 성공 - 문서 ID: {}, 사용자: {}", id, user.getEmail());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("❌ 문서 삭제 실패 - 문서 ID: {}, 오류: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private User getCurrentUser(HttpServletRequest request) {
        try {
            log.info("=== JWT 토큰 추출 시작 ===");

            // 모든 헤더 로깅 (디버깅용)
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                String headerValue = request.getHeader(headerName);
                log.info("Header - {}: {}", headerName, headerValue);
            }

            // Authorization 헤더 확인
            String authHeader = request.getHeader("Authorization");
            log.info("Authorization 헤더: {}", authHeader);

            if (authHeader == null) {
                log.warn("Authorization 헤더가 없습니다");
                throw new RuntimeException("Authorization 헤더가 없습니다");
            }

            if (!authHeader.startsWith("Bearer ")) {
                log.warn("Bearer 토큰 형식이 아닙니다: {}", authHeader);
                throw new RuntimeException("Bearer 토큰 형식이 아닙니다");
            }

            // JWT 토큰에서 사용자 정보 추출 시도
            User user = authUtil.getCurrentUser(request);
//            log.info("JWT 토큰에서 추출된 사용자: {} ({})", user.getName(), user.getId());
            log.info("JWT 토큰에서 추출된 사용자: {} ({})", user.getName(), user.getEmail());
            return user;
        } catch (Exception e) {
            log.error("JWT 토큰 추출 실패: {}", e.getMessage(), e);
            log.warn("JWT 토큰 추출 실패, 인증이 필요합니다: {}", e.getMessage());
            // 인증이 필요한 상황에서는 예외를 던져서 클라이언트가 로그인하도록 유도
            throw new RuntimeException("인증이 필요합니다. 로그인 후 다시 시도해주세요.");
        }
    }

    /**
     * 문서 조회 표시 API - 사용자가 문서를 조회했음을 표시
     */
    @PostMapping("/{id}/view")
    public ResponseEntity<?> markDocumentAsViewed(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {

        log.info("문서 조회 표시 요청 - DocumentId: {}", id);

        try {
            User currentUser = getCurrentUser(httpRequest);
            documentService.markDocumentAsViewed(id, currentUser);

            return ResponseEntity.ok()
                    .body(Map.of("success", true, "message", "문서 조회가 표시되었습니다."));

        } catch (Exception e) {
            log.error("문서 조회 표시 실패 - DocumentId: {}", id, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "문서 조회 표시 실패: " + e.getMessage()));
        }
    }

    /**
     * 템플릿 ID로 문서 조회 API
     * - 현재 사용자가 EDITOR로 할당된 문서만 반환
     * - 서명자 정보와 서명 데이터는 제외
     */
    @GetMapping("/by-template/{templateId}")
    public ResponseEntity<?> getDocumentsByTemplateId(
            @PathVariable Long templateId,
            HttpServletRequest httpRequest) {

        log.info("템플릿 ID로 문서 조회 요청 - 템플릿 ID: {}", templateId);

        try {
            User currentUser = getCurrentUser(httpRequest);
            List<DocumentResponse> documents = documentService.getDocumentsByTemplateId(templateId, currentUser);

            log.info("템플릿 ID {}로 {}개의 문서 조회 완료 - 사용자: {}", 
                    templateId, documents.size(), currentUser.getEmail());

            return ResponseEntity.ok(documents);
        } catch (Exception e) {
            log.error("템플릿 ID로 문서 조회 실패 - 템플릿 ID: {}, 오류: {}", templateId, e.getMessage(), e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 관리자가 작업자에게 메일 전송 API
     * - 서명자(SIGNER)인 경우: anonymous token이 포함된 서명 요청 이메일 발송
     * - 그 외(EDITOR, REVIEWER): 일반 관리자 메시지 발송
     */
    @PostMapping("/{id}/send-message")
    public ResponseEntity<?> sendMessageToWorker(
            @PathVariable Long id,
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {

        String recipientRole = request.get("recipientRole");
        log.info("관리자 메시지 전송 요청 - DocumentId: {}, RecipientEmail: {}, Role: {}",
                id, request.get("recipientEmail"), recipientRole);

        try {
            User currentUser = getCurrentUser(httpRequest);

            log.info("현재 사용자 정보 - ID: {}, 이메일: {}, Position: {}",
                    currentUser.getId(),
                    currentUser.getEmail(),
                    currentUser.getPosition());

            // 관리자 권한 체크 - position이 교직원인 경우
            boolean isAdmin = currentUser.getPosition() == Position.교직원;

            log.info("관리자 여부 체크: isAdmin={}", isAdmin);

            if (!isAdmin) {
                log.warn("권한 없음 - Position: {}", currentUser.getPosition());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "관리자만 메시지를 전송할 수 있습니다."));
            }

            // 문서 정보 가져오기
            Document document = documentService.getDocumentById(id)
                    .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다."));

            String recipientEmail = request.get("recipientEmail");
            String recipientName = request.get("recipientName");
            String message = request.get("message");

            if (recipientEmail == null || recipientEmail.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "받는 사람 이메일이 필요합니다."));
            }

            String documentTitle = document.getTitle() != null ? document.getTitle() : document.getTemplate().getName();

            // 서명자(SIGNER)인 경우: 토큰 기반 서명 요청 이메일 발송
            if ("SIGNER".equals(recipientRole)) {
                log.info("서명자에게 토큰 기반 서명 요청 이메일 발송 - 문서: {}, 서명자: {}", id, recipientEmail);

                // SigningTokenService를 통해 토큰 생성 및 이메일 발송
                signingTokenService.createAndSendToken(
                    document.getId(),
                    recipientEmail,
                    recipientName != null ? recipientName : recipientEmail,
                    documentTitle
                );

                log.info("서명자 토큰 이메일 전송 성공 - From: {}, To: {}", currentUser.getEmail(), recipientEmail);

                return ResponseEntity.ok()
                        .body(Map.of("success", true, "message", "서명 요청 이메일이 성공적으로 전송되었습니다."));
            }

            // 그 외(EDITOR, REVIEWER): 일반 관리자 메시지 발송
            if (message == null || message.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "메시지 내용이 필요합니다."));
            }

            // 받는 사람 정보 찾기
            User recipient = userRepository.findByEmail(recipientEmail)
                    .orElseThrow(() -> new RuntimeException("받는 사람을 찾을 수 없습니다."));

            // 메일 전송
            MailRequest.AdminMessageEmailCommand mailCommand = MailRequest.AdminMessageEmailCommand.builder()
                    .recipientEmail(recipient.getEmail())
                    .recipientName(recipient.getName())
                    .senderName(currentUser.getName())
                    .message(message)
                    .documentTitle(documentTitle)
                    .documentId(document.getId())
                    .build();

            mailService.sendAdminMessageToWorker(mailCommand);

            log.info("관리자 메시지 전송 성공 - From: {}, To: {}", currentUser.getEmail(), recipientEmail);

            return ResponseEntity.ok()
                    .body(Map.of("success", true, "message", "메시지가 성공적으로 전송되었습니다."));

        } catch (Exception e) {
            log.error("관리자 메시지 전송 실패 - DocumentId: {}", id, e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "메시지 전송 실패: " + e.getMessage()));
        }
    }
} 