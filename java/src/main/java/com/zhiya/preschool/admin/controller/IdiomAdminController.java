package com.zhiya.preschool.admin.controller;

import com.zhiya.preschool.admin.config.RequireAdminPermission;
import com.zhiya.preschool.admin.service.AdminAccessService;
import com.zhiya.preschool.admin.service.IdiomMeaningGenerator;
import com.zhiya.preschool.admin.service.IdiomAdminService;
import com.zhiya.preschool.admin.service.IdiomAdminService.BatchPreview;
import com.zhiya.preschool.admin.service.IdiomAdminService.BatchRequest;
import com.zhiya.preschool.admin.service.IdiomAdminService.EntryPage;
import com.zhiya.preschool.admin.service.IdiomAdminService.EntryReview;
import com.zhiya.preschool.admin.service.IdiomAdminService.LevelDraft;
import com.zhiya.preschool.admin.service.IdiomAdminService.Preview;
import com.zhiya.preschool.admin.service.IdiomAdminService.PublishedLevel;
import com.zhiya.preschool.admin.service.IdiomAdminService.PublishedLevelPage;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 本机内容管理接口。开发代理必须通过回环地址访问后台。 */
@Profile("local")
@RestController
@RequestMapping("/api/admin")
public class IdiomAdminController {
    private static final String BATCH_DRAFT = "admin.idiomBatchDraft";
    private final IdiomAdminService service;
    private final IdiomMeaningGenerator meanings;

    public IdiomAdminController(IdiomAdminService service, IdiomMeaningGenerator meanings) {
        this.service = service;
        this.meanings = meanings;
    }

    @GetMapping("/idioms")
    @RequireAdminPermission(AdminAccessService.IDIOM_READ)
    public EntryPage search(
        @RequestParam(defaultValue = "") @Size(max = 64) String keyword,
        @RequestParam(required = false) @Min(1) @Max(4) Integer difficulty,
        @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int pageSize
    ) {
        return service.search(keyword, difficulty, page, pageSize);
    }

    @PostMapping(path = "/levels/preview", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public Preview preview(@Valid @RequestBody LevelDraft draft) {
        return service.preview(draft);
    }

    @GetMapping("/levels/published")
    @RequireAdminPermission(AdminAccessService.IDIOM_READ)
    public PublishedLevelPage publishedLevels(
        @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page
    ) {
        return service.publishedLevels(page);
    }

    @PostMapping(path = "/levels", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public PublishedLevel publish(
        @RequestParam @Positive int expectedLevelNo,
        @Valid @RequestBody LevelDraft draft
    ) {
        return service.publish(draft, expectedLevelNo);
    }

    @PostMapping(path = "/idioms/review", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public ResponseEntity<Void> approve(@Valid @RequestBody EntryReview review) {
        service.approveEntry(review);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/levels/batch/preview", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public BatchDraftView previewBatch(HttpSession session, @Valid @RequestBody BatchRequest request) {
        BatchPreview preview = service.previewBatch(request);
        UUID token = UUID.randomUUID();
        session.setAttribute(BATCH_DRAFT, new BatchDraft(token, request, preview));
        return new BatchDraftView(token, preview);
    }

    @PostMapping(path = "/levels/batch/publish", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public List<PublishedLevel> publishBatch(HttpSession session, @RequestBody BatchConfirmation confirmation) {
        BatchDraft draft = requireBatchDraft(session, confirmation.token());
        return service.publishBatch(draft.request(), draft.preview());
    }

    @PostMapping(path = "/levels/batch/meanings", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public Map<Long, String> generateMeanings(HttpSession session, @Valid @RequestBody MeaningRequest request) {
        BatchDraft draft = requireBatchDraft(session, request.token());
        var requested = new HashSet<>(request.entryIds());
        var pending = new HashSet<>(draft.preview().pendingEntryIds());
        var questions = draft.preview().levels().stream()
            .flatMap(level -> level.preview().questions().stream())
            .filter(question -> requested.contains(question.entryId())
                && pending.contains(question.entryId()) && question.meaning().isBlank())
            .toList();
        if (questions.size() != requested.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能为当前预览中缺释义的词条生成");
        }
        return meanings.generate(questions);
    }

    @PostMapping(path = "/levels/batch/review", consumes = "application/json")
    @RequireAdminPermission(AdminAccessService.LEVEL_PUBLISH)
    public ResponseEntity<Void> approveBatch(HttpSession session, @Valid @RequestBody BatchReview request) {
        BatchDraft draft = requireBatchDraft(session, request.token());
        var pending = new HashSet<>(draft.preview().pendingEntryIds());
        if (request.entries().stream().anyMatch(entry -> !pending.contains(entry.entryId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能确认当前预览中待审核的词条");
        }
        service.approveBatch(request.entries());
        return ResponseEntity.noContent().build();
    }

    private BatchDraft requireBatchDraft(HttpSession session, UUID token) {
        if (!(session.getAttribute(BATCH_DRAFT) instanceof BatchDraft draft) || !draft.token().equals(token)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "批量预览已失效，请重新生成");
        }
        return draft;
    }

    private record BatchDraft(UUID token, BatchRequest request, BatchPreview preview) {
    }

    public record BatchDraftView(UUID token, BatchPreview preview) {
    }

    public record BatchConfirmation(UUID token) {
    }

    public record MeaningRequest(
        @NotNull UUID token,
        @NotNull @Size(min = 1, max = 16) List<@NotNull @Positive Long> entryIds
    ) {
    }

    public record BatchReview(
        @NotNull UUID token,
        @NotNull @Size(min = 1, max = 300) List<@NotNull @Valid EntryReview> entries
    ) {
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleBusinessError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

}
