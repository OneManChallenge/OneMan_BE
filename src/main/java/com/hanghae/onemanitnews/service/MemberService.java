package com.hanghae.onemanitnews.service;

import static com.hanghae.onemanitnews.common.exception.CommonExceptionEnum.*;

import java.util.concurrent.TimeUnit;

import javax.persistence.LockModeType;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanghae.onemanitnews.common.exception.CommonException;
import com.hanghae.onemanitnews.common.exception.CommonExceptionEnum;
import com.hanghae.onemanitnews.common.jwt.JwtAccessUtil;
import com.hanghae.onemanitnews.common.jwt.JwtRefreshUtil;
import com.hanghae.onemanitnews.common.redis.RedisHashEnum;
import com.hanghae.onemanitnews.common.redis.RedisTokenUtil;
import com.hanghae.onemanitnews.common.security.dto.MemberRoleEnum;
import com.hanghae.onemanitnews.controller.request.LoginMemberRequest;
import com.hanghae.onemanitnews.controller.request.SaveMemberRequest;
import com.hanghae.onemanitnews.entity.Member;
import com.hanghae.onemanitnews.mapper.MemberMapper;
import com.hanghae.onemanitnews.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class MemberService {

	private final MemberRepository memberRepository;
	private final MemberMapper memberMapper;
	private final PasswordEncoder passwordEncoder;
	private final JwtAccessUtil jwtAccessUtil;
	private final JwtRefreshUtil jwtRefreshUtil;
	private final RedisTemplate<String, String> redisTemplate;

	private final RedisTokenUtil redisTokenUtil;

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_READ)
	public void signup(SaveMemberRequest saveMemberRequest) throws JsonProcessingException {
		//1. ?????? ?????? ?????? ??????
		Boolean isEmail = memberRepository.existsByEmail(saveMemberRequest.getEmail());

		if (isEmail == true) {
			throw new CommonException(CommonExceptionEnum.DUPLICATE_EMAIL);
		}

		//2. ???????????? ?????????
		String encryptPassword = passwordEncoder.encode(saveMemberRequest.getPassword());

		//3. member_id ????????? ??????(Node.js)
		HttpHeaders headers = new HttpHeaders();
		headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

		HttpEntity<String> requestEntity = new HttpEntity<>(headers);

		RestTemplate rt = new RestTemplate();

		ResponseEntity<String> response = rt.exchange(
			"http://172.30.1.1:3000/api/v1/member/count",
			HttpMethod.POST,
			requestEntity,
			String.class
		);

		String responseBody = response.getBody();

		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode jsonNode = objectMapper.readTree(responseBody);

		if (jsonNode.get("result").asText().equals("fail")) {
			throw new CommonException(NODE_JS_COUNT_FAIL);
		}

		//4. Dto -> Entity
		Member member = memberMapper.toEntity(saveMemberRequest.getEmail(), encryptPassword);

		//5. ????????????
		memberRepository.save(member);
	}

	@Transactional(readOnly = true)
	public void login(LoginMemberRequest loginMemberRequest, HttpServletResponse response) {
		/** 1. ?????? ?????? **/
		String email = loginMemberRequest.getEmail();
		String password = loginMemberRequest.getPassword();
		final String HASH_KEY_ACCESS_TOKEN = "accessToken";
		final String HASH_KEY_REFRESH_TOKEN = "refreshToken";

		/** 2. ?????? ?????? ?????? ?????? **/
		Member member = memberRepository.findByEmail(email).orElseThrow(
			() -> new CommonException(CommonExceptionEnum.MEMBER_NOT_FOUND)
		);

		/** 3. ???????????? ???????????? ?????? **/
		if (!passwordEncoder.matches(password, member.getPassword())) {
			throw new CommonException(CommonExceptionEnum.INCORRECT_PASSWORD);
		}

		/** 3. JWT ?????? ??? ?????? **/
		// 3-1. Access/Refresh JWT ??????
		String accessToken = jwtAccessUtil.createAccessToken(email, MemberRoleEnum.ONEMAN);
		String refreshToken = jwtRefreshUtil.createRefreshToken(email, MemberRoleEnum.ONEMAN);

		// 3-2. ????????? JWT??? Http Response ????????? ??????
		response.addHeader(jwtAccessUtil.ACCESS_HEADER, accessToken);
		response.addHeader(jwtRefreshUtil.REFRESH_HEADER, refreshToken);

		/** 4. Redis??? ????????? ?????? Set **/
		//4-1. key hash ?????????(MurmurHash)
		String emailHash = redisTokenUtil.createRedisHash(email, RedisHashEnum.EMAIL);
		String accessHash = redisTokenUtil.createRedisHash(accessToken, RedisHashEnum.ACCESS_TOKEN);
		String refreshHash = redisTokenUtil.createRedisHash(refreshToken, RedisHashEnum.REFRESH_TOKEN);

		//4-2. Redis Hash Key ?????? - ?????? ?????? ?????? ???????????? ?????? Key ?????? ??? ??????
		Object redisHgetAccessToken = redisTemplate.opsForHash().get(emailHash, HASH_KEY_ACCESS_TOKEN);
		Object redisHgetRefreshToken = redisTemplate.opsForHash().get(emailHash, HASH_KEY_REFRESH_TOKEN);

		if (redisHgetRefreshToken != null) { // 4-3??? ????????? ?????? : redisHgetAccessToken != null
			redisTemplate.delete(redisHgetAccessToken.toString());
			redisTemplate.delete(redisHgetRefreshToken.toString());
		}

		//4-3. ????????? Redis Hash key-value ?????? - ????????? ?????? ????????? - ??????????????? refresh ?????? ??????
		redisTemplate.opsForHash().put(emailHash, HASH_KEY_ACCESS_TOKEN, accessHash);
		redisTemplate.opsForHash().put(emailHash, HASH_KEY_REFRESH_TOKEN, refreshHash);
		redisTemplate.expire(emailHash, jwtRefreshUtil.REFRESH_TOKEN_TIME, TimeUnit.MILLISECONDS);

		//4-4. ?????? ????????? AccessToken/RefreshToken Redis ??????
		ValueOperations<String, String> vop = redisTemplate.opsForValue();
		vop.set(accessHash, "available", jwtAccessUtil.ACCESS_TOKEN_TIME, TimeUnit.MILLISECONDS);
		vop.set(refreshHash, "available", jwtRefreshUtil.REFRESH_TOKEN_TIME, TimeUnit.MILLISECONDS);
	}

	@Transactional
	public void logout(UserDetails userDetails, HttpServletRequest request) {
		//1. ???????????? ?????? ????????????
		String accessToken = redisTokenUtil.getHeaderAccessToken(request);
		String refreshToken = redisTokenUtil.getHeaderRefreshToken(request);

		//2. Redis key ????????? ?????? hash ?????????(MurmurHash)
		String emailHash = redisTokenUtil.createRedisHash(userDetails.getUsername(), RedisHashEnum.EMAIL);
		String accessHash = redisTokenUtil.createRedisHash(accessToken, RedisHashEnum.ACCESS_TOKEN);
		String refreshHash = redisTokenUtil.createRedisHash(refreshToken, RedisHashEnum.REFRESH_TOKEN);

		//3. Redis Key ??????
		redisTemplate.delete(emailHash);
		redisTemplate.delete(accessHash);
		redisTemplate.delete(refreshHash);
	}
}