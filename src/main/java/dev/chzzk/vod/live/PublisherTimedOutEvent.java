package dev.chzzk.vod.live;

/** 송출자 연결이 끊긴 뒤 유예 시간 안에 다시 연결되지 않았을 때 발생한다. */
public record PublisherTimedOutEvent(String liveId) {
}
