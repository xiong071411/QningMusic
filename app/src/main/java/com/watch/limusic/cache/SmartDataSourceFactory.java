package com.watch.limusic.cache;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.Map;

/**
 * SmartDataSourceFactory routes:
 * - file/content URIs -> DefaultDataSource (no cache write)
 * - http/https URIs   -> CacheDataSource (read/write cache)
 */
public class SmartDataSourceFactory implements DataSource.Factory {
	private final Context context;
	private final Cache cache;
	private final DefaultHttpDataSource.Factory httpFactory;
	private final DefaultDataSource.Factory defaultFactory;
	private final CacheDataSource.Factory cacheFactory;
	private final CacheDataSource.Factory cacheFactoryReadOnly;

	public SmartDataSourceFactory(Context context) {
		this.context = context.getApplicationContext();
		this.cache = CacheManager.getCache(this.context);
		this.httpFactory = new DefaultHttpDataSource.Factory().setUserAgent("LiMusic");
		this.defaultFactory = new DefaultDataSource.Factory(this.context, httpFactory);
		this.cacheFactory = new CacheDataSource.Factory()
				.setCache(cache)
				.setUpstreamDataSourceFactory(httpFactory)
				.setCacheWriteDataSinkFactory(new CacheDataSink.Factory().setCache(cache))
				.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
		// 只读缓存：不写入磁盘，低耗模式使用
		this.cacheFactoryReadOnly = new CacheDataSource.Factory()
				.setCache(cache)
				.setUpstreamDataSourceFactory(httpFactory)
				// 不设置写入sink，即仅读缓存
				.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
	}

	@Override
	public DataSource createDataSource() {
		// 始终使用读写缓存（避免频繁走网络导致更高功耗）
		final DataSource httpCached = cacheFactory.createDataSource();
		final DataSource localDs = defaultFactory.createDataSource();
		return new SwitchingDataSource(localDs, httpCached);
	}

	private static class SwitchingDataSource implements DataSource {
		private final DataSource localDataSource;
		private final DataSource httpCachedDataSource;
		private DataSource active;

		SwitchingDataSource(DataSource localDataSource, DataSource httpCachedDataSource) {
			this.localDataSource = localDataSource;
			this.httpCachedDataSource = httpCachedDataSource;
		}

		@Override
		public void addTransferListener(TransferListener transferListener) {
			localDataSource.addTransferListener(transferListener);
			httpCachedDataSource.addTransferListener(transferListener);
		}

		@Override
		public long open(DataSpec dataSpec) throws IOException {
			Uri uri = dataSpec.uri;
			String scheme = uri != null ? uri.getScheme() : null;
			boolean isLocal = scheme == null || "file".equalsIgnoreCase(scheme) || "content".equalsIgnoreCase(scheme);
			active = isLocal ? localDataSource : httpCachedDataSource;
			return active.open(dataSpec);
		}

		@Override
		public int read(byte[] buffer, int offset, int readLength) throws IOException {
			return active.read(buffer, offset, readLength);
		}

		@Override
		public Uri getUri() {
			return active != null ? active.getUri() : null;
		}

		@Override
		public Map<String, java.util.List<String>> getResponseHeaders() {
			return active != null ? active.getResponseHeaders() : java.util.Collections.emptyMap();
		}

		@Override
		public void close() throws IOException {
			if (active != null) {
				active.close();
			}
		}
	}
} 