package ru.itmo.advisor.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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

    private final AdvisorProperties props;

    public SecurityFilter(AdvisorProperties props) {
        this.props = props;
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
