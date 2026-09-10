package com.potg.don.global.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.potg.don.auth.jwt.JwtAuthenticationFilter;
import com.potg.don.auth.oauth.CustomOAuth2UserService;
import com.potg.don.auth.oauth.OAuth2SuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final CustomOAuth2UserService oAuth2UserService;
	private final OAuth2SuccessHandler successHandler;
	private final JwtAuthenticationFilter jwtFilter;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(c -> c.configurationSource(corsConfigurationSource()))
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)

			.authorizeHttpRequests(auth -> auth
				// 프리플라이트는 무조건 허용
				.requestMatchers(HttpMethod.OPTIONS, "/**")
				.permitAll()
				.requestMatchers("/login/**", "/oauth2/**", "/auth/reissue", "/test", "/v3/api-docs/**",
					"/swagger-ui/**", "/swagger-ui.html")
				.permitAll()
				.requestMatchers("/auth/logout")
				.authenticated()
				.anyRequest()
				.authenticated())

			// ★ 핵심: 리다이렉트 대신 401/403 + CORS 헤더 보장
			.exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
				addCorsHeaders(req, res); // CORS 보장
				writeErrorJson(res, 401, "Unauthorized", "Access token이 필요합니다.");
			}).accessDeniedHandler((req, res, e) -> {
				addCorsHeaders(req, res);
				writeErrorJson(res, 403, "Forbidden", "권한이 없습니다.");
			}))

			.oauth2Login(o -> o.userInfoEndpoint(u -> u.userService(oAuth2UserService)).successHandler(successHandler)
				// (선택) 로그인 페이지 경로를 명시해, 혹시라도 기본 로그인 페이지 개입 소지 축소
				//.loginPage("/oauth2/authorization/ssafy")
			);

		http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	// 공용 CORS 헤더 주입 (에러 응답에서도 사용)
	private void addCorsHeaders(HttpServletRequest req, HttpServletResponse res) {
		String origin = req.getHeader("Origin");
		if (origin != null) {
			res.setHeader("Access-Control-Allow-Origin", origin);
			res.setHeader("Vary", "Origin");
			res.setHeader("Access-Control-Allow-Credentials", "true");
			res.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,Accept,X-Requested-With,Origin");
			res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
			res.setHeader("Access-Control-Expose-Headers", "Location,Content-Disposition");
		}
	}

	private static void writeErrorJson(HttpServletResponse res, int status, String error, String message) throws
		IOException {
		res.setStatus(status);
		res.setContentType("application/json;charset=UTF-8");
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("error", error);
		body.put("message", message);
		body.put("status", status);
		new com.fasterxml.jackson.databind.ObjectMapper().writeValue(res.getWriter(), body);
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173", "http://localhost:8080",
			"https://j13a409.p.ssafy.io", "https://j13a409.p.ssafy.io:3002"));
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

		// ★ 여기 변경: 프리플라이트 실패 줄이기
		// 브라우저가 보내는 Access-Control-Request-Headers를 포괄 허용
		config.setAllowedHeaders(List.of("*"));

		// 필요 시 노출 헤더
		config.setExposedHeaders(List.of("Location", "Content-Disposition"));

		config.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
