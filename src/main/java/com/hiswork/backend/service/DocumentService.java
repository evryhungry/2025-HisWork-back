package com.hiswork.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hiswork.backend.domain.Document;
import com.hiswork.backend.domain.DocumentRole;
import com.hiswork.backend.domain.DocumentStatusLog;
import com.hiswork.backend.domain.NotificationType;
import com.hiswork.backend.domain.SigningToken;
import com.hiswork.backend.domain.Template;
import com.hiswork.backend.domain.User;
import com.hiswork.backend.dto.DocumentResponse;
import com.hiswork.backend.dto.DocumentStatusLogResponse;
import com.hiswork.backend.dto.DocumentUpdateRequest;
import com.hiswork.backend.dto.MailRequest;
import com.hiswork.backend.repository.DocumentRepository;
import com.hiswork.backend.repository.DocumentRoleRepository;
import com.hiswork.backend.repository.DocumentStatusLogRepository;
import com.hiswork.backend.repository.SigningTokenRepository;
import com.hiswork.backend.repository.TemplateRepository;
import com.hiswork.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import com.hiswork.backend.domain.Position;
import com.hiswork.backend.domain.Role;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@RequiredArgsConstructor
@Transactional
public class DocumentService {
    
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final MailService mailService;
    private final DocumentRepository documentRepository;
    private final TemplateRepository templateRepository;
    private final DocumentRoleRepository documentRoleRepository;
    private final DocumentStatusLogRepository documentStatusLogRepository;
    private final SigningTokenRepository signingTokenRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final SigningTokenService signingTokenService;

    public Document createDocument(Long templateId, User creator, String editorEmail, String title, LocalDateTime deadline) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        
        ObjectNode initialData = initializeDocumentData(template);
        
        // deadline이 제공되면 사용하고, 없으면 템플릿의 deadline 사용
        LocalDateTime finalDeadline = deadline != null ? deadline : template.getDeadline();
        
        Document document = Document.builder()
                .template(template)
                .title(title)
                .data(initialData)
                .status(Document.DocumentStatus.DRAFT)
                .deadline(finalDeadline)  // 요청된 마감일 또는 템플릿의 만료일
                .folder(template.getDefaultFolder())  // 템플릿의 기본 폴더 적용
                .build();
        
        document = documentRepository.save(document);
        
        // 생성자 역할 할당
        DocumentRole creatorRole = DocumentRole.builder()
                .document(document)
                .assignedUserId(creator.getId())
                .taskRole(DocumentRole.TaskRole.CREATOR)
                .build();
        
        documentRoleRepository.save(creatorRole);

        User editor = null;
        
        // 편집자가 지정된 경우 편집자 역할 할당
        if (editorEmail != null && !editorEmail.trim().isEmpty()) {
            editor = getUserOrCreate(editorEmail, "Editor User");
            
            DocumentRole editorRole = DocumentRole.builder()
                    .document(document)
                    .assignedUserId(editor.getId())
                    .taskRole(DocumentRole.TaskRole.EDITOR)
                    .build();
            
            documentRoleRepository.save(editorRole);
            
            // 편집자가 생성자와 다른 경우에만 알림 생성
            if (!editor.getId().equals(creator.getId())) {
                createDocumentAssignmentNotification(editor, document, DocumentRole.TaskRole.EDITOR);
            }
            
            // 문서 상태를 EDITING으로 변경
            changeDocumentStatus(document, Document.DocumentStatus.EDITING, editor, "편집자 할당으로 인한 상태 변경");
            document = documentRepository.save(document);
        }

        // 편집자가 지정되었고 생성자와 다른 경우에만 메일 전송
        if (editorEmail != null && !editorEmail.trim().isEmpty() && !editor.getId().equals(creator.getId())) {
            mailService.sendAssignEditorNotification(MailRequest.EditorAssignmentEmailCommand.builder()
                            .documentId(document.getId())
                            .documentTitle(document.getTitle())
                            .creatorName(creator.getName())
                            .editorEmail(editorEmail)
                            .editorName(editor.getName())
                            .dueDate(document.getDeadline() != null ? document.getDeadline().atZone(java.time.ZoneId.systemDefault()) : null)
                    .build());
        }

