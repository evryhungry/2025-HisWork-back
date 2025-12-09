package com.hiswork.backend.service;

import com.hiswork.backend.domain.Template;
import com.hiswork.backend.domain.User;
import com.hiswork.backend.domain.Folder;
import com.hiswork.backend.dto.TemplateCreateRequest;
import com.hiswork.backend.repository.TemplateRepository;
import com.hiswork.backend.repository.FolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TemplateService {
    
    private final TemplateRepository templateRepository;
    private final FolderRepository folderRepository;
    
    public Template savePdfTemplate(Template template) {
        return templateRepository.save(template);
    }
    
    public List<Template> getAllTemplates() {
        return templateRepository.findAll();
    }
    
    public Optional<Template> getTemplateById(Long id) {
        return templateRepository.findById(id);
    }
    
    public Template updateTemplate(Long id, TemplateCreateRequest request, User user) {
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        
        // 교직원이거나 생성자인 경우 수정 가능
        if (!template.getCreatedBy().getId().equals(user.getId()) 
                && user.getPosition() != com.hiswork.backend.domain.Position.교직원) {
            throw new RuntimeException("템플릿을 수정할 권한이 없습니다");
        }
        
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setIsPublic(request.getIsPublic());
//        template.setPdfFilePath(request.getPdfFilePath()); // PDF 파일 경로는 수정하지 않음
//        template.setPdfImagePath(request.getPdfImagePath()); // PDF 이미지 경로는 수정하지 않음
        template.setCoordinateFields(request.getCoordinateFields()); // 누락된 필드 추가
        template.setDeadline(request.getDeadline()); // 만료일 업데이트
        
        // 기본 폴더 업데이트
        if (request.getDefaultFolderId() != null) {
            Folder defaultFolder = folderRepository.findById(request.getDefaultFolderId()).orElse(null);
            template.setDefaultFolder(defaultFolder);
            if (defaultFolder == null) {
                log.warn("지정된 폴더를 찾을 수 없습니다: {}", request.getDefaultFolderId());
            }
        } else {
            template.setDefaultFolder(null);
        }
        
        return templateRepository.save(template);
    }
    
    public void deleteTemplate(Long id, User user) {
        Template template = templateRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        
        // 교직원이거나 생성자인 경우 삭제 가능
        if (!template.getCreatedBy().getId().equals(user.getId()) 
                && user.getPosition() != com.hiswork.backend.domain.Position.교직원) {
            throw new RuntimeException("템플릿을 삭제할 권한이 없습니다");
        }
        
        templateRepository.delete(template);
    }
} 