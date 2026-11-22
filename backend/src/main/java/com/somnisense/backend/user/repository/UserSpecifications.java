package com.somnisense.backend.user.repository;

import com.somnisense.backend.user.entity.Role;
import com.somnisense.backend.user.entity.User;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Optional filters for the staff list, built as JPA "Specifications" - small reusable WHERE-clause pieces.
 * A filter that was not supplied simply adds no condition. Combined in UserService with .and(...).
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> hasRole(Role role) {
        return (root, query, cb) -> role == null ? null : cb.equal(root.get("role"), role);
    }

    public static Specification<User> isActive(Boolean active) {
        return (root, query, cb) -> active == null ? null : cb.equal(root.get("active"), active);
    }

    /** Case-insensitive "contains" search on username or email. % and _ typed by the user are matched literally. */
    public static Specification<User> matchesText(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String escaped = text.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            return cb.or(cb.like(root.get("username"), pattern, '\\'),
                    cb.like(root.get("email"), pattern, '\\'));
        };
    }
}
