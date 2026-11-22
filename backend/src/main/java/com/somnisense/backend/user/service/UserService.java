package com.somnisense.backend.user.service;

import com.somnisense.backend.audit.service.AuditAction;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ConflictException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.common.exception.InvalidRequestException;
import com.somnisense.backend.common.exception.ResourceNotFoundException;
import com.somnisense.backend.common.response.PageResponse;
import com.somnisense.backend.security.TemporaryPasswordGenerator;
import com.somnisense.backend.user.dto.CreateUserRequest;
import com.somnisense.backend.user.dto.CreatedUserResponse;
import com.somnisense.backend.user.dto.TemporaryPasswordResponse;
import com.somnisense.backend.user.dto.UserResponse;
import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import com.somnisense.backend.user.repository.UserRepository;
import com.somnisense.backend.user.repository.UserSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Staff account management, used only by Admins (Stage 4).
 *
 * Business rules enforced here (not in the frontend):
 * - The last active Admin can never be deactivated or demoted (LAST_ACTIVE_ADMIN). The check locks the
 *   active-Admin rows first, so two Admins acting at the same moment cannot both succeed.
 * - An Admin cannot deactivate, demote or reset the password of their OWN account (SELF_ACTION_NOT_ALLOWED);
 *   they use "change my password" instead.
 * - Usernames/emails are unique (409 CONFLICT).
 * - Accounts are never deleted: audit_logs and other tables keep referring to them. Deactivate instead.
 * - Repeating an action that is already done (deactivating an inactive user, setting the same role)
 *   succeeds without changes and without a new audit row - safe for double clicks and retries.
 *
 * @Transactional on a method: everything inside commits together or not at all.
 */
@Service
public class UserService {

    /** Columns the staff list may be sorted by (API name -> entity field). */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "username", "username",
            "createdAt", "createdAt",
            "lastLoginAt", "lastLoginAt");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator passwordGenerator;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TemporaryPasswordGenerator passwordGenerator, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordGenerator = passwordGenerator;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Role role, Boolean active, String search,
                                           int page, int size, String sort, String direction) {
        String field = SORT_FIELDS.get(sort);
        if (field == null) {
            throw new InvalidRequestException("sort must be one of " + SORT_FIELDS.keySet());
        }
        if (!DIRECTIONS.contains(direction)) {
            throw new InvalidRequestException("direction must be 'asc' or 'desc'");
        }
        Sort order = Sort.by(Sort.Direction.fromString(direction), field).and(Sort.by("id"));   // id = stable tie-break
        Specification<User> filter = Specification.allOf(
                UserSpecifications.hasRole(role),
                UserSpecifications.isActive(active),
                UserSpecifications.matchesText(search));
        return PageResponse.from(userRepository.findAll(filter, PageRequest.of(page, size, order)),
                UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(findOrThrow(id));
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public CreatedUserResponse create(CreateUserRequest request, UUID actorId) {
        String username = User.normalizeUsername(request.getUsername());
        String email = User.normalizeEmail(request.getEmail());
        if (userRepository.existsByUsername(username)) {
            throw new ConflictException("Username is already taken");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already in use");
        }

        String temporaryPassword = passwordGenerator.generate();
        User user = new User(username, email, passwordEncoder.encode(temporaryPassword), request.getRole());
        userRepository.save(user);
        auditService.record(actorId, AuditAction.USER_CREATED, "USER", user.getId(),
                Map.of("role", user.getRole().name()));

        return new CreatedUserResponse(UserResponse.from(user), temporaryPassword);
    }

    // ---------------------------------------------------------------- change role

    @Transactional
    public UserResponse changeRole(UUID id, Role newRole, UUID actorId) {
        rejectSelf(id, actorId, "You cannot change your own role");
        List<User> activeAdmins = userRepository.lockActiveByRole(Role.ADMIN);   // lock FIRST, then read target
        User user = findOrThrow(id);
        Role oldRole = user.getRole();
        if (oldRole == newRole) {
            return UserResponse.from(user);   // nothing to do
        }
        if (oldRole == Role.ADMIN && user.isActive()) {
            ensureAnotherActiveAdminRemains(activeAdmins, user);
        }
        user.changeRole(newRole);
        auditService.record(actorId, AuditAction.USER_ROLE_CHANGED, "USER", user.getId(),
                Map.of("from", oldRole.name(), "to", newRole.name()));
        return UserResponse.from(user);
    }

    // ---------------------------------------------------------------- activate / deactivate

    @Transactional
    public UserResponse deactivate(UUID id, UUID actorId) {
        rejectSelf(id, actorId, "You cannot deactivate your own account");
        List<User> activeAdmins = userRepository.lockActiveByRole(Role.ADMIN);
        User user = findOrThrow(id);
        if (!user.isActive()) {
            return UserResponse.from(user);
        }
        if (user.getRole() == Role.ADMIN) {
            ensureAnotherActiveAdminRemains(activeAdmins, user);
        }
        user.deactivate();
        auditService.record(actorId, AuditAction.USER_DEACTIVATED, "USER", user.getId(), null);
        // The user's open session ends on their next request (ActiveUserCheckFilter).
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse reactivate(UUID id, UUID actorId) {
        User user = findOrThrow(id);
        if (user.isActive()) {
            return UserResponse.from(user);
        }
        user.activate();
        auditService.record(actorId, AuditAction.USER_REACTIVATED, "USER", user.getId(), null);
        return UserResponse.from(user);
    }

    // ---------------------------------------------------------------- password reset

    @Transactional
    public TemporaryPasswordResponse resetPassword(UUID id, UUID actorId) {
        rejectSelf(id, actorId, "Use 'change my password' for your own account");
        User user = findOrThrow(id);
        String temporaryPassword = passwordGenerator.generate();
        user.changePasswordHash(passwordEncoder.encode(temporaryPassword));
        auditService.record(actorId, AuditAction.USER_PASSWORD_RESET, "USER", user.getId(), null);
        // The user's existing sessions end on their next request (password fingerprint changed).
        return new TemporaryPasswordResponse(temporaryPassword);
    }

    // ---------------------------------------------------------------- helpers

    private User findOrThrow(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private static void rejectSelf(UUID targetId, UUID actorId, String message) {
        if (targetId.equals(actorId)) {
            throw new ApiException(ErrorCode.SELF_ACTION_NOT_ALLOWED, message);
        }
    }

    /** activeAdmins was loaded (and locked) in this transaction, so it reflects committed reality. */
    private static void ensureAnotherActiveAdminRemains(List<User> activeAdmins, User leaving) {
        long others = activeAdmins.stream().filter(admin -> !admin.getId().equals(leaving.getId())).count();
        if (others == 0) {
            throw new ApiException(ErrorCode.LAST_ACTIVE_ADMIN);
        }
    }
}
