package dev.chzzk.vod.comment;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.chzzk.vod.broadcast.BroadcastRepository;
import dev.chzzk.vod.broadcast.BroadcastService;
import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.live.LiveService;
import dev.chzzk.vod.live.LiveStreamRepository;
import dev.chzzk.vod.user.LoginUser;
import dev.chzzk.vod.user.User;
import dev.chzzk.vod.user.UserService;

/**
 * 방송별 댓글. 목록은 누구나 조회하고, 작성은 로그인 사용자만, 수정·삭제는 작성자 본인만 할 수 있다.
 */
@Service
public class CommentService {

	private final CommentRepository repository;
	private final BroadcastRepository broadcasts;
	private final LiveStreamRepository lives;
	private final UserService userService;

	public CommentService(CommentRepository repository, BroadcastRepository broadcasts, LiveStreamRepository lives,
			UserService userService) {
		this.repository = repository;
		this.broadcasts = broadcasts;
		this.lives = lives;
		this.userService = userService;
	}

	@Transactional(readOnly = true)
	public List<Comment> list(CommentTarget type, String targetId) {
		requireTarget(type, targetId);
		return repository.findByTargetTypeAndTargetIdOrderByCreatedAtAscIdAsc(type, targetId);
	}

	/** 비로그인 → 401. 작성자는 요청 본문이 아니라 로그인 세션의 사용자로 정한다. */
	@Transactional
	public Comment create(LoginUser me, CommentTarget type, String targetId, String rawContent) {
		User author = userService.require(me);
		requireTarget(type, targetId);
		return repository.save(new Comment(type, targetId, author, validContent(rawContent)));
	}

	@Transactional
	public Comment edit(LoginUser me, Long id, String rawContent) {
		Comment comment = requireAuthor(me, id);
		comment.edit(validContent(rawContent));
		return comment;
	}

	@Transactional
	public void delete(LoginUser me, Long id) {
		repository.delete(requireAuthor(me, id));
	}

	@EventListener
	@Transactional
	public void onBroadcastDeleted(BroadcastService.Deleted event) {
		repository.deleteAllByTarget(CommentTarget.BROADCAST, event.broadcastId());
	}

	@EventListener
	@Transactional
	public void onLiveDeleted(LiveService.Deleted event) {
		repository.deleteAllByTarget(CommentTarget.LIVE, event.liveId());
	}

	/** 비로그인 → 401, 다른 사용자 → 403 */
	private Comment requireAuthor(LoginUser me, Long id) {
		if (me == null) {
			throw ApiException.unauthorized();
		}
		Comment comment = repository.findById(id).orElseThrow(() -> ApiException.notFound("댓글을 찾을 수 없습니다."));
		if (!comment.isWrittenBy(me.getId())) {
			throw ApiException.forbidden("댓글 작성자만 수정·삭제할 수 있습니다.");
		}
		return comment;
	}

	private void requireTarget(CommentTarget type, String targetId) {
		boolean exists = type == CommentTarget.BROADCAST ? broadcasts.existsById(targetId) : lives.existsById(targetId);
		if (!exists) {
			throw ApiException.notFound("방송을 찾을 수 없습니다.");
		}
	}

	private static String validContent(String raw) {
		String content = raw == null ? "" : raw.trim();
		if (content.isEmpty()) {
			throw ApiException.badRequest("댓글 내용을 입력하세요.");
		}
		if (content.length() > Comment.CONTENT_MAX) {
			throw ApiException.badRequest("댓글은 " + Comment.CONTENT_MAX + "자 이하로 입력하세요.");
		}
		return content;
	}

}
