package com.hiswork.backend.service;

import com.hiswork.backend.domain.User;
import com.hiswork.backend.dto.FailureRow;
import com.hiswork.backend.dto.MailRequest;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

//    private static final String linkDomain = "http://localhost:5173/";
    private static final String linkDomain = "https://coworks.walab.info/";


    /**
     * 편집자 할당 알림 메일 전송
     * @param command
     */
    @Async
    public void sendAssignEditorNotification(MailRequest.EditorAssignmentEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("documentTitle", command.getDocumentTitle());
            ctx.setVariable("actionLink", linkDomain + "tasks/");
            ctx.setVariable("creatorName", command.getCreatorName());
            ctx.setVariable("editorName", command.getEditorName());
            ctx.setVariable("dueDate", command.getDueDate() != null ? fmt.format(command.getDueDate()) : null);

            String html = templateEngine.process("assign_editor_notification", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(command.getEditorEmail());
            helper.setSubject("[CoWorks] " + command.getDocumentTitle() + " 문서의 편집자로 지정되었음을 알려드립니다.");
            helper.setText(html, true);


//            helper.addInline("logoImage", new ClassPathResource("static/images/hiswork-logo.png"));

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("편집자 할당 메일 전송 실패", e);
        }
    }

    /**
     * 검토자 할당 알림 메일 전송
     * @param command
     */
    @Async
    public void sendAssignReviewerNotification(MailRequest.ReviewerAssignmentEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("documentTitle", command.getDocumentTitle());
            ctx.setVariable("actionLink", linkDomain + "email-sign/" + command.getDocumentId());
            ctx.setVariable("editorName", command.getEditorName());
            ctx.setVariable("reviewerName", command.getReviewerName());
            ctx.setVariable("reviewDueDateStr", command.getReviewDueDate() != null ? fmt.format(command.getReviewDueDate()) : null);

            String html = templateEngine.process("assign_reviewer_notification", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(command.getReviewerEmail());
            helper.setSubject("[CoWorks] '" + command.getDocumentTitle() + "' 문서의 검토자로 지정되었음을 알려드립니다.");
            helper.setText(html, true);

//            helper.addInline("logoImage", new ClassPathResource("static/images/hiswork-logo.png"));
            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("서명자 할당 메일 전송 실패", e);
        }
    }

    @Async
    public void sendAssignRejectNotification(MailRequest.RejectionAssignmentEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("documentTitle", command.getDocumentTitle());
            ctx.setVariable("actionLink", linkDomain + "documents/" + command.getDocumentId() + "/edit");
            ctx.setVariable("editorName", command.getEditorName());
            ctx.setVariable("rejecterName", command.getRejecterName());
            ctx.setVariable("rejectionReason", command.getRejectionReason());
            ctx.setVariable("rejectionDate", fmt.withZone(ZoneId.of("Asia/Seoul")).format(ZonedDateTime.now()));
            ctx.setVariable("dueDate", command.getDueDate() != null ? fmt.format(command.getDueDate()) : null);

            String html = templateEngine.process("assign_rejection_notification", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(command.getEditorEmail());
            helper.setSubject("[CoWorks] " + command.getDocumentTitle() + " 문서가 반려되었음을 알려드립니다.");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("문서 반려 메일 전송 실패", e);
        }
    }

    /**
     * 테스트용 TA 보고 메일<br/>
     * 실제 실패 리스트 없이 테스트용 1개만
     * @param sender
     */
    private void notifyTaAboutFailures(User sender) {
        try {
            // 테스트용 FailureRow 1개만
            List<FailureRow> failures = List.of(
                    new FailureRow("student1@example.com", sender.getName())
            );

            Context ctx = new Context();
            ctx.setVariable("course", "TA 문서일지");
            ctx.setVariable("username", sender.getName());
            ctx.setVariable("failedCount", failures.size());
            ctx.setVariable("batchId", "TEST-001"); // 테스트용 고정 ID
            ctx.setVariable("failureRows", failures);
            ctx.setVariable("retryLink", "https://hisnet.handong.edu/test-retry");
            ctx.setVariable("dashboardLink", "https://hisnet.handong.edu/test-dashboard");

            String html = templateEngine.process("failure-notice-ta", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(sender.getEmail());
            helper.setSubject("[CoWorks] (TEST) 메일 전송 실패 보고서");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("TA 보고 메일 전송 실패", e);
        }
    }

    /**
     * <h4> 대량 메일 전송 처리 </h4> <br/>
     * users 리스트를 50개씩 잘라서 처리 <br/>
     * users : 메일을 받을 학생들 <br/>
     * sender : 관리자 (실패 보고 메일 받을 사람) <br/>
     * 추후 <br/>
     * massage : 메일 중심에 들어 갈 내용 (예: TA 문서일지 작성, 과제 제출 등) <br/>
     * notificationType : 메일 중심에 들어 갈 내용2
     *                         (예: TA 문서일지 작성, 과제 제출 등) <br/>
     *                         -> 추후에 Enum으로 관리하는게 좋을듯: Enum으로 관리하면, Enum에 따라 템플릿도 다르게 처리 가능 <br/>
     * subject : 메일 제목 (예: [CoWorks] 알림이 도착했습니다. -> notificationType에 따라 다르게 처리하면 좋을거 같다고 생각.)
     * @param users
     * @param sender
     */
    public void sendNotificationInBatch(List<User> users, User sender) {
        int chunkSize = 50;
        for (int i = 0; i < users.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, users.size());
            List<User> chunk = users.subList(i, end);
            processChunk(chunk, sender);
        }
    }

    private void processChunk(List<User> chunk, User sender) {
        List<FailureRow> failures = new ArrayList<>();

        for (User u : chunk) {
            try {
                Context ctx = new Context();
                ctx.setVariable("message", "TA 문서일지"); // 동적으로 바꿔야함
                ctx.setVariable("notificationType", "TA 문서일지 작성"); // 동적으로 바꿔야함 2
                ctx.setVariable("username", u.getName());
                ctx.setVariable("verifyLink", "https://hisnet.handong.edu/"); // 개별 링크 수정요함
                String html = templateEngine.process("notification", ctx);

                MimeMessage mime = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
                helper.setTo(u.getEmail());
                helper.setSubject("[CoWorks] 알림이 도착했습니다.");
                helper.setText(html, true);

                mailSender.send(mime);

            } catch (Exception ex) {
                failures.add(new FailureRow(u.getEmail(), u.getName()));
            }
        }

        if (!failures.isEmpty()) {
            notifyTaAboutFailures(failures, sender);
        }
    }

    private void notifyTaAboutFailures(List<FailureRow> failures, User sender) {
        try {
            Context ctx = new Context();
            ctx.setVariable("course", "Ta 문서일지"); // 바꿔야함
            ctx.setVariable("username", sender.getName());
            ctx.setVariable("failedCount", failures.size());
            ctx.setVariable("batchId", "20240601-001"); // 실제 배치 ID로 바꿔야함 근데 있어야할까? 라는 고민
            ctx.setVariable("failureRows", failures);
            ctx.setVariable("retryLink", "https://hisnet.handong.edu/"); // 해당 재시도 링크로 바꿔야함
            ctx.setVariable("dashboardLink", "https://hisnet.handong.edu/"); // 해당 데시보드 링크로 바꿔야함

            String html = templateEngine.process("failure-notice-ta", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());

            helper.setTo(sender.getEmail());
            helper.setSubject("[CoWorks] 메일 전송 실패 보고서");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("TA 보고 메일 전송 실패", e);
        }
    }

    /**
     * 관리자가 작업자에게 메시지 전송
     * @param command
     */
    @Async
    public void sendAdminMessageToWorker(MailRequest.AdminMessageEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("recipientName", command.getRecipientName());
            ctx.setVariable("senderName", command.getSenderName());
            ctx.setVariable("message", command.getMessage());
            ctx.setVariable("documentTitle", command.getDocumentTitle());
            ctx.setVariable("documentLink", command.getDocumentId() != null 
                ? linkDomain + "documents/" + command.getDocumentId() 
                : linkDomain + "tasks/");

            String html = templateEngine.process("admin_message_notification", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(command.getRecipientEmail());
            helper.setSubject("[CoWorks] 관리자로부터 메시지가 도착했습니다.");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("관리자 메시지 메일 전송 실패", e);
        }
    }
    
    /**
     * 서명 요청 이메일 전송 (익명 사용자용 - 토큰 포함)
     */
    @Async
    public void sendSigningRequestEmail(
        String signerEmail, 
        String signerName, 
        String documentTitle, 
        String signingUrl,
        java.time.LocalDateTime expiresAt
    ) {
        try {
            Context ctx = new Context();
            ctx.setVariable("signerName", signerName);
            ctx.setVariable("documentTitle", documentTitle);
            ctx.setVariable("signingUrl", signingUrl);
            ctx.setVariable("expiresAt", fmt.format(expiresAt));

            String html = templateEngine.process("signing_request_email", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(signerEmail);
            helper.setSubject("[CoWorks] '" + documentTitle + "' 문서의 서명이 요청되었습니다.");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("서명 요청 메일 전송 실패", e);
        }
    }

    /**
     * 마감일 임박 알림 메일 전송 (작성 중인 문서 대상)
     */
    @Async
    public void sendDeadlineReminderNotification(MailRequest.DeadlineReminderEmailCommand command) {
        try {
            Context ctx = new Context();
            ctx.setVariable("documentTitle", command.getDocumentTitle());
            ctx.setVariable("actionLink", linkDomain + "documents/" + command.getDocumentId() + "/edit");
            ctx.setVariable("editorName", command.getEditorName());
            ctx.setVariable("deadline", command.getDeadline() != null ? fmt.format(command.getDeadline()) : null);

            String html = templateEngine.process("deadline_reminder_notification", ctx);

            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setTo(command.getEditorEmail());
            helper.setSubject("[CoWorks] '" + command.getDocumentTitle() + "' 문서의 마감일이 내일입니다.");
            helper.setText(html, true);

            mailSender.send(mime);
        } catch (Exception e) {
            throw new RuntimeException("마감일 알림 메일 전송 실패", e);
        }
    }
}