        return document;
    }
    
    private ObjectNode initializeDocumentData(Template template) {
        ObjectNode data = objectMapper.createObjectNode();
        
        // 템플릿에서 coordinateFields 복사 (레거시 지원용)
        if (template.getCoordinateFields() != null && !template.getCoordinateFields().trim().isEmpty()) {
            try {
                JsonNode coordinateFieldsJson = objectMapper.readTree(template.getCoordinateFields());
                if (coordinateFieldsJson.isArray()) {
                    // coordinateFields를 값만 빈 상태로 복사
                    ArrayNode fieldsArray = objectMapper.createArrayNode();
                    for (JsonNode field : coordinateFieldsJson) {
                        ObjectNode fieldCopy = field.deepCopy();
                        fieldCopy.put("value", ""); // 값은 빈 문자열로 초기화
                        fieldsArray.add(fieldCopy);
                    }
                    data.set("coordinateFields", fieldsArray);
                    log.info("문서 생성 시 템플릿의 coordinateFields 복사: {} 개 필드", fieldsArray.size());
                }
            } catch (Exception e) {
                log.warn("템플릿 coordinateFields 파싱 실패: {}", e.getMessage());
            }
        }
        
        return data;
    }
    
    public Document updateDocumentData(Long documentId, DocumentUpdateRequest request, User user) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 권한 확인 - 편집자만 수정 가능 (생성자는 편집 불가)
        if (!isEditor(document, user)) {
            throw new RuntimeException("문서를 수정할 권한이 없습니다");
        }
        
        // 문서 데이터 업데이트
        document.setData(request.getData());
        
        // deadline 업데이트 (있을 경우)
        if (request.getDeadline() != null) {
            document.setDeadline(request.getDeadline());
            log.info("문서 만료일 업데이트 - 문서 ID: {}, 새 만료일: {}", documentId, request.getDeadline());
        }
        
        document = documentRepository.save(document);

        return document;
    }
    
    /**
     * 문서 만료일 업데이트 - 폴더 접근 권한이 있는 사용자만 가능
     */
    public Document updateDocumentDeadline(Long documentId, LocalDateTime deadline, User user) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 권한 확인 - 폴더 접근 권한이 있는 사용자만 만료일 수정 가능
        if (!user.canAccessFolders()) {
            throw new RuntimeException("문서 만료일을 수정할 권한이 없습니다");
        }
        
        // 만료일 업데이트
        document.setDeadline(deadline);
        document = documentRepository.save(document);
        
        log.info("문서 만료일 업데이트 - 문서 ID: {}, 사용자: {}, 새 만료일: {}", 
                documentId, user.getEmail(), deadline);
        
        return document;
    }
    
    public Document startEditing(Long documentId, User user) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 편집자만 편집 시작 가능
        if (!isEditor(document, user)) {
            throw new RuntimeException("편집할 권한이 없습니다");
        }
        
        // 문서가 DRAFT 상태인 경우만 EDITING으로 변경
        if (document.getStatus() == Document.DocumentStatus.DRAFT) {
            changeDocumentStatus(document, Document.DocumentStatus.EDITING, user, "문서 편집 시작");
            document = documentRepository.save(document);
            
            log.info("문서 편집 시작 - 문서 ID: {}, 사용자: {}, 상태: {} -> EDITING", 
                    documentId, user.getId(), "DRAFT");
        }
        
        return document;
    }
    
    public Document submitForReview(Long documentId, User user) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 편집자 또는 생성자만 검토 요청 가능
        if (!isEditor(document, user) && !isCreator(document, user)) {
            throw new RuntimeException("검토 요청할 권한이 없습니다");
        }
        
        // 현재 상태가 EDITING이어야 함
        if (document.getStatus() != Document.DocumentStatus.EDITING) {
            throw new RuntimeException("문서가 편집 상태가 아닙니다");
        }
        
        // 상태를 READY_FOR_REVIEW로 변경
        changeDocumentStatus(document, Document.DocumentStatus.READY_FOR_REVIEW, user, "검토 요청");
        document = documentRepository.save(document);
        
        return document;
    }
    
    public Document assignEditor(Long documentId, String editorEmail, User assignedBy) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        User editor = getUserOrCreate(editorEmail, "Editor User");
        
        // 기존 편집자 역할이 있다면 제거
        documentRoleRepository.findByDocumentAndRole(documentId, DocumentRole.TaskRole.EDITOR)
                .ifPresent(existingRole -> documentRoleRepository.delete(existingRole));
        
        // 새로운 편집자 역할 할당
        DocumentRole editorRole = DocumentRole.builder()
                .document(document)
                .assignedUserId(editor.getId())
                .taskRole(DocumentRole.TaskRole.EDITOR)
                .build();
        
        documentRoleRepository.save(editorRole);
        
        // 편집자가 할당자와 다른 경우에만 알림 생성
        if (!editor.getId().equals(assignedBy.getId())) {
            createDocumentAssignmentNotification(editor, document, DocumentRole.TaskRole.EDITOR);
        }
        
        // 문서 상태를 EDITING으로 변경
        changeDocumentStatus(document, Document.DocumentStatus.EDITING, editor, "편집자 재할당");
        document = documentRepository.save(document);
        
        return document;
    }
    
    /**
     * 검토자 지정
     */
    public Document assignReviewer(Long documentId, String reviewerEmail, User assignedBy) {
        log.info("검토자 할당 요청 - 문서 ID: {}, 검토자 이메일: {}, 요청자: {}", 
                documentId, reviewerEmail, assignedBy.getId());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        log.info("문서 정보 - ID: {}, 상태: {}, 생성자: {}", 
                document.getId(), document.getStatus(), document.getTemplate().getCreatedBy().getId());
        
        // 검토자 할당 권한 확인 - 생성자 또는 편집자 가능
        boolean isCreator = isCreator(document, assignedBy);
        boolean isEditor = isEditor(document, assignedBy);
        
        log.info("권한 확인 - 요청자: {}, 생성자 여부: {}, 편집자 여부: {}", 
                assignedBy.getId(), isCreator, isEditor);
        
        if (!isCreator && !isEditor) {
            throw new RuntimeException("검토자를 할당할 권한이 없습니다. 생성자 또는 편집자만 가능합니다.");
        }
        
        User reviewer = getUserOrCreate(reviewerEmail, "Reviewer User");
        
        // 이미 동일한 검토자가 있는지 확인
        boolean isAlreadyAssigned = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.REVIEWER)
                .stream()
                .anyMatch(role -> role.getAssignedUserId().equals(reviewer.getId()));
        
        if (isAlreadyAssigned) {
            log.warn("이미 지정된 검토자입니다 - 문서 ID: {}, 검토자 ID: {}", documentId, reviewer.getId());
            throw new RuntimeException("이미 지정된 검토자입니다.");
        }
        
        // 새로운 검토자 역할 할당 (기존 검토자 제거하지 않음)
        DocumentRole reviewerRole = DocumentRole.builder()
                .document(document)
                .assignedUserId(reviewer.getId())
                .taskRole(DocumentRole.TaskRole.REVIEWER)
                .build();
        
        documentRoleRepository.save(reviewerRole);

        // 검토자에게 알림 생성
        createDocumentAssignmentNotification(reviewer, document, DocumentRole.TaskRole.REVIEWER);

        // 검토자 지정만 하고 상태는 READY_FOR_REVIEW 유지
        documentRepository.save(document);
        
        return document;
    }
    
    /**
     * 서명자 지정 (기존 assignReviewer 로직 재사용)
     */
    public Document assignSigner(Long documentId, String signerEmail, User assignedBy) {
        log.info("서명자 할당 요청 - 문서 ID: {}, 서명자 이메일: {}, 요청자: {}", 
                documentId, signerEmail, assignedBy.getId());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        log.info("문서 정보 - ID: {}, 상태: {}", 
                document.getId(), document.getStatus());
        
        // 서명자 할당 권한 확인 - 생성자 또는 편집자 가능
        boolean isCreator = isCreator(document, assignedBy);
        boolean isEditor = isEditor(document, assignedBy);
        
        log.info("권한 확인 - 요청자: {}, 생성자 여부: {}, 편집자 여부: {}", 
                assignedBy.getId(), isCreator, isEditor);
        
        if (!isCreator && !isEditor) {
            throw new RuntimeException("서명자를 할당할 권한이 없습니다. 생성자 또는 편집자만 가능합니다.");
        }
        
        User signer = getUserOrCreate(signerEmail, "Signer User");
        
        // 이미 동일한 서명자가 있는지 확인
        boolean isAlreadyAssigned = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.SIGNER)
                .stream()
                .anyMatch(role -> role.getAssignedUserId().equals(signer.getId()));
        
        if (isAlreadyAssigned) {
            log.warn("이미 지정된 서명자입니다 - 문서 ID: {}, 서명자 ID: {}", documentId, signer.getId());
            throw new RuntimeException("이미 지정된 서명자입니다.");
        }
        
        // 새로운 서명자 역할 할당 (기존 서명자 제거하지 않음)
        DocumentRole signerRole = DocumentRole.builder()
                .document(document)
                .assignedUserId(signer.getId())
                .taskRole(DocumentRole.TaskRole.SIGNER)
                .build();
        
        documentRoleRepository.save(signerRole);
        
        // 서명자에게 알림 생성
        createDocumentAssignmentNotification(signer, document, DocumentRole.TaskRole.SIGNER);

        // 서명자 지정만 하고 상태는 유지 (서명 필드 배치 후 상태가 SIGNING이 될 때 메일 전송)
        documentRepository.save(document);
        
        return document;
    }

    /**
     * 서명자 일괄 지정
     */
    public Document assignSignersBatch(Long documentId, List<String> signerEmails, User assignedBy) {
        log.info("서명자 일괄 할당 요청 - 문서 ID: {}, 서명자 수: {}, 요청자: {}", 
                documentId, signerEmails.size(), assignedBy.getId());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 서명자 할당 권한 확인
        boolean isCreator = isCreator(document, assignedBy);
        boolean isEditor = isEditor(document, assignedBy);
        
        if (!isCreator && !isEditor) {
            throw new RuntimeException("서명자를 할당할 권한이 없습니다. 생성자 또는 편집자만 가능합니다.");
        }

        // 모든 서명자 할당
        int successCount = 0;
        for (String signerEmail : signerEmails) {
            try {
                User signer = getUserOrCreate(signerEmail, "Signer User");
                
                // 이미 동일한 서명자가 있는지 확인
                boolean isAlreadyAssigned = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                        documentId, DocumentRole.TaskRole.SIGNER)
                        .stream()
                        .anyMatch(role -> role.getAssignedUserId().equals(signer.getId()));
                
                if (isAlreadyAssigned) {
                    log.warn("이미 지정된 서명자 건너뜀 - 문서 ID: {}, 서명자: {}", documentId, signerEmail);
                    continue;
                }
                
                // 새로운 서명자 역할 할당
                DocumentRole signerRole = DocumentRole.builder()
                        .document(document)
                        .assignedUserId(signer.getId())
                        .taskRole(DocumentRole.TaskRole.SIGNER)
                        .build();
                
                documentRoleRepository.save(signerRole);
                
                // 서명자에게 알림 생성
                createDocumentAssignmentNotification(signer, document, DocumentRole.TaskRole.SIGNER);
                
                successCount++;
                log.info("서명자 할당 성공 - 문서 ID: {}, 서명자: {}", documentId, signerEmail);
            } catch (Exception e) {
                log.error("서명자 할당 실패 - 문서 ID: {}, 서명자: {}, 오류: {}", 
                        documentId, signerEmail, e.getMessage());
                // 개별 실패는 로그만 남기고 계속 진행
            }
        }

        if (successCount == 0) {
            throw new RuntimeException("서명자 할당에 모두 실패했습니다.");
        }

        documentRepository.save(document);
        
        log.info("서명자 일괄 할당 완료 - 문서 ID: {}, 성공: {}/{}", 
                documentId, successCount, signerEmails.size());
        
        return document;
    }

    /**
     * 검토자 제거
     */
    public Document removeReviewer(Long documentId, String reviewerEmail, User removedBy) {
        log.info("검토자 제거 요청 - 문서 ID: {}, 검토자 이메일: {}, 요청자: {}", 
                documentId, reviewerEmail, removedBy.getId());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 검토자 제거 권한 확인 - 생성자 또는 편집자 가능
        boolean isCreator = isCreator(document, removedBy);
        boolean isEditor = isEditor(document, removedBy);
        
        if (!isCreator && !isEditor) {
            throw new RuntimeException("검토자를 제거할 권한이 없습니다. 생성자 또는 편집자만 가능합니다.");
        }
        
        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new RuntimeException("검토자를 찾을 수 없습니다."));
        
        // 해당 검토자의 역할 찾기
        List<DocumentRole> reviewerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.REVIEWER)
                .stream()
                .filter(role -> role.getAssignedUserId().equals(reviewer.getId()))
                .collect(java.util.stream.Collectors.toList());
        
        if (reviewerRoles.isEmpty()) {
            throw new RuntimeException("지정된 검토자가 아닙니다.");
        }
        
        // 검토자 역할 제거
        documentRoleRepository.deleteAll(reviewerRoles);
        
        log.info("검토자 제거 완료 - 문서 ID: {}, 검토자 ID: {}", documentId, reviewer.getId());
        
        return document;
    }
    
    /**
     * 서명자 제거
     */
    public Document removeSigner(Long documentId, String signerEmail, User removedBy) {
        log.info("서명자 제거 요청 - 문서 ID: {}, 서명자 이메일: {}, 요청자: {}", 
                documentId, signerEmail, removedBy.getId());

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 서명자 제거 권한 확인 - 생성자 또는 편집자 가능
        boolean isCreator = isCreator(document, removedBy);
        boolean isEditor = isEditor(document, removedBy);
        
        if (!isCreator && !isEditor) {
            throw new RuntimeException("서명자를 제거할 권한이 없습니다. 생성자 또는 편집자만 가능합니다.");
        }
        
        User signer = userRepository.findByEmail(signerEmail)
                .orElseThrow(() -> new RuntimeException("서명자를 찾을 수 없습니다."));
        
        // 해당 서명자의 역할 찾기
        List<DocumentRole> signerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.SIGNER)
                .stream()
                .filter(role -> role.getAssignedUserId().equals(signer.getId()))
                .collect(java.util.stream.Collectors.toList());
        
        if (signerRoles.isEmpty()) {
            throw new RuntimeException("지정된 서명자가 아닙니다.");
        }
        
        // 서명자 역할 제거
        documentRoleRepository.deleteAll(signerRoles);
        
        log.info("서명자 제거 완료 - 문서 ID: {}, 서명자 ID: {}", documentId, signer.getId());
        
        return document;
    }

    /**
     * 검토자 지정 완료 후 검토 단계로 이동 (READY_FOR_REVIEW -> REVIEWING)
     * 또는 검토 단계 건너뛰고 바로 서명 단계로 이동 (skipReview=true인 경우)
     */
    public Document completeReviewerAssignment(Long documentId, User user, boolean skipReview) {
        log.info("검토자 지정 완료 처리 시작 - 문서 ID: {}, 사용자: {}, 검토 건너뛰기: {}", 
                documentId, user.getId(), skipReview);
        
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 검토자 지정 권한 확인
        if (!canAssignReviewer(document, user)) {
            throw new RuntimeException("검토자 지정 권한이 없습니다");
        }
        
        // 현재 상태가 READY_FOR_REVIEW이어야 함
        if (document.getStatus() != Document.DocumentStatus.READY_FOR_REVIEW) {
            throw new RuntimeException("문서가 검토자 지정 상태가 아닙니다");
        }
        
        if (skipReview) {
            // 검토 단계 건너뛰고 바로 서명 단계로 이동
            // 서명자가 지정되어 있는지 확인
            boolean hasSigner = documentRoleRepository.existsByDocumentIdAndTaskRole(
                    documentId, DocumentRole.TaskRole.SIGNER);
            
            if (!hasSigner) {
                throw new RuntimeException("서명자가 지정되지 않았습니다");
            }
            
            changeDocumentStatus(document, Document.DocumentStatus.SIGNING, user, "검토 단계 건너뛰기 - 서명 단계로 이동");
            log.info("검토 단계 건너뛰기 - 문서 ID: {}, READY_FOR_REVIEW -> SIGNING", documentId);
            
            // 모든 서명자에게 메일 발송
            sendSignerNotifications(document, user);
        } else {
            // 검토자가 지정되어 있는지 확인
            boolean hasReviewer = documentRoleRepository.existsByDocumentIdAndTaskRole(
                    documentId, DocumentRole.TaskRole.REVIEWER);
            
            if (!hasReviewer) {
                throw new RuntimeException("검토자가 지정되지 않았습니다");
            }
            
            // 상태를 REVIEWING으로 변경
            changeDocumentStatus(document, Document.DocumentStatus.REVIEWING, user, "검토자 지정 완료 - 검토 단계로 이동");
            log.info("검토 단계로 이동 - 문서 ID: {}, READY_FOR_REVIEW -> REVIEWING", documentId);
        }
        
        document = documentRepository.save(document);
        
        log.info("검토자 지정 완료 처리 완료 - 문서 ID: {}", documentId);
        return document;
    }
    
    /**
     * 서명자 지정 완료 및 템플릿 생성자를 검토자로 자동 지정 (READY_FOR_REVIEW -> REVIEWING)
     * 프론트엔드 워크플로우: 작성자가 서명자만 지정 → 서명자 지정 완료 → 템플릿 생성자가 자동으로 검토자가 됨
     */
    public Document completeSignerAssignment(Long documentId, User user) {
        log.info("서명자 지정 완료 처리 시작 - 문서 ID: {}, 사용자: {}", documentId, user.getId());
        
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 서명자 지정 권한 확인
        if (!canAssignReviewer(document, user)) {
            throw new RuntimeException("서명자 지정 권한이 없습니다");
        }
        
        // 현재 상태가 READY_FOR_REVIEW여야 함 (서명자 지정 후)
        if (document.getStatus() != Document.DocumentStatus.READY_FOR_REVIEW) {
            throw new RuntimeException("문서가 서명자 지정 상태가 아닙니다");
        }
        
        // 서명자가 지정되어 있는지 확인
        boolean hasSigner = documentRoleRepository.existsByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.SIGNER);
        
        if (!hasSigner) {
            throw new RuntimeException("서명자가 지정되지 않았습니다");
        }
        
        // 템플릿 생성자를 검토자로 자동 지정
        User templateCreator = document.getTemplate().getCreatedBy();
        
        if (templateCreator == null) {
            throw new RuntimeException("템플릿 생성자 정보를 찾을 수 없습니다");
        }
        
        // 템플릿 생성자가 이미 검토자로 지정되어 있는지 확인
        boolean isAlreadyReviewer = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.REVIEWER)
                .stream()
                .anyMatch(role -> role.getAssignedUserId().equals(templateCreator.getId()));
        
        if (!isAlreadyReviewer) {
            // 템플릿 생성자를 검토자로 지정
            DocumentRole reviewerRole = DocumentRole.builder()
                    .document(document)
                    .assignedUserId(templateCreator.getId())
                    .taskRole(DocumentRole.TaskRole.REVIEWER)
                    .build();
            
            documentRoleRepository.save(reviewerRole);
            
            log.info("템플릿 생성자를 검토자로 자동 지정 - 문서 ID: {}, 검토자: {} ({})",
                    documentId, templateCreator.getName(), templateCreator.getEmail());

            // 템플릿 생성자에게 알림 생성
            createDocumentAssignmentNotification(templateCreator, document, DocumentRole.TaskRole.REVIEWER);
        } else {
            log.info("템플릿 생성자가 이미 검토자로 지정되어 있음 - 문서 ID: {}, 검토자: {}", 
                    documentId, templateCreator.getEmail());
        }
        
        // 상태를 REVIEWING으로 변경
        changeDocumentStatus(document, Document.DocumentStatus.REVIEWING, user, 
                "서명자 지정 완료 - 템플릿 생성자 검토 단계로 이동");
        document = documentRepository.save(document);
        
        log.info("서명자 지정 완료 및 검토 단계 시작 - 문서 ID: {}, 상태: REVIEWING", documentId);
        return document;
    }
    
    @Transactional(readOnly = true)
    public List<Document> getDocumentsByUser(User user) {
        // 교직원은 모든 문서 조회 가능
        if ("교직원".equals(user.getPosition())) {
            return documentRepository.findAllWithFolder();
        }
        // 일반 사용자는 자신에게 할당된 문서만 조회
        return documentRepository.findDocumentsByUserIdWithStatusLogs(user.getId());
    }
    
    @Transactional(readOnly = true)
    public List<Document> getTodoDocumentsByUser(User user) {
        return documentRepository.findTodoDocumentsByUserId(user.getId());
    }
    
    @Transactional(readOnly = true)
    public Optional<Document> getDocumentById(Long id) {
        return documentRepository.findByIdWithStatusLogs(id);
    }
    
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentResponse(Long id) {
        Optional<Document> documentOpt = documentRepository.findByIdWithStatusLogs(id);
        if (documentOpt.isEmpty()) {
            return null;
        }

        Document document = documentOpt.get();

        // TaskInfo 생성 시 실제 사용자 정보 포함
        List<DocumentResponse.TaskInfo> taskInfos = document.getDocumentRoles().stream()
                .map(role -> {
                    String userEmail = null;
                    String userName = null;

                    // assignedUserId가 있으면 실제 사용자 정보 조회
                    if (role.getAssignedUserId() != null) {
                        Optional<User> userOpt = userRepository.findById(role.getAssignedUserId());
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            userEmail = user.getEmail();
                            userName = user.getName();
                        }
                    } else {
                        // 임시 사용자 정보 사용
                        userEmail = role.getPendingEmail();
                        userName = role.getPendingName();
                    }

                    // 서명자인 경우 토큰 만료일 조회 (토큰이 존재하면 항상 표시)
                    LocalDateTime tokenExpiresAt = null;
                    if (role.getTaskRole() == DocumentRole.TaskRole.SIGNER && userEmail != null) {
                        Optional<SigningToken> tokenOpt = signingTokenRepository
                                .findByDocumentIdAndSignerEmail(document.getId(), userEmail);
                        if (tokenOpt.isPresent()) {
                            tokenExpiresAt = tokenOpt.get().getExpiresAt();
                        }
                    }

                    return DocumentResponse.TaskInfo.builder()
                            .id(role.getId())
                            .role(role.getTaskRole().name())
                            .assignedUserName(userName)
                            .assignedUserEmail(userEmail)
                            .lastViewedAt(role.getLastViewedAt())
                            .createdAt(role.getCreatedAt())
                            .updatedAt(role.getUpdatedAt())
                            .isNew(role.isNew()) // 새로운 할당인지 확인
                            .tokenExpiresAt(tokenExpiresAt) // 서명자 토큰 만료일
                            .build();
                })
                .collect(Collectors.toList());
        
        List<DocumentStatusLogResponse> statusLogResponses = document.getStatusLogs().stream()
                .map(DocumentStatusLogResponse::from)
                .collect(Collectors.toList());
        
        DocumentResponse.TemplateInfo templateInfo = DocumentResponse.TemplateInfo.from(document.getTemplate());
        
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
    
    private boolean isCreator(Document document, User user) {
        return documentRoleRepository.findByDocumentAndUserAndRole(
                document.getId(), user.getId(), DocumentRole.TaskRole.CREATOR
        ).isPresent();
    }
    
    private boolean isEditor(Document document, User user) {
        return documentRoleRepository.findByDocumentAndUserAndRole(
                document.getId(), user.getId(), DocumentRole.TaskRole.EDITOR
        ).isPresent();
    }
    
    private boolean isReviewer(Document document, User user) {
        return documentRoleRepository.findByDocumentAndUserAndRole(
                document.getId(), user.getId(), DocumentRole.TaskRole.REVIEWER
        ).isPresent();
    }
    
    private boolean isSigner(Document document, User user) {
        return documentRoleRepository.findByDocumentAndUserAndRole(
                document.getId(), user.getId(), DocumentRole.TaskRole.SIGNER
        ).isPresent();
    }
    
    private User getUserOrCreate(String email, String defaultName) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .id(java.util.UUID.randomUUID().toString()) // UUID를 String으로 생성
                            .name(defaultName)
                            .email(email)
                            .password(passwordEncoder.encode("defaultPassword123"))
                            .position(Position.교직원)
                            .role(Role.USER)
                            .build();
                    return userRepository.save(newUser);
                });
    }
    
    public Document completeEditing(Long documentId, User user) {
        log.info("편집 완료 처리 시작 - 문서 ID: {}, 사용자: {}", documentId, user.getId());
        
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        log.info("문서 정보 - ID: {}, 상태: {}, 템플릿 ID: {}", 
                document.getId(), document.getStatus(), document.getTemplate().getId());
        
        // 편집자만 편집 완료 가능
        boolean isEditor = isEditor(document, user);
        
        if (!isEditor) {
            throw new RuntimeException("편집을 완료할 권한이 없습니다");
        }
        
        // 현재 상태가 EDITING이어야 함
        if (document.getStatus() != Document.DocumentStatus.EDITING) {
            log.warn("문서 상태 오류 - 현재 상태: {}, 예상 상태: EDITING", document.getStatus());
            throw new RuntimeException("문서가 편집 상태가 아닙니다");
        }
        
        log.info("필수 필드 검증 시작");
        // 필수 필드 검증
        validateRequiredFields(document);
        log.info("필수 필드 검증 완료");
        
        // 상태를 READY_FOR_REVIEW로 변경 (서명자 지정 단계)
        changeDocumentStatus(document, Document.DocumentStatus.READY_FOR_REVIEW, user, "편집 완료 - 서명자 지정 단계로 이동");
        document = documentRepository.save(document);
        
        return document;
    }
    
    /**
     * 검토자가 문서 검토 승인 (REVIEWING -> 서명자에게 전달, 자동으로 SIGNING으로 또는 서명자 지정 대기)
     */
    public Document approveReview(Long documentId, User user, String comment) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 검토자만 승인 가능
        if (!isReviewer(document, user)) {
            throw new RuntimeException("문서를 검토 승인할 권한이 없습니다");
        }
        
        // 현재 상태가 REVIEWING 이어야 함
        if (document.getStatus() != Document.DocumentStatus.REVIEWING) {
            throw new RuntimeException("문서가 검토 상태가 아닙니다");
        }
        
        // 상태 로그 기록
        String commentText = comment != null ? comment : "검토 승인";
        changeDocumentStatus(document, document.getStatus(), user, commentText);
        
        // 서명자가 이미 지정되어 있는지 확인
        boolean hasSigner = documentRoleRepository.existsByDocumentIdAndTaskRole(
                documentId, DocumentRole.TaskRole.SIGNER);
        
        if (hasSigner) {
            // 서명자가 지정되어 있으면 바로 SIGNING 상태로 변경
            changeDocumentStatus(document, Document.DocumentStatus.SIGNING, user, "검토 승인 완료 - 서명 단계로 자동 이동");
            log.info("검토 승인 후 자동으로 서명 단계로 이동 - 문서 ID: {}", documentId);
            
            // 모든 서명자에게 메일 발송
            sendSignerNotifications(document, user);
        } else {
            // 서명자가 없으면 REVIEWING 상태 유지 (편집자가 서명자 지정 후 completeSignerAssignment 호출 필요)
            log.info("검토 승인 완료 - 서명자 지정 대기 중 - 문서 ID: {}", documentId);
        }
        
        document = documentRepository.save(document);
        
        log.info("검토 승인 완료 - 문서 ID: {}, 검토자: {}", documentId, user.getEmail());
        return document;
    }
    
    /**
     * 검토자가 문서 검토 반려 (REVIEWING -> EDITING)
     */
    public Document rejectReview(Long documentId, User user, String reason) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 검토자만 반려 가능
        if (!isReviewer(document, user)) {
            throw new RuntimeException("문서를 반려할 권한이 없습니다");
        }
        
        // 현재 상태가 REVIEWING 이어야 함
        if (document.getStatus() != Document.DocumentStatus.REVIEWING) {
            throw new RuntimeException("문서가 검토 상태가 아닙니다");
        }
        
        // 먼저 REJECTED 상태를 로그에 기록
        logStatusChange(document, Document.DocumentStatus.REJECTED, user, reason != null ? reason : "검토 반려");
        
        // 그 다음 상태를 EDITING으로 변경
        document.setStatus(Document.DocumentStatus.EDITING);
        document.setIsRejected(true);
        document = documentRepository.save(document);
        
        // 서명자 역할 삭제 (검토 반려 시 서명자 지정 취소)
        List<DocumentRole> signerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(documentId, DocumentRole.TaskRole.SIGNER);
        if (!signerRoles.isEmpty()) {
            documentRoleRepository.deleteAll(signerRoles);
            log.info("검토 반려로 인해 서명자 {} 명 삭제됨 - 문서 ID: {}", signerRoles.size(), documentId);
        }
        
        // 편집자의 lastViewedAt을 null로 초기화하여 NEW 상태로 만들기
        final Document finalDocument = document;
        final User[] editorHolder = new User[1];
        
        // 편집자 lastViewedAt 초기화 + 알림
        documentRoleRepository.findByDocumentAndRole(documentId, DocumentRole.TaskRole.EDITOR)
                .ifPresent(editorRole -> {
                    editorRole.setLastViewedAt(null);
                    documentRoleRepository.save(editorRole);
                    
                    if (editorRole.getAssignedUserId() != null) {
                        userRepository.findById(editorRole.getAssignedUserId())
                                .ifPresent(editor -> {
                                    // 알림 생성
                                    try {
                                        String title = "문서 검토 반려 알림";
                                        String message = String.format("'%s' 문서가 검토 단계에서 반려되었습니다. 수정 후 다시 검토 요청해주세요.",
                                                finalDocument.getTitle() != null ? finalDocument.getTitle() : finalDocument.getTemplate().getName());
                                        String actionUrl = "/documents/" + finalDocument.getId() + "/edit";
                                        
                                        notificationService.createNotification(
                                                editor,
                                                title,
                                                message,
                                                NotificationType.DOCUMENT_REJECTED,
                                                finalDocument.getId(),
                                                actionUrl
                                        );
                                    } catch (Exception e) {
                                        log.error("검토 반려 알림 생성 실패 - 편집자: {}, 문서: {}",
                                                editor.getName(), finalDocument.getTitle(), e);
                                    }
                                    editorHolder[0] = editor;
                                });
                    }
                });
        
        // 메일 전송
        if (editorHolder[0] != null) {
            mailService.sendAssignRejectNotification(
                    MailRequest.RejectionAssignmentEmailCommand.builder()
                            .documentId(document.getId())
                            .documentTitle(document.getTitle())
                            .editorName(editorHolder[0].getName())
                            .editorEmail(editorHolder[0].getEmail())
                            .rejectionReason(reason)
                            .dueDate(document.getDeadline() != null ? document.getDeadline().atZone(java.time.ZoneId.systemDefault()) : null)
                            .rejecterName(user.getName())
                            .build()
            );
        }
        
        log.info("검토 반려 완료 - 문서 ID: {}, 검토자: {}", documentId, user.getEmail());
        return document;
    }
    
    /**
     * 서명자가 문서 서명 (SIGNING -> COMPLETED 또는 다른 서명자 대기)
     */
    public Document approveDocument(Long documentId, User user, String signatureData) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 서명자만 승인 가능
        if (!isSigner(document, user)) {
            throw new RuntimeException("문서를 서명할 권한이 없습니다");
        }
        
        // 현재 상태가 SIGNING 이어야 함
        if (document.getStatus() != Document.DocumentStatus.SIGNING) {
            throw new RuntimeException("문서가 서명 대기 상태가 아닙니다");
        }
        
        // 서명 데이터를 coordinateFields 배열의 해당 필드에 직접 저장
        if (signatureData != null && document.getData() != null) {
            ObjectNode data = (ObjectNode) document.getData();
            
            // coordinateFields 배열 가져오기
            if (data.has("coordinateFields")) {
                ArrayNode coordinateFields = (ArrayNode) data.get("coordinateFields");
                
                // signer_signature 타입이면서 signerEmail이 현재 사용자인 필드 찾기
                // (하위 호환성: reviewer_signature도 지원)
                for (int i = 0; i < coordinateFields.size(); i++) {
                    ObjectNode field = (ObjectNode) coordinateFields.get(i);
                    String fieldType = field.get("type").asText();
                    
                    // signer_signature 또는 reviewer_signature 타입 모두 지원
                    if (("signer_signature".equals(fieldType) || "reviewer_signature".equals(fieldType)) &&
                        field.has("signerEmail") &&
                        user.getEmail().equals(field.get("signerEmail").asText())) {
                        // 해당 필드의 value에 서명 데이터 저장
                        field.put("value", signatureData);
                    }
                    // 하위 호환성: reviewerEmail 필드도 확인
                    else if (("signer_signature".equals(fieldType) || "reviewer_signature".equals(fieldType)) &&
                             field.has("reviewerEmail") &&
                             user.getEmail().equals(field.get("reviewerEmail").asText())) {
                        field.put("value", signatureData);
                    }
                }
                
                // 업데이트된 coordinateFields 저장
                data.set("coordinateFields", coordinateFields);
                document.setData(data);
            }
        }
        
        // 문서 저장 (서명 데이터 업데이트)
        document = documentRepository.save(document);
        
        // 모든 서명자가 서명했는지 확인
        boolean allSignersSigned = checkAllSignersSigned(document);
        
        if (allSignersSigned) {
            // 모든 서명자가 서명했으면 상태를 COMPLETED로 변경
            log.info("모든 서명자가 서명 완료 - 문서 ID: {}, 상태를 COMPLETED로 변경", documentId);
            changeDocumentStatus(document, Document.DocumentStatus.COMPLETED, user, "모든 서명자 승인 완료");
            document = documentRepository.save(document);
        } else {
            // 아직 서명하지 않은 서명자가 있으면 SIGNING 상태 유지
            log.info("서명 완료 - 문서 ID: {}, 사용자: {}, 다른 서명자 대기 중", documentId, user.getEmail());
        }
        
        return document;
    }
    
    /**
     * 모든 서명자가 서명했는지 확인
     */
    private boolean checkAllSignersSigned(Document document) {
        // 지정된 모든 서명자 가져오기
        List<DocumentRole> signerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                document.getId(), DocumentRole.TaskRole.SIGNER);
        
        if (signerRoles.isEmpty()) {
            log.warn("서명자가 없습니다 - 문서 ID: {}", document.getId());
            return false;
        }
        
        // 모든 서명자의 이메일 수집
        Set<String> signerEmails = new HashSet<>();
        for (DocumentRole role : signerRoles) {
            Optional<User> userOpt = userRepository.findById(role.getAssignedUserId());
            userOpt.ifPresent(user -> signerEmails.add(user.getEmail()));
        }
        
        // coordinateFields에서 signer_signature 타입 필드 확인
        if (document.getData() == null || !document.getData().has("coordinateFields")) {
            log.warn("coordinateFields가 없습니다 - 문서 ID: {}", document.getId());
            return false;
        }
        
        ArrayNode coordinateFields = (ArrayNode) document.getData().get("coordinateFields");
        
        // 각 서명자별 서명 여부 확인
        Set<String> signedSigners = new HashSet<>();
        for (int i = 0; i < coordinateFields.size(); i++) {
            JsonNode field = coordinateFields.get(i);
            String fieldType = field.get("type").asText();
            
            // signer_signature 또는 reviewer_signature (하위 호환성) 타입 확인
            if (("signer_signature".equals(fieldType) || "reviewer_signature".equals(fieldType)) &&
                field.has("value") &&
                field.get("value") != null &&
                !field.get("value").isNull() &&
                !field.get("value").asText().isEmpty()) {
                
                // signerEmail 또는 reviewerEmail (하위 호환성) 필드 확인
                String signerEmail = null;
                if (field.has("signerEmail")) {
                    signerEmail = field.get("signerEmail").asText();
                } else if (field.has("reviewerEmail")) {
                    signerEmail = field.get("reviewerEmail").asText();
                }
                
                if (signerEmail != null) {
                    signedSigners.add(signerEmail);
                }
            }
        }
        
        // 모든 서명자가 서명했는지 확인
        boolean allSigned = signedSigners.containsAll(signerEmails);
        
        log.info("서명 상태 확인 - 문서 ID: {}, 전체 서명자: {}, 서명 완료: {}, 모두 서명: {}", 
                document.getId(), signerEmails.size(), signedSigners.size(), allSigned);
        
        return allSigned;
    }
    
    /**
     * 서명자가 문서 서명 반려 (SIGNING -> EDITING)
     */
    public Document rejectDocument(Long documentId, User user, String reason) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
        
        // 서명자만 거부 가능
        if (!isSigner(document, user)) {
            throw new RuntimeException("문서를 반려할 권한이 없습니다");
        }
        
        // 현재 상태가 SIGNING 이어야 함
        if (document.getStatus() != Document.DocumentStatus.SIGNING) {
            throw new RuntimeException("문서가 서명 대기 상태가 아닙니다");
        }
        
        // 먼저 REJECTED 상태를 로그에 기록
        logStatusChange(document, Document.DocumentStatus.REJECTED, user, reason != null ? reason : "문서 반려");
        
        // 그 다음 상태를 Editing 변경
        document.setStatus(Document.DocumentStatus.EDITING);
        document.setIsRejected(true);
        document = documentRepository.save(document);

        // 서명자 역할 삭제 (서명 반려 시 모든 서명자 지정 취소)
        List<DocumentRole> signerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(documentId, DocumentRole.TaskRole.SIGNER);
        if (!signerRoles.isEmpty()) {
            documentRoleRepository.deleteAll(signerRoles);
            log.info("서명 반려로 인해 서명자 {} 명 삭제됨 - 문서 ID: {}", signerRoles.size(), documentId);
        }

        // 편집자의 lastViewedAt을 null로 초기화하여 NEW 상태로 만들기
        final Document finalDocument = document;
        final User[] editorHolder = new User[1];

        // 편집자 lastViewedAt 초기화 + 알림
        documentRoleRepository.findByDocumentAndRole(documentId, DocumentRole.TaskRole.EDITOR)
                .ifPresent(editorRole -> {
                    editorRole.setLastViewedAt(null);
                    documentRoleRepository.save(editorRole);

                    if (editorRole.getAssignedUserId() != null) {
                        userRepository.findById(editorRole.getAssignedUserId())
                                .ifPresent(editor -> {
                                    // 알림 생성
                                    try {
                                        String title = "문서 반려 알림";
                                        String message = String.format("'%s' 문서가 반려되었습니다. 수정 후 다시 검토 요청해주세요.",
                                                finalDocument.getTitle() != null ? finalDocument.getTitle() : finalDocument.getTemplate().getName());
                                        String actionUrl = "/documents/" + finalDocument.getId() + "/edit";

                                        notificationService.createNotification(
                                                editor,
                                                title,
                                                message,
                                                NotificationType.DOCUMENT_REJECTED,
                                                finalDocument.getId(),
                                                actionUrl
                                        );
                                    } catch (Exception e) {
                                        log.error("문서 반려 알림 생성 실패 - 편집자: {}, 문서: {}",
                                                editor.getName(), finalDocument.getTitle(), e);
                                    }
                                    editorHolder[0] = editor;
                                });
                    }
                });
        // 서명자 역할은 유지 (반려 후에도 서명자가 자신이 반려한 문서를 확인할 수 있도록)
        if (editorHolder[0] != null) {
            mailService.sendAssignRejectNotification(
                    MailRequest.RejectionAssignmentEmailCommand.builder()
                            .documentId(document.getId())
                            .documentTitle(document.getTitle())
                            .editorName(editorHolder[0].getName())
                            .editorEmail(editorHolder[0].getEmail())
                            .rejectionReason(reason)
                            .dueDate(document.getDeadline() != null ? document.getDeadline().atZone(java.time.ZoneId.systemDefault()) : null)
                            .rejecterName(user.getName())
                            .build()
            );
        }


        log.info("문서 반려 완료 - 문서 ID: {}, 검토자: {} (검토자 역할 유지, 편집자 NEW 상태로 초기화)", documentId, user.getEmail());
        
        return document;
    }
    
    public boolean canAssignReviewer(Document document, User user) {
        try {
            // 해당 사용자의 모든 역할을 조회하여 작성자이거나 편집자인 역할이 있는지 확인
            List<DocumentRole> roles = documentRoleRepository.findAllByDocumentAndUser(document.getId(), user.getId());
            
            return roles.stream().anyMatch(role ->
                role.getTaskRole() == DocumentRole.TaskRole.CREATOR || 
                role.getTaskRole() == DocumentRole.TaskRole.EDITOR
            );
        } catch (Exception e) {
            log.error("Error checking assign reviewer permission for document {} and user {}", document.getId(), user.getId(), e);
            return false;
        }
    }

    public boolean canReview(Long documentId, User user) {
        try {
            Document document = getDocumentById(documentId)
                    .orElseThrow(() -> new RuntimeException("Document not found"));
            
            // 검토자이고 문서가 검토 대기 상태인지 확인
            return isReviewer(document, user) && 
                   document.getStatus() == Document.DocumentStatus.REVIEWING;
        } catch (Exception e) {
            log.error("Error checking review permission for document {} and user {}", documentId, user.getId(), e);
            return false;
        }
    }
    
    public boolean canSign(Long documentId, User user) {
        try {
            Document document = getDocumentById(documentId)
                    .orElseThrow(() -> new RuntimeException("Document not found"));
            
            // 서명자이고 문서가 서명 대기 상태인지 확인
            return isSigner(document, user) && 
                   document.getStatus() == Document.DocumentStatus.SIGNING;
        } catch (Exception e) {
            log.error("Error checking sign permission for document {} and user {}", documentId, user.getId(), e);
            return false;
        }
    }    private void validateRequiredFields(Document document) {
        try {
            log.info("필수 필드 검증 시작 - 문서 ID: {}", document.getId());
            
            // 문서 데이터가 없으면 검증하지 않음
            if (document.getData() == null) {
                log.info("문서 데이터가 없어 필수 필드 검증을 건너뜁니다");
                return;
            }
            
            JsonNode documentData = document.getData();
            JsonNode coordinateFields = documentData.get("coordinateFields");
            
            if (coordinateFields == null || !coordinateFields.isArray()) {
                log.info("coordinateFields가 없거나 배열이 아닙니다");
                return;
            }
            
            log.info("검증할 필드 수: {}", coordinateFields.size());
            
            List<String> missingFields = new ArrayList<>();
            
            for (JsonNode field : coordinateFields) {
                JsonNode requiredNode = field.get("required");
                JsonNode valueNode = field.get("value");
                JsonNode labelNode = field.get("label");
                JsonNode idNode = field.get("id");
                
                String fieldId = idNode != null ? idNode.asText() : "unknown";
                String fieldLabel = labelNode != null ? labelNode.asText() : fieldId;
                boolean isRequired = requiredNode != null && requiredNode.asBoolean();
                String value = valueNode != null ? valueNode.asText() : "";
                
                log.debug("필드 검증 - ID: {}, Label: {}, Required: {}, Value: '{}'", 
                         fieldId, fieldLabel, isRequired, value);
                
                // required가 true이고 value가 비어있으면 필수 필드 누락
                if (isRequired) {
                    if (value == null || value.trim().isEmpty()) {
                        String fieldName = labelNode != null ? labelNode.asText() : 
                                         (idNode != null ? "필드 " + idNode.asText() : "알 수 없는 필드");
                        missingFields.add(fieldName);
                        log.warn("필수 필드 누락 - {}", fieldName);
                    }
                }
            }
            
            if (!missingFields.isEmpty()) {
                String errorMessage = "다음 필수 필드를 채워주세요: " + String.join(", ", missingFields);
                log.error("필수 필드 검증 실패: {}", errorMessage);
                throw new RuntimeException(errorMessage);
            }
            
            log.info("필수 필드 검증 완료 - 모든 필수 필드가 채워져 있습니다");
            
        } catch (Exception e) {
            if (e.getMessage().contains("필수 필드")) {
                throw e; // 필수 필드 검증 오류는 그대로 전파
            }
            log.warn("필수 필드 검증 중 오류 발생: {}", e.getMessage());
        }
    }
    
    public void deleteDocument(Long documentId, User user) {
        log.info("🗑️ 문서 삭제 요청 - 문서 ID: {}, 사용자: {}", documentId, user.getEmail());
        
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("문서를 찾을 수 없습니다"));
        
        // 삭제 권한 검증: CREATOR, EDITOR, 또는 폴더 접근 권한이 있는 사용자
        boolean hasDeletePermission = isCreator(document, user) || 
                                    isEditor(document, user) || 
                                    user.canAccessFolders();
        
        if (!hasDeletePermission) {
            log.warn("문서 삭제 권한 없음 - 문서 ID: {}, 사용자: {}", documentId, user.getEmail());
            throw new RuntimeException("문서를 삭제할 권한이 없습니다");
        }
        
        log.info("문서 삭제 권한 확인 완료 - 문서 ID: {}, 사용자: {} (생성자: {}, 편집자: {}, 폴더접근: {})",
                documentId, user.getEmail(), 
                isCreator(document, user), isEditor(document, user), user.canAccessFolders());
        
        // 관련 DocumentRole 데이터 삭제
        List<DocumentRole> documentRoles = documentRoleRepository.findByDocumentId(documentId);
        if (!documentRoles.isEmpty()) {
            documentRoleRepository.deleteAll(documentRoles);
            log.info("문서 역할 데이터 삭제 완료 - 문서 ID: {}, 삭제된 역할 수: {}", documentId, documentRoles.size());
        }

        // 문서 삭제
        documentRepository.delete(document);
        log.info("문서 삭제 완료 - 문서 ID: {}, 제목: {}", documentId, document.getTitle());
    }
    
     // 폴더 관리 권한 확인 (hasAccessFolders=true)
    public void validateFolderManagePermission(User user) {
        log.info("폴더 관리 권한 검증 - 사용자: {}, 권한: {}", user.getEmail(), user.canAccessFolders());
         if (!user.canAccessFolders()) {
             throw new RuntimeException("폴더 관리 권한이 없습니다. 관리자에게 문의하세요.");
         }
    }
    
    /**
     * 문서 상태 변경을 로그에 기록
     */
    private void logStatusChange(Document document, Document.DocumentStatus newStatus, User changedBy, String comment) {
        // 반려(REJECTED) 상태인지 확인
        boolean isRejection = newStatus == Document.DocumentStatus.REJECTED;
        
        DocumentStatusLog statusLog = DocumentStatusLog.builder()
                .document(document)
                .status(newStatus)
                .changedByEmail(changedBy != null ? changedBy.getEmail() : null)
                .changedByName(changedBy != null ? changedBy.getName() : null)
                .comment(comment)
                .rejectLog(isRejection) // 반려인 경우 true, 아니면 false
                .build();
        
        documentStatusLogRepository.save(statusLog);
        log.info("문서 상태 변경 로그 생성 - 문서ID: {}, 상태: {} -> {}, 변경자: {}, 반려여부: {}", 
                document.getId(), document.getStatus(), newStatus, 
                changedBy != null ? changedBy.getEmail() : "시스템", isRejection);
    }
    
    /**
     * 문서 상태 변경 (로그 포함)
     */
    public void changeDocumentStatus(Document document, Document.DocumentStatus newStatus, User changedBy, String comment) {
        Document.DocumentStatus oldStatus = document.getStatus();
        
        // 상태가 실제로 변경되는 경우에만 로그 기록
        if (oldStatus != newStatus) {
            document.setStatus(newStatus);
            documentRepository.save(document);
            logStatusChange(document, newStatus, changedBy, comment);
         }
    }
    
    /**
     * 사용자가 문서를 조회했음을 표시 (lastViewedAt 업데이트)
     */
    @Transactional
    public void markDocumentAsViewed(Long documentId, User user) {
        log.info("문서 조회 표시 - DocumentId: {}, UserId: {}", documentId, user.getId());
        
        // 해당 문서에서 현재 사용자에게 할당된 모든 역할 찾기
        List<DocumentRole> userRoles = documentRoleRepository
            .findAllByDocumentAndUser(documentId, user.getId());
            
        if (!userRoles.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (DocumentRole role : userRoles) {
                role.setLastViewedAt(now);
                documentRoleRepository.save(role);
                log.info("문서 조회 시간 업데이트 완료 - DocumentRoleId: {}, TaskRole: {}", 
                        role.getId(), role.getTaskRole());
            }
            log.info("총 {}개의 역할에 대해 조회 시간 업데이트 완료", userRoles.size());
        } else {
            log.warn("해당 사용자에게 할당된 문서 역할을 찾을 수 없습니다 - DocumentId: {}, UserId: {}", 
                    documentId, user.getId());
        }
    }
    
    /**
     * 문서 할당 시 알림 생성
     */
    private void createDocumentAssignmentNotification(User assignedUser, Document document, DocumentRole.TaskRole role) {
        try {
            String title = getNotificationTitle(role, document);
            String message = getNotificationMessage(role, document);
            String actionUrl = "/documents/" + document.getId();
            
            notificationService.createNotification(
                assignedUser,
                title,
                message,
                NotificationType.DOCUMENT_ASSIGNED,
                document.getId(),
                actionUrl
            );
            
            log.info("문서 할당 알림 생성 완료 - 사용자: {}, 문서: {}, 역할: {}", 
                    assignedUser.getName(), document.getTitle(), role);
        } catch (Exception e) {
            log.error("문서 할당 알림 생성 실패 - 사용자: {}, 문서: {}, 역할: {}", 
                    assignedUser.getName(), document.getTitle(), role, e);
        }
    }
    
    /**
     * 역할에 따른 알림 제목 생성
     */
    private String getNotificationTitle(DocumentRole.TaskRole role, Document document) {
        switch (role) {
            case EDITOR:
                return "새로운 문서 편집 요청";
            case REVIEWER:
                return "새로운 문서 검토 요청";
            case SIGNER:
                return "새로운 문서 서명 요청";
            case CREATOR:
                return "문서가 생성되었습니다";
            default:
                return "새로운 문서 할당";
        }
    }
    
    /**
     * 역할에 따른 알림 메시지 생성
     */
    private String getNotificationMessage(DocumentRole.TaskRole role, Document document) {
        String documentTitle = document.getTitle() != null ? document.getTitle() : document.getTemplate().getName();
        
        switch (role) {
            case EDITOR:
                return "'" + documentTitle + "' 문서의 편집자로 지정되었습니다.";
            case REVIEWER:
                return "'" + documentTitle + "' 문서의 검토자로 지정되었습니다.";
            case SIGNER:
                return "'" + documentTitle + "' 문서의 서명자로 지정되었습니다.";
            case CREATOR:
                return "'" + documentTitle + "' 문서를 성공적으로 생성했습니다.";
            default:
                return "'" + documentTitle + "' 문서에 새로운 역할이 할당되었습니다.";
        }
    }
    
    /**
     * 모든 서명자에게 서명 요청 메일 발송 (토큰 기반)
     */
    private void sendSignerNotifications(Document document, User requestedBy) {
        log.info("서명자 토큰 기반 메일 발송 시작 - 문서 ID: {}", document.getId());
        
        // 모든 서명자 조회
        List<DocumentRole> signerRoles = documentRoleRepository.findAllByDocumentIdAndTaskRole(
                document.getId(), DocumentRole.TaskRole.SIGNER);
        
        if (signerRoles.isEmpty()) {
            log.warn("서명자가 없습니다 - 문서 ID: {}", document.getId());
            return;
        }
        
        String documentTitle = document.getTitle() != null ? document.getTitle() : document.getTemplate().getName();
        
        // 각 서명자에게 토큰 생성 및 메일 발송
        for (DocumentRole signerRole : signerRoles) {
            try {
                Optional<User> signerOpt = userRepository.findById(signerRole.getAssignedUserId());
                if (signerOpt.isPresent()) {
                    User signer = signerOpt.get();
                    
                    // 서명 토큰 생성 및 이메일 발송
                    signingTokenService.createAndSendToken(
                        document.getId(),
                        signer.getEmail(),
                        signer.getName(),
                        documentTitle
                    );
                    
                    log.info("서명자에게 토큰 기반 메일 발송 완료 - 서명자: {}, 문서 ID: {}", 
                            signer.getEmail(), document.getId());
                } else {
                    log.warn("서명자를 찾을 수 없습니다 - 사용자 ID: {}", signerRole.getAssignedUserId());
                }
            } catch (Exception e) {
                log.error("서명자에게 토큰 기반 메일 발송 실패 - 역할 ID: {}, 문서 ID: {}", 
                        signerRole.getId(), document.getId(), e);
                // 메일 발송 실패는 전체 프로세스를 중단시키지 않음
            }
        }
        
        log.info("서명자 토큰 기반 메일 발송 완료 - 문서 ID: {}, 발송 대상: {}명", 
                document.getId(), signerRoles.size());
    }
    
    /**
     * 이메일 기반 서명 승인 (익명 사용자용)
     */
    public Document approveDocumentByEmail(Long documentId, String signerEmail, String signatureData) {
        log.info("이메일 기반 서명 승인 - 문서 ID: {}, 서명자: {}", documentId, signerEmail);
        
        // 이메일로 사용자 찾기
        User user = userRepository.findByEmail(signerEmail)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + signerEmail));
        
        // 기존 approveDocument 메서드 호출
        return approveDocument(documentId, user, signatureData);
    }
    
    /**
     * 이메일 기반 서명 반려 (익명 사용자용)
     */
    public Document rejectDocumentByEmail(Long documentId, String signerEmail, String reason) {
        log.info("이메일 기반 서명 반려 - 문서 ID: {}, 서명자: {}", documentId, signerEmail);
        
        // 이메일로 사용자 찾기
        User user = userRepository.findByEmail(signerEmail)
            .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + signerEmail));
        
        // 기존 rejectDocument 메서드 호출
        return rejectDocument(documentId, user, reason);
    }


    /**
     * 특정 템플릿 ID로 문서 조회 (현재 사용자가 EDITOR인 문서만)
     * - 서명자 이름과 서명자 서명 데이터는 제외
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> getDocumentsByTemplateId(Long templateId, User user) {
        log.info("템플릿 ID로 문서 조회 - 템플릿 ID: {}, 사용자: {}", templateId, user.getId());

        List<Document> documents = documentRepository.findByTemplateIdAndEditorUserId(templateId, user.getId());

        log.info("조회된 문서 수: {}", documents.size());

        return documents.stream()
                .map(document -> {
                    // DocumentResponse 생성
                    DocumentResponse response = getDocumentResponseForTemplate(document);
                    return response;
                })
                .collect(Collectors.toList());
    }

    /**
     * 템플릿 기반 문서 조회용 DocumentResponse 생성 (서명 정보 제외)
     */
    private DocumentResponse getDocumentResponseForTemplate(Document document) {
        // TaskInfo 생성 시 실제 사용자 정보 포함
        List<DocumentResponse.TaskInfo> taskInfos = document.getDocumentRoles().stream()
                .map(role -> {
                    String userEmail = null;
                    String userName = null;

                    // assignedUserId가 있으면 실제 사용자 정보 조회
                    if (role.getAssignedUserId() != null) {
                        Optional<User> userOpt = userRepository.findById(role.getAssignedUserId());
                        if (userOpt.isPresent()) {
                            User assignedUser = userOpt.get();
                            userEmail = assignedUser.getEmail();
                            userName = assignedUser.getName();
                        }
                    } else {
                        // 임시 사용자 정보 사용
                        userEmail = role.getPendingEmail();
                        userName = role.getPendingName();
                    }

                    return DocumentResponse.TaskInfo.builder()
                            .id(role.getId())
                            .role(role.getTaskRole().name())
                            .assignedUserName(userName)
                            .assignedUserEmail(userEmail)
                            .lastViewedAt(role.getLastViewedAt())
                            .createdAt(role.getCreatedAt())
                            .updatedAt(role.getUpdatedAt())
                            .isNew(role.isNew())
                            .build();
                })
                .collect(Collectors.toList());

        // 서명 데이터 제외한 문서 data 생성
        JsonNode sanitizedData = sanitizeDocumentData(document.getData());

        return DocumentResponse.builder()
                .id(document.getId())
                .templateId(document.getTemplate().getId())
                .templateName(document.getTemplate().getName())
                .title(document.getTitle())
                .data(sanitizedData)
                .status(document.getStatus().name())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .deadline(document.getDeadline())
                .isRejected(document.getIsRejected() != null ? document.getIsRejected() : false)
                .tasks(taskInfos)
                .folderId(document.getFolder() != null ? document.getFolder().getId().toString() : null)
                .folderName(document.getFolder() != null ? document.getFolder().getName() : null)
                .build();
    }

    /**
     * 문서 데이터에서 서명자 이름, 서명자 서명 데이터 제외
     */
    private JsonNode sanitizeDocumentData(JsonNode data) {
        if (data == null) {
            return null;
        }

        try {
            ObjectNode sanitizedData = data.deepCopy();

            // coordinateFields 처리
            if (sanitizedData.has("coordinateFields") && sanitizedData.get("coordinateFields").isArray()) {
                ArrayNode coordinateFields = (ArrayNode) sanitizedData.get("coordinateFields");
                ArrayNode sanitizedFields = objectMapper.createArrayNode();

                for (JsonNode field : coordinateFields) {
                    ObjectNode fieldCopy = field.deepCopy();
                    String fieldType = fieldCopy.has("type") ? fieldCopy.get("type").asText() : "";

                    // signer_signature 또는 reviewer_signature 타입의 value (서명 데이터) 제거
                    if ("signer_signature".equals(fieldType) || "reviewer_signature".equals(fieldType)) {
                        fieldCopy.put("value", ""); // 서명 데이터 초기화
                        // 서명자 이름 필드도 제거
                        if (fieldCopy.has("signerEmail")) {
                            fieldCopy.remove("signerEmail");
                        }
                        if (fieldCopy.has("signerName")) {
                            fieldCopy.remove("signerName");
                        }
                        if (fieldCopy.has("reviewerEmail")) {
                            fieldCopy.remove("reviewerEmail");
                        }
                        if (fieldCopy.has("reviewerName")) {
                            fieldCopy.remove("reviewerName");
                        }
                    }

                    sanitizedFields.add(fieldCopy);
                }

                sanitizedData.set("coordinateFields", sanitizedFields);
            }

            return sanitizedData;
        } catch (Exception e) {
            log.warn("문서 데이터 정제 중 오류: {}", e.getMessage());
            return data; // 오류 시 원본 반환
        }
    }

} 