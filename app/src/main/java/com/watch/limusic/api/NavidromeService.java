package com.watch.limusic.api;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface NavidromeService {
    @GET("ping.view")
    Call<NavidromeResponse> ping();

    @GET("getAlbumList.view")
    Call<NavidromeResponse> getAlbums(
        @Query("type") String type,
        @Query("size") int size,
        @Query("offset") int offset
    );

    @GET("getAlbum.view")
    Call<NavidromeResponse> getAlbum(
        @Query("id") String id
    );

    @GET("getArtists.view")
    Call<NavidromeResponse> getArtists();

    @GET("getArtist.view")
    Call<NavidromeResponse> getArtist(
        @Query("id") String id
    );

    @GET("getRandomSongs.view")
    Call<NavidromeResponse> getRandomSongs(
        @Query("size") int size
    );

    @GET("search3.view")
    Call<NavidromeResponse> search(
        @Query("query") String query,
        @Query("artistCount") int artistCount,
        @Query("albumCount") int albumCount,
        @Query("songCount") int songCount
    );

    @GET("stream.view")
    String getStreamUrl(
        @Query("id") String id,
        @Query("maxBitRate") Integer maxBitRate,
        @Query("format") String format
    );

    @GET("getCoverArt.view")
    String getCoverArtUrl(
        @Query("id") String id,
        @Query("size") Integer size
    );
} 