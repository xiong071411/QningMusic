package com.watch.limusic;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

@GlideModule
public final class LiMusicGlideModule extends AppGlideModule {
	@Override
	public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
		RequestOptions defaults = new RequestOptions()
				.format(DecodeFormat.PREFER_RGB_565)
				.disallowHardwareConfig()
				.dontAnimate();
		builder.setDefaultRequestOptions(defaults);
		// 如需进一步限制线程并发，可在后续版本升级 Glide 后再开启自定义执行器
	}

	@Override
	public boolean isManifestParsingEnabled() { return false; }
} 