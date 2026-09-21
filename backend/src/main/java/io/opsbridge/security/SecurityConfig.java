package io.opsbridge.security;

import io.opsbridge.security.jwt.JwtAuthFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// Enforced twice on purpose (spec 3.2): matchers here plus @PreAuthorize on every method -- losing either one still fails closed on the other.
// See RestSecurityResponses for the 401-vs-403 handling, and GlobalExceptionHandler for when @PreAuthorize denies inside the controller.
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;
    private final RestSecurityResponses restSecurityResponses;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(
            @Value("${opsbridge.cors.allowed-origins}") List<String> allowedOrigins,
            RestSecurityResponses restSecurityResponses,
            JwtAuthFilter jwtAuthFilter) {
        this.allowedOrigins = allowedOrigins;
        this.restSecurityResponses = restSecurityResponses;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Stateless bearer auth, no cookies/session -- CSRF adds nothing here.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restSecurityResponses)
                        .accessDeniedHandler(restSecurityResponses))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login", "/actuator/health")
                        .permitAll()
                        // permitAll here because the browser can't attach a bearer token to a websocket upgrade.
                        // Auth still happens in-band on the first frame (see LiveEventSocketHandler); nothing flows until it succeeds.
                        .requestMatchers("/ws/events")
                        .permitAll()
                        // Reads are open to any authenticated role.
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents", "/api/v1/incidents/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/services", "/api/v1/services/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/dashboard/**")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/**")
                        .authenticated()
                        // Incident mutations exclude Viewers.
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents", "/api/v1/incidents/**")
                        .hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/incidents/**")
                        .hasAnyRole("OPERATOR", "ADMIN")
                        // Service catalog management is Admin-only: Operators are excluded too.
                        .requestMatchers(HttpMethod.POST, "/api/v1/services", "/api/v1/services/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/services/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
