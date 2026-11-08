package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService users(Database db) {
    return name ->
        db
            .rows("SELECT * FROM users WHERE username=? AND enabled=1 AND kind<>'AGENT'", name)
            .stream()
            .findFirst()
            .map(
                u ->
                    User.withUsername((String) u.get("username"))
                        .password((String) u.get("password_hash"))
                        .roles((String) u.get("kind"))
                        .build())
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  @Order(1)
  SecurityFilterChain mcp(
      HttpSecurity http, AgentTokens tokens, @Value("${tracker.public-origin}") String origin)
      throws Exception {
    return http.securityMatcher("/mcp", "/mcp/**")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) ->
                        json(
                            res,
                            401,
                            "UNAUTHENTICATED",
                            "A valid agent bearer token is required.")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected boolean shouldNotFilterAsyncDispatch() {
                return false;
              }

              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                String suppliedOrigin = req.getHeader("Origin");
                if (suppliedOrigin != null && !origin.equals(suppliedOrigin)) {
                  json(res, 403, "ORIGIN", "Origin is not allowed.");
                  return;
                }
                String header = req.getHeader("Authorization");
                try {
                  if (header != null && header.startsWith("Bearer ")) {
                    Actor actor = tokens.authenticate(header.substring(7));
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(actor, null, List.of()));
                  }
                  chain.doFilter(req, res);
                } catch (Problem ex) {
                  json(res, 401, "INVALID_TOKEN", "Agent token is invalid, expired or revoked.");
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain browser(HttpSecurity http) throws Exception {
    String contentSecurityPolicy = browserContentSecurityPolicy();
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(dev.forge.tracker.common.PageController.PAGE_PATH)
                    .permitAll()
                    .requestMatchers(
                        "/api/csrf",
                        "/api/login",
                        "/api/register",
                        "/actuator/health",
                        "/",
                        "/index.html",
                        "/_app/**",
                        "/favicon.svg")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler((q, r, a) -> json(r, 200, "OK", "Signed in."))
                    .failureHandler(
                        (q, r, e) ->
                            json(r, 401, "INVALID_CREDENTIALS", "Invalid username or password.")))
        .logout(
            l ->
                l.logoutUrl("/api/logout")
                    .logoutSuccessHandler((q, r, a) -> json(r, 200, "OK", "Signed out.")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, ex) -> json(r, 401, "UNAUTHENTICATED", "Please sign in."))
                    .accessDeniedHandler(
                        (q, r, ex) ->
                            json(
                                r,
                                403,
                                ex instanceof org.springframework.security.web.csrf.CsrfException
                                    ? "CSRF_EXPIRED"
                                    : "FORBIDDEN",
                                ex instanceof org.springframework.security.web.csrf.CsrfException
                                    ? "Your session token expired. Please retry."
                                    : "You do not have permission for this action.")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(contentSecurityPolicy)))
        .build();
  }

  private static String browserContentSecurityPolicy() throws Exception {
    // Each prerendered page and the client fallback have their own generated bootstrap.
    // API-only development can run beside Vite without a packaged frontend.
    var hashes = new LinkedHashSet<String>();
    var scriptPattern = Pattern.compile("<script\\b[^>]*>(.*?)</script>", Pattern.DOTALL);
    for (var page : new PathMatchingResourcePatternResolver().getResources("classpath*:static/**/*.html")) {
      var scripts = scriptPattern.matcher(page.getContentAsString(StandardCharsets.UTF_8));
      while (scripts.find()) {
        String script = scripts.group(1);
        if (!script.isBlank()) {
          byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
          hashes.add(" 'sha256-" + Base64.getEncoder().encodeToString(digest) + "'");
        }
      }
    }
    return "default-src 'self'; script-src 'self'" + String.join("", hashes)
        + "; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self';"
        + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
  }

  private static void json(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
