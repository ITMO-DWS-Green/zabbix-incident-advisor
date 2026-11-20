package ru.itmo.advisor.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Простая защита:
 * <ul>
 *   <li>{@code /zabbix/connector/**} — коннектор Zabbix, {@code Authorization: Bearer <connector-token>};</li>
 *   <li>{@code /api/v1/**} — приём разметки от test-server, без токена (только для стенда);</li>
 *   <li>{@code /api/**} — модуль интерфейса Zabbix, заголовок {@code X-Advisor-Token: <ui-token>}.</li>
 * </ul>
 * Пустой токен в настройках выключает проверку.
 */
@Component
public class SecurityFilter extends OncePerRequestFilter {

    public static final String UI_TOKEN_HEADER = "X-Advisor-Token";
    public static final String USER_HEADER = "X-Advisor-User";
    private static final String RETENTION_TOKEN_HEADER = "X-Advisor-Retention-Token";

    private final AdvisorProperties props;
    private final String retentionToken;
    private final Path retentionTokenFile;

    public SecurityFilter(AdvisorProperties props,
                          @Value("${ADVISOR_RETENTION_ADMIN_TOKEN:}") String retentionToken,
                          @Value("${ADVISOR_RETENTION_ADMIN_TOKEN_FILE:/advisor-admin/token}") String retentionTokenFile) {
        this.props = props;
        this.retentionToken = retentionToken;
        this.retentionTokenFile = Path.of(retentionTokenFile);
    }

    public boolean canManageRetention(HttpServletRequest request) {
        String supplied = request.getHeader(RETENTION_TOKEN_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return false;
        }
        try {
            String token = retentionToken.isBlank() ? Files.readString(retentionTokenFile).trim() : retentionToken;
            return !token.isBlank() && matches(token, supplied);
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean allowed = true;
        if (path.startsWith("/zabbix/connector/")) {
            allowed = matches(props.security().connectorToken(), bearer(request.getHeader("Authorization")));
        } else if (path.startsWith("/api/") && !path.startsWith("/api/v1/")) {
            allowed = matches(props.security().uiToken(), request.getHeader(UI_TOKEN_HEADER));
        }
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    private static boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
