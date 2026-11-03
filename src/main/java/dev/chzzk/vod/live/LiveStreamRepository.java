package dev.chzzk.vod.live;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveStreamRepository extends JpaRepository<LiveStream, String> {

	List<LiveStream> findAllByOrderByStartedAtDesc();

	List<LiveStream> findAllByStatus(LiveStatus status);

	List<LiveStream> findAllByVodBroadcastId(String vodBroadcastId);

}
