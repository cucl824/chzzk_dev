package dev.chzzk.vod.comment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	List<Comment> findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(CommentTarget targetType, String targetId);

	@Modifying
	@Query("delete from Comment c where c.targetType = :type and c.targetId = :id")
	void deleteAllByTarget(@Param("type") CommentTarget type, @Param("id") String id);

}
