package dev.forge.tracker.auth;

import dev.forge.tracker.common.Problem;
import dev.forge.tracker.persistence.Database;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
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
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/csrf",
                        "/api/login",
                        "/actuator/health",
                        "/",
                        "/index.html",
                        "/assets/**",
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
                                "FORBIDDEN",
                                "Access denied or CSRF token missing. Refresh and retry.")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'"
                                + " 'unsafe-inline'; img-src 'self' blob:; connect-src 'self';"
                                + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
        .build();
  }

  private static void json(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
  }
}
