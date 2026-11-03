package dev.chzzk.vod.user;

/** 응답에 넣는 공개 사용자 정보 (방송 소유자, 댓글 작성자). */
public record UserSummary(Long id, String nickname) {

	public static UserSummary of(User user) {
		return user == null ? null : new UserSummary(user.getId(), user.getNickname());
	}

	public static UserSummary of(LoginUser user) {
		return user == null ? null : new UserSummary(user.getId(), user.getNickname());
	}

}
