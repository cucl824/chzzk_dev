package dev.chzzk.vod.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.chzzk.vod.storage.VideoStorage;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final VideoStorage storage;

	public WebConfig(VideoStorage storage) {
		this.storage = storage;
	}

	/** 업로드 영상 제공. Spring 정적 리소스 처리가 Range 요청을 지원하므로 탐색(seek)이 가능하다. */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(VideoStorage.PUBLIC_PATH + "**")
			.addResourceLocations(storage.getRoot().toUri().toString());
	}

}
