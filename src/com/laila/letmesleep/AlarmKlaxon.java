package com.laila.letmesleep;
/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



import com.laila.letmesleep.R;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Vibrator;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Manages alarms and vibe. Runs as a service so that it can continue to play
 * if another activity overrides the AlarmAlert dialog.
 */
public class AlarmKlaxon extends Service {

	/** Play alarm up to 10 minutes before silencing */
	private static final int ALARM_TIMEOUT_SECONDS = 10 * 60;

	private static final long[] sVibratePattern = new long[] { 500, 500 };

	private boolean mPlaying = false;
	private Vibrator mVibrator;
	private MediaPlayer mMediaPlayer;
	private Alarm mCurrentAlarm;
	private long mStartTime;
	private TelephonyManager mTelephonyManager;
	private int mInitialCallState;

	// Internal messages
	private static final int KILLER = 1000;
	public static final int VIBRATOR_KILLER = 1001;

	final private Handler mHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case KILLER:
				sendKillBroadcast((Alarm) msg.obj);
				stopSelf();
				break;
			case VIBRATOR_KILLER:
				mVibrator.cancel();
				break;
			}
		}
	};

	final Messenger mMessenger = new Messenger (mHandler);

	private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
		@Override
		public void onCallStateChanged(int state, String ignored) {
			// The user might already be in a call when the alarm fires. When
			// we register onCallStateChanged, we get the initial in-call state
			// which kills the alarm. Check against the initial call state so
			// we don't kill the alarm during a call.
			if (state != TelephonyManager.CALL_STATE_IDLE
					&& state != mInitialCallState) {
				sendKillBroadcast(mCurrentAlarm);
				stopSelf();
			}
		}
	};

	@Override
	public void onCreate() {
		Log.i("AlarmKlaxon", "klaxon started");
		mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
		// Listen for incoming calls to kill the alarm.
		mTelephonyManager =
				(TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		mTelephonyManager.listen(
				mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
	}

	@Override
	public void onDestroy() {
		stop();
		// Stop listening for incoming calls.
		mTelephonyManager.listen(mPhoneStateListener, 0);
		AlarmAlertWakeLock.releaseCpuLock();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// No intent, tell the system not to restart us.
		if (intent == null) {
			stopSelf();
			return START_NOT_STICKY;
		}

		final Alarm alarm = intent.getParcelableExtra(
				Alarms.ALARM_INTENT_EXTRA);

		if (alarm == null) {
			Log.w("AlarmKlaxon","AlarmKlaxon failed to parse the alarm from the intent");
			stopSelf();
			return START_NOT_STICKY;
		}

		if (mCurrentAlarm != null) {
			sendKillBroadcast(mCurrentAlarm);
		}

		play(alarm);
		mCurrentAlarm = alarm;
		// Record the initial call state here so that the new alarm has the
		// newest state.
		mInitialCallState = mTelephonyManager.getCallState();

		return START_STICKY;
	}

	private void sendKillBroadcast(Alarm alarm) {
		long millis = System.currentTimeMillis() - mStartTime;
		int minutes = (int) Math.round(millis / 60000.0);
		Intent alarmKilled = new Intent(Alarms.ALARM_KILLED);
		alarmKilled.putExtra(Alarms.ALARM_INTENT_EXTRA, alarm);
		alarmKilled.putExtra(Alarms.ALARM_KILLED_TIMEOUT, minutes);
		sendBroadcast(alarmKilled);
	}

	// Volume suggested by media team for in-call alarms.
	private static final float IN_CALL_VOLUME = 0.125f;

	private void play(Alarm alarm) {
		// stop() checks to see if we are already playing.
		stop();

		if (!alarm.silent) {
			Uri alert = alarm.alert;
			// Fall back on the default alarm if the database does not have an
			// alarm stored.
			if (alert == null) {
				alert = RingtoneManager.getDefaultUri(
						RingtoneManager.TYPE_ALARM);
			}

			// RingtoneManager.
			mMediaPlayer = new MediaPlayer();
			mMediaPlayer.setOnErrorListener(new OnErrorListener() {
				public boolean onError(MediaPlayer mp, int what, int extra) {
					mp.stop();
					mp.release();
					mMediaPlayer = null;
					return true;
				}
			});

			try {
				// Check if we are in a call. If we are, use the in-call alarm
				// resource at a low volume to not disrupt the call.
				if (mTelephonyManager.getCallState()
						!= TelephonyManager.CALL_STATE_IDLE) {
					mMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
					setDataSourceFromResource(getResources(), mMediaPlayer,
							R.raw.in_call_alarm);
				} else {
					mMediaPlayer.setDataSource(this, alert);
				}
				startAlarm(mMediaPlayer);
			} catch (Exception ex) {
				// The alert may be on the sd card which could be busy right
				// now. Use the fallback ringtone.
				try {
					// Must reset the media player to clear the error state.
					mMediaPlayer.reset();
					setDataSourceFromResource(getResources(), mMediaPlayer,
							R.raw.fallbackring);
					startAlarm(mMediaPlayer);
				} catch (Exception ex2) {
					// At this point we just don't play anything.
				}
			}
		}

		/* Start the vibrator after everything is ok with the media player */
		if (alarm.vibrate) {
			mVibrator.vibrate(sVibratePattern, 0);
		} else {
			mVibrator.cancel();
		}

		enableKiller(alarm);
		mPlaying = true;
		mStartTime = System.currentTimeMillis();
	}

	// Do the common stuff when starting the alarm.
	private void startAlarm(MediaPlayer player)
			throws java.io.IOException, IllegalArgumentException,
			IllegalStateException {
		player.setAudioStreamType(AudioManager.STREAM_ALARM);
		player.setLooping(true);
		player.prepare();
		player.start();
	}

	private void setDataSourceFromResource(Resources resources,
			MediaPlayer player, int res) throws java.io.IOException {
		AssetFileDescriptor afd = resources.openRawResourceFd(res);
		if (afd != null) {
			player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
					afd.getLength());
			afd.close();
		}
	}

	/**
	 * Stops alarm audio and disables alarm if it not snoozed and not
	 * repeating
	 */
	public void stop() {
		if (mPlaying) {
			mPlaying = false;

			// Stop audio playing
			if (mMediaPlayer != null) {
				mMediaPlayer.stop();
				mMediaPlayer.release();
				mMediaPlayer = null;
			}

			// Stop vibrator
			mVibrator.cancel();
		}
		disableKiller();
	}

	/**
	 * Kills alarm audio after ALARM_TIMEOUT_SECONDS, so the alarm
	 * won't run all day.
	 *
	 * This just cancels the audio, but leaves the notification
	 * popped, so the user will know that the alarm tripped.
	 */
	private void enableKiller(Alarm alarm) {
		mHandler.sendMessageDelayed(mHandler.obtainMessage(KILLER, alarm),
				1000 * ALARM_TIMEOUT_SECONDS);
	}

	private void disableKiller() {
		mHandler.removeMessages(KILLER);
	}
}