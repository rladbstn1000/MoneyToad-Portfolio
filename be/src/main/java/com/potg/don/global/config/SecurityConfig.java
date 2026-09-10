package com.potg.don.global.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
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
import com.potg.don.auth.demo.DemoAuthHttpConfiguration;
import com.potg.don.auth.oauth.CustomOAuth2UserService;
import com.potg.don.auth.oauth.OAuth2SuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Configuration
@Import(AuthProfileGuardConfiguration.class)
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtFilter;

	@Bean
	@Profile("!demo")
	public SecurityFilterChain filterChain(HttpSecurity http, CustomOAuth2UserService oAuth2UserService,
		OAuth2SuccessHandler successHandler) throws Exception {
		configureJwtSecurity(http, null);
		http.oauth2Login(o -> o.userInfoEndpoint(u -> u.userService(oAuth2UserService)).successHandler(successHandler));
		return http.build();
	}

	@Bean
	@Profile("demo")
	public SecurityFilterChain demoFilterChain(HttpSecurity http, DemoAuthHttpConfiguration.Settings settings) throws Exception {
		configureJwtSecurity(http, settings);
		return http.build();
	}

	private void configureJwtSecurity(HttpSecurity http, DemoAuthHttpConfiguration.Settings settings) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(c -> c.configurationSource(settings == null ? corsConfigurationSource() : DemoAuthHttpConfiguration.cors(settings)))
			.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)

			.authorizeHttpRequests(auth -> {
				if (settings != null) {
					auth.requestMatchers(HttpMethod.POST, "/auth/demo/login", "/auth/demo/reissue").permitAll();
					// Demo cards are installed internally. These original routes replace data and invoke AI.
					auth.requestMatchers(HttpMethod.POST, "/cards").denyAll()
						.requestMatchers(HttpMethod.PATCH, "/cards").denyAll()
						.requestMatchers(HttpMethod.DELETE, "/cards").denyAll();
				}
				auth
				// 프리플라이트는 무조건 허용
				.requestMatchers(HttpMethod.OPTIONS, "/**")
				.permitAll()
				.requestMatchers("/login/**", "/oauth2/**", "/auth/reissue", "/test", "/v3/api-docs/**",
					"/swagger-ui/**", "/swagger-ui.html")
				.permitAll()
				.requestMatchers("/auth/logout")
				.authenticated()
				.anyRequest()
				.authenticated();
			})

			// ★ 핵심: 리다이렉트 대신 401/403 + CORS 헤더 보장
			.exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
				if (settings == null) addCorsHeaders(req, res); // Demo keeps the exact CorsFilter headers.
				writeErrorJson(res, 401, "Unauthorized", "Access token이 필요합니다.");
			}).accessDeniedHandler((req, res, e) -> {
				if (settings == null) addCorsHeaders(req, res);
				writeErrorJson(res, 403, "Forbidden", "권한이 없습니다.");
			}));

		http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
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
