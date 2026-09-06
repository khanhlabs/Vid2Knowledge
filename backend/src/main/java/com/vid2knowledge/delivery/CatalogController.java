package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogController {

    private final TenantAccessService access;
    private final CatalogService catalog;

    public CatalogController(TenantAccessService access, CatalogService catalog) {
        this.access = access;
        this.catalog = catalog;
    }

    @GetMapping("/courses")
    public java.util.List<CatalogService.Course> courses(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        reader(organizationId, authentication);
        return catalog.courses(organizationId);
    }

    @GetMapping("/courses/{courseId}")
    public CatalogService.CourseDetail course(
            @PathVariable UUID organizationId, @PathVariable UUID courseId, Authentication authentication
    ) {
        reader(organizationId, authentication);
        return catalog.course(organizationId, courseId);
    }

    @GetMapping("/cohorts")
    public java.util.List<CatalogService.Cohort> cohorts(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        reader(organizationId, authentication);
        return catalog.cohorts(organizationId);
    }

    @GetMapping("/assignments")
    public java.util.List<CatalogService.Assignment> assignments(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        reader(organizationId, authentication);
        return catalog.assignments(organizationId);
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Course createCourse(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateCourse request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.createCourse(
                author(organizationId, authentication), request.title(), request.description(), correlation(servletRequest)
        );
    }

    @PostMapping("/courses/{courseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Module addModule(
            @PathVariable UUID organizationId,
            @PathVariable UUID courseId,
            @Valid @RequestBody AddModule request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.addModule(
                author(organizationId, authentication), courseId, request.title(), request.position(),
                correlation(servletRequest)
        );
    }

    @PostMapping("/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Lesson addLesson(
            @PathVariable UUID organizationId,
            @PathVariable UUID moduleId,
            @Valid @RequestBody AddLesson request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.addLesson(
                author(organizationId, authentication), moduleId, request.packageId(), request.title(),
                request.position(), correlation(servletRequest)
        );
    }

    @PostMapping("/cohorts")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Cohort createCohort(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateCohort request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.createCohort(
                manager(organizationId, authentication), request.name(), request.startsAt(), request.endsAt(),
                correlation(servletRequest)
        );
    }

    @PostMapping("/cohorts/{cohortId}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addCohortMember(
            @PathVariable UUID organizationId,
            @PathVariable UUID cohortId,
            @Valid @RequestBody AddCohortMember request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        catalog.addCohortMember(
                manager(organizationId, authentication), cohortId, request.userId(), correlation(servletRequest)
        );
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.Assignment createAssignment(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateAssignment request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.createAssignment(
                author(organizationId, authentication), request.cohortId(), request.lessonId(), request.title(),
                request.availableAt(), request.dueAt(), correlation(servletRequest)
        );
    }

    @PostMapping("/assignments/{assignmentId}/publish")
    public CatalogService.Assignment publishAssignment(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.publishAssignment(
                author(organizationId, authentication), assignmentId, correlation(servletRequest)
        );
    }

    @PostMapping("/program-launches")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.ProgramLaunch launchProgram(
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody LaunchProgramRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return catalog.launchProgram(
                author(organizationId, authentication), request.title(), request.packageId(), request.learnerIds(),
                request.availableAt(), request.dueAt(), idempotencyKey, correlation(servletRequest)
        );
    }

    private CurrentActor author(UUID organizationId, Authentication authentication) {
        return access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR
        );
    }

    private CurrentActor reader(UUID organizationId, Authentication authentication) {
        return access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
    }

    private CurrentActor manager(UUID organizationId, Authentication authentication) {
        return access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN
        );
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    public record CreateCourse(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 5000) String description
    ) {
    }

    public record AddModule(@NotBlank @Size(max = 240) String title, @Min(1) int position) {
    }

    public record AddLesson(
            @NotBlank @Size(max = 240) String title,
            @Min(1) int position,
            UUID packageId
    ) {
    }

    public record CreateCohort(
            @NotBlank @Size(max = 240) String name,
            Instant startsAt,
            Instant endsAt
    ) {
    }

    public record AddCohortMember(@NotNull UUID userId) {
    }

    public record CreateAssignment(
            @NotNull UUID cohortId,
            @NotNull UUID lessonId,
            @NotBlank @Size(max = 240) String title,
            @NotNull Instant availableAt,
            Instant dueAt
    ) {
    }

    public record LaunchProgramRequest(
            @NotBlank @Size(max = 240) String title,
            @NotNull UUID packageId,
            @NotEmpty @Size(max = 2000) java.util.List<@NotNull UUID> learnerIds,
            @NotNull Instant availableAt,
            Instant dueAt
    ) {}
}
