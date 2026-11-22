package com.somnisense.backend.auth.dto;

/**
 * Response of GET /api/v1/auth/csrf: {"headerName":"X-XSRF-TOKEN","token":"..."}.
 * The same token is also in the XSRF-TOKEN cookie; send it back in the X-XSRF-TOKEN header on every
 * POST/PUT/PATCH/DELETE (axios does this automatically when withXSRFToken is enabled).
 */
public class CsrfTokenResponse {

    private final String headerName;
    private final String token;

    public CsrfTokenResponse(String headerName, String token) {
        this.headerName = headerName;
        this.token = token;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getToken() {
        return token;
    }
}
