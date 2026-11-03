package dev.chzzk.vod.broadcast;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BroadcastRepository extends JpaRepository<Broadcast, String> {

	List<Broadcast> findAllByOrderByCreatedAtDesc();

	/** 동시에 여러 명이 재생해도 조회수가 빠지지 않도록 DB에서 바로 증가시킨다. */
	@Modifying(clearAutomatically = true)
	@Query("update Broadcast b set b.viewCount = b.viewCount + 1 where b.id = :id")
	int incrementViewCount(@Param("id") String id);

}
