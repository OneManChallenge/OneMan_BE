package com.hanghae.onemanitnews.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hanghae.onemanitnews.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
}