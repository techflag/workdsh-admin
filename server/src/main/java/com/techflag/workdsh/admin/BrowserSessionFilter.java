package com.techflag.workdsh.admin;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the browser's HttpOnly session to existing API auth without exposing it to JavaScript. */
@Component
public class BrowserSessionFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "workdsh-admin-session";
    private final String webOrigin;

    public BrowserSessionFilter(@Value("${workdsh.web.origin:http://127.0.0.1:18891}") String webOrigin) {
        this.webOrigin = webOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) { chain.doFilter(request, response); return; }
        String token = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName())) { token = cookie.getValue(); break; }
            }
        }
        boolean browserLogin = path.equals("/api/auth/browser-login");
        if (browserLogin || token != null) {
            String site = request.getHeader("Sec-Fetch-Site");
            if ("cross-site".equals(site)) { response.sendError(403); return; }
            if (!isRead(request.getMethod()) && !webOrigin.equals(request.getHeader("Origin"))) {
                response.sendError(403); return;
            }
        }
        if (token == null || request.getHeader("Authorization") != null) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = "Bearer " + token;
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return "Authorization".equalsIgnoreCase(name) ? authorization : super.getHeader(name);
            }
            @Override public Enumeration<String> getHeaders(String name) {
                return "Authorization".equalsIgnoreCase(name)
                        ? Collections.enumeration(Set.of(authorization)) : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                Set<String> names = new LinkedHashSet<>();
                super.getHeaderNames().asIterator().forEachRemaining(names::add);
                names.add("Authorization");
                return Collections.enumeration(names);
            }
        }, response);
    }

    private static boolean isRead(String method) { return method.equals("GET") || method.equals("HEAD"); }
}
