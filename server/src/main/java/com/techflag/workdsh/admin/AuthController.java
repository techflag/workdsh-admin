package com.techflag.workdsh.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final boolean secureCookie;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public AuthController(AuthService auth, org.springframework.jdbc.core.JdbcTemplate db, @Value("${workdsh.web.origin:http://127.0.0.1:18891}") String webOrigin) {
        this.auth = auth;
        this.db = db;
        this.secureCookie = webOrigin.startsWith("https://");
    }

    public record Credentials(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=512) String password) {}
    public record PasswordChange(@NotBlank String currentPassword, @Size(min=12) String newPassword) {}

    @PostMapping("/login")
    public AuthService.Login login(@Valid @RequestBody Credentials credentials) {
        return auth.login(credentials.email(), credentials.password());
    }
    @PostMapping("/browser-login")
    public AuthService.Actor browserLogin(@Valid @RequestBody Credentials credentials, HttpServletResponse response) {
        var login = auth.login(credentials.email(), credentials.password());
        response.addHeader("Set-Cookie", cookie(login.token(), 43_200));
        return login.member();
    }
    public record Account(String id, String organizationId, String organizationName, String email, String displayName, String role, boolean mustChangePassword) {}
    @GetMapping("/me")
    public Account me(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        var organizationName = db.queryForObject("select name from organizations where id=?", String.class, actor.organizationId());
        return new Account(actor.id(), actor.organizationId(), organizationName, actor.email(), actor.displayName(), actor.role(), actor.mustChangePassword());
    }
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestHeader(value="Authorization", required=false) String bearer,
                               @Valid @RequestBody PasswordChange change, HttpServletResponse response) {
        auth.changePassword(auth.actor(bearer), change.currentPassword(), change.newPassword());
        response.addHeader("Set-Cookie", cookie("", 0));
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value="Authorization", required=false) String bearer,
                       HttpServletResponse response) {
        auth.logout(bearer);
        response.addHeader("Set-Cookie", cookie("", 0));
    }

    private String cookie(String token, int maxAge) {
        return BrowserSessionFilter.COOKIE_NAME + "=" + token + "; Path=/api; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Strict" + (secureCookie ? "; Secure" : "");
    }
}
