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
    public AuthController(AuthService auth, @Value("${workdsh.web.origin:http://127.0.0.1:18891}") String webOrigin) {
        this.auth = auth;
        this.secureCookie = webOrigin.startsWith("https://");
    }

    public record Credentials(@Email @NotBlank String email, @NotBlank String password) {}
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
    @GetMapping("/me")
    public AuthService.Actor me(@RequestHeader(value="Authorization", required=false) String bearer) {
        return auth.actor(bearer);
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
