package com.hanghae.onemanitnews.common.config;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsUtils;

import com.hanghae.onemanitnews.common.jwt.JwtAccessUtil;
import com.hanghae.onemanitnews.common.jwt.JwtRefreshUtil;
import com.hanghae.onemanitnews.common.redis.RedisAuthFilter;
import com.hanghae.onemanitnews.common.redis.RedisTokenUtil;
import com.hanghae.onemanitnews.common.security.CustomAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@EnableWebSecurity
@Configuration
public class WebSecurityConfig {
	private final RedisTokenUtil redisTokenUtil;
	private final RedisTemplate<String, String> redisTemplate;
	private final JwtAccessUtil jwtAccessUtil;
	private final JwtRefreshUtil jwtRefreshUtil;
	private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	} //암호강도 12설정(기본 10, 최소 4, 최대 31)

	@Bean
	public WebSecurityCustomizer webSecurityCustomizer() {
		// h2-console 사용 및 resources 접근 허용 설정 => 정적 리소스 필터 제외하여 서버 부하 줄임
		return (web) -> web.ignoring()
			//.requestMatchers(PathRequest.toH2Console())
			.requestMatchers(PathRequest.toStaticResources().atCommonLocations());
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// CSRF(Cross-site request forgery) 비활성화 설정
		// 공격자가 인증된 브라우저에 저장된 쿠키의 세션 정보를 활용하여 웹 서버에 사용자가 의도하지 않은 요청을 전달하는 것
		http.csrf().disable();

		// Session 방식 비활성화
		http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);

		// .authorizeRequests() : 요청에 대한 권한을 지정
		http.authorizeRequests()
			//.antMatchers(HttpMethod.GET, "/api/v1/news/list").permitAll()
			.requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
			.antMatchers(HttpMethod.POST, "/api/v1/member/signup").permitAll()
			.antMatchers(HttpMethod.POST, "/api/v1/member/login").permitAll()
			.anyRequest().authenticated(); //나머진 토큰 필요

		// Redis Auth Filter 등록 - UPAF필터보다 먼저 검사 진행
		http.addFilterBefore(new RedisAuthFilter(redisTokenUtil, redisTemplate, jwtAccessUtil, jwtRefreshUtil),
			UsernamePasswordAuthenticationFilter.class);

		// 401 Error, 인증 실패 - RedisAuthFilter 통과 후 SecurityContextHolder 인증객체 검증 실패 시 발생
		http.exceptionHandling().authenticationEntryPoint(customAuthenticationEntryPoint);

		// 403 Error, 권한 오류
		// http.exceptionHandling().accessDeniedHandler(customAccessDeniedHandler);

		return http.build();
	}
}
