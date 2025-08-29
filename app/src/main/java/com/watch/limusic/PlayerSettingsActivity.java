package com.watch.limusic;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class PlayerSettingsActivity extends AppCompatActivity {
	private static final String PREFS = "player_prefs";
	private static final String KEY_BG_BLUR_ENABLED = "bg_blur_enabled";
	private static final String KEY_BG_BLUR_INTENSITY = "bg_blur_intensity";
	private static final String KEY_LYRIC_SIZE_CURRENT = "lyric_size_current_sp";
	private static final String KEY_LYRIC_SIZE_OTHER = "lyric_size_other_sp";
	private static final String KEY_CUSTOM_LYRICS_ENABLED = "custom_lyrics_enabled";
	private static final String KEY_CUSTOM_LYRICS_URL = "custom_lyrics_url";
	private static final String KEY_LYRIC_SMOOTH = "lyric_smooth_enabled";
	// 新增：长句滚动显示
	private static final String KEY_LYRIC_LONG_MARQUEE = "lyric_long_marquee_enabled";

	private TextView txtBlurSummary;
	private TextView txtIntensityValue;
	private SeekBar seekIntensity;

	private TextView txtLyricCur;
	private TextView txtLyricOther;
	private SeekBar seekLyricCur;
	private SeekBar seekLyricOther;

	private TextView txtCustomLyricsSummary;
	private TextView txtLyricSmoothSummary;
	// 新增：长句滚动显示摘要
	private TextView txtLyricLongSummary;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		setTheme(R.style.Theme_LiMusic);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_player_settings);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		txtBlurSummary = findViewById(R.id.txt_bg_blur_summary);
		txtIntensityValue = findViewById(R.id.txt_bg_blur_intensity_value);
		seekIntensity = findViewById(R.id.seek_bg_blur_intensity);

		txtLyricCur = findViewById(R.id.txt_lyric_current_size_value);
		txtLyricOther = findViewById(R.id.txt_lyric_other_size_value);
		seekLyricCur = findViewById(R.id.seek_lyric_current_size);
		seekLyricOther = findViewById(R.id.seek_lyric_other_size);

		txtCustomLyricsSummary = findViewById(R.id.txt_custom_lyrics_summary);
		txtLyricSmoothSummary = findViewById(R.id.txt_lyric_smooth_summary);
		// 新增：长句滚动显示摘要
		txtLyricLongSummary = findViewById(R.id.txt_lyric_long_marquee_summary);

		updateBlurSummary();
		updateIntensitySummary();
		initLyricSizeControls();
		updateCustomLyricsSummary();
		updateLyricSmoothSummary();
		// 新增：初始化长句滚动显示摘要
		updateLyricLongSummary();

		findViewById(R.id.card_bg_blur).setOnClickListener(v -> {
			SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
			boolean enabled = sp.getBoolean(KEY_BG_BLUR_ENABLED, true);
			sp.edit().putBoolean(KEY_BG_BLUR_ENABLED, !enabled).apply();
			updateBlurSummary();
			notifyUi();
		});


		findViewById(R.id.card_custom_lyrics).setOnClickListener(v -> {
			SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
			boolean enabled = sp.getBoolean(KEY_CUSTOM_LYRICS_ENABLED, false);
			sp.edit().putBoolean(KEY_CUSTOM_LYRICS_ENABLED, !enabled).apply();
			updateCustomLyricsSummary();
			notifyUiLyricSource();
		});

		findViewById(R.id.card_custom_lyrics).setOnLongClickListener(v -> {
			SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
			String cur = sp.getString(KEY_CUSTOM_LYRICS_URL, "https://your.domain/lyrics?artist={artist}&title={title}");
			final EditText input = new EditText(this);
			input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
			input.setText(cur);
			input.setTextColor(getResources().getColor(android.R.color.black));
			input.setHintTextColor(0x80000000);
			input.setSingleLine(true);
			int pad = (int) (getResources().getDisplayMetrics().density * 12);
			input.setPadding(pad, pad, pad, pad);
			new AlertDialog.Builder(this)
					.setTitle("编辑自定义歌词源URL模板")
					.setMessage("使用 {artist} 与 {title} 占位符，例如：https://your.domain/lyrics?artist={artist}&title={title}")
					.setView(input)
					.setPositiveButton("保存", (d, which) -> {
						String v2 = input.getText() == null ? "" : input.getText().toString();
						sp.edit().putString(KEY_CUSTOM_LYRICS_URL, v2).apply();
						updateCustomLyricsSummary();
						notifyUiLyricSource();
					})
					.setNegativeButton("取消", null)
					.show();
			return true;
		});

		findViewById(R.id.card_lyric_smooth).setOnClickListener(v -> {
			SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
			boolean enabled = sp.getBoolean(KEY_LYRIC_SMOOTH, false);
			sp.edit().putBoolean(KEY_LYRIC_SMOOTH, !enabled).apply();
			updateLyricSmoothSummary();
			notifyUiLyric();
		});

		// 新增：长句滚动显示开关
		findViewById(R.id.card_lyric_long_marquee).setOnClickListener(v -> {
			SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
			boolean enabled = sp.getBoolean(KEY_LYRIC_LONG_MARQUEE, false);
			sp.edit().putBoolean(KEY_LYRIC_LONG_MARQUEE, !enabled).apply();
			updateLyricLongSummary();
			notifyUiLyric();
		});

		seekIntensity.setMax(100);
		seekIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				txtIntensityValue.setText(progress + "%");
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {
				SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
				sp.edit().putInt(KEY_BG_BLUR_INTENSITY, seekBar.getProgress()).apply();
				notifyUi();
			}
		});
	}

	private void initLyricSizeControls() {
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		int cur = sp.getInt(KEY_LYRIC_SIZE_CURRENT, 18);
		int other = sp.getInt(KEY_LYRIC_SIZE_OTHER, 12);
		seekLyricCur.setMax(8);
		int curProg = Math.max(0, Math.min(8, cur - 16));
		seekLyricCur.setProgress(curProg);
		txtLyricCur.setText(cur + "sp");
		seekLyricOther.setMax(6);
		int otherProg = Math.max(0, Math.min(6, other - 10));
		seekLyricOther.setProgress(otherProg);
		txtLyricOther.setText(other + "sp");

		seekLyricCur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				int val = 16 + progress;
				txtLyricCur.setText(val + "sp");
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {
				int val = 16 + seekBar.getProgress();
				SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
				sp.edit().putInt(KEY_LYRIC_SIZE_CURRENT, val).apply();
				notifyUiLyric();
			}
		});
		seekLyricOther.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				int val = 10 + progress;
				txtLyricOther.setText(val + "sp");
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {
				int val = 10 + seekBar.getProgress();
				SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
				sp.edit().putInt(KEY_LYRIC_SIZE_OTHER, val).apply();
				notifyUiLyric();
			}
		});
	}

	private void updateBlurSummary() {
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		boolean enabled = sp.getBoolean(KEY_BG_BLUR_ENABLED, true);
		txtBlurSummary.setText(enabled ? "已开启" : "关闭");
	}

	private void updateIntensitySummary() {
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		int v = sp.getInt(KEY_BG_BLUR_INTENSITY, 50);
		txtIntensityValue.setText(v + "%");
		seekIntensity.setProgress(v);
	}


	private void updateCustomLyricsSummary() {
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		boolean enabled = sp.getBoolean(KEY_CUSTOM_LYRICS_ENABLED, false);
		String url = sp.getString(KEY_CUSTOM_LYRICS_URL, "");
		String state = enabled ? "已开启" : "关闭";
		String desc = (url == null || url.trim().isEmpty()) ? "未配置" : url;
		txtCustomLyricsSummary.setText(state + " · " + desc);
	}

	private void updateLyricSmoothSummary() {
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		boolean enabled = sp.getBoolean(KEY_LYRIC_SMOOTH, false);
		txtLyricSmoothSummary.setText(enabled ? "已开启（会增加歌词显示时的耗电与发热）" : "关闭");
	}

	// 新增：更新长句滚动显示摘要
	private void updateLyricLongSummary() {
		if (txtLyricLongSummary == null) return;
		SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
		boolean enabled = sp.getBoolean(KEY_LYRIC_LONG_MARQUEE, false);
		txtLyricLongSummary.setText(enabled ? "已开启（仅当前行，超过两行时在两行视窗内滚动）" : "关闭");
	}

	private void notifyUi() {
		try { Intent i = new Intent("com.watch.limusic.UI_SETTINGS_CHANGED"); i.putExtra("what","player_bg"); sendBroadcast(i);} catch (Exception ignore) {}
		try { sendBroadcast(new Intent("com.watch.limusic.PLAYBACK_STATE_CHANGED")); } catch (Exception ignore) {}
	}

	private void notifyUiLyric() {
		try { Intent i = new Intent("com.watch.limusic.UI_SETTINGS_CHANGED"); i.putExtra("what","lyric_size"); sendBroadcast(i);} catch (Exception ignore) {}
	}

	private void notifyUiLyricSource() {
		try { Intent i = new Intent("com.watch.limusic.UI_SETTINGS_CHANGED"); i.putExtra("what","lyric_source"); sendBroadcast(i);} catch (Exception ignore) {}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
		return super.onOptionsItemSelected(item);
	}
} 