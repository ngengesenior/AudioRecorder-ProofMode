/*
 * Copyright 2018 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.dimowner.audiorecorder.audio.player.PlayerContractNew;
import com.dimowner.audiorecorder.data.Prefs;
import com.dimowner.audiorecorder.util.AndroidUtils;
import com.dimowner.audiorecorder.util.C2paUtils;
import org.proofmode.audio.utils.ProofModeUtils;

//import com.google.firebase.FirebaseApp;

import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPUtil;
import org.witness.proofmode.ProofMode;
import org.witness.proofmode.ProofModeConstants;
import org.witness.proofmode.crypto.pgp.PgpUtils;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;

import timber.log.Timber;

public class ARApplication extends Application {


	final static String AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY";
	private AudioOutputChangeReceiver audioOutputChangeReceiver;

	private static String PACKAGE_NAME ;
	public static volatile Handler applicationHandler;

	/** Screen width in dp */
	private static float screenWidthDp = 0;

	public static Injector injector;

	private PgpUtils mPgpUtils;
	private SharedPreferences mPrefs;

	public static Injector getInjector() {
		if (injector == null) {
			injector = new Injector();
		}
		return injector;
	}

	public static String appPackage() {
		return PACKAGE_NAME;
	}

	private void initPgpKey() throws PGPException, IOException {
		if (mPgpUtils == null) {
			var passphrase = mPrefs.getString(ProofModeConstants.PREFS_KEY_PASSPHRASE,ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT);
			mPgpUtils = PgpUtils.getInstance(this,passphrase);
		}
	}


	/**
	 * Calculate density pixels per second for record duration.
	 * Used for visualisation waveform in view.
	 * @param durationSec record duration in seconds
	 */
	public static float getDpPerSecond(float durationSec) {
		if (durationSec > AppConstants.LONG_RECORD_THRESHOLD_SECONDS) {
			return AppConstants.WAVEFORM_WIDTH * screenWidthDp / durationSec;
		} else {
			return AppConstants.SHORT_RECORD_DP_PER_SECOND;
		}
	}

	private void setC2paIdentity() throws PGPException, IOException {

		try {
			Os.setenv("TMPDIR",getCacheDir().getAbsolutePath(), true);
		} catch (ErrnoException e) {
			Timber.d("The temp dir was not set");
		}

		String key = "0x" + mPgpUtils.getPublicKeyFingerprint();
		//String key = "0x" + ProofMode.getPublicKeyString();
		String email = "info@proofmode.org";
		String display = email.replace("@","at");
		String uri =
				"https://keys.openpgp.org/search?q=" + key;
		C2paUtils.Companion.setC2PAIdentity(display,uri,email,key);
	}

	public static int getLongWaveformSampleCount() {
		return (int)(AppConstants.WAVEFORM_WIDTH * screenWidthDp);
	}



	public void checkAndGeneratePublicKey() {
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				if (PgpUtils.keyRingExists(this)) {
						initPgpKey();
						setC2paIdentity();

				} else {
					String newPassphrase = generateRandomPassword(12);
					Timber.d("checkAndGeneratePublicKey password: " + newPassphrase);
					mPrefs.edit().putString(ProofModeConstants.PREFS_KEY_PASSPHRASE, newPassphrase).commit();

					initPgpKey();
					setC2paIdentity();

				}
			} catch (Exception ex) {
				Timber.e(ex,"Error getting public key");
			}

        });

	}

	private String generateRandomPassword(int length) {
		String charSet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		Random random = new Random(System.nanoTime());
		StringBuilder password = new StringBuilder();
		for (int i = 0; i < length; i++) {
			password.append(charSet.charAt(random.nextInt(charSet.length())));
		}
		return password.toString();
	}

	@Override
	public void onCreate() {
		if (BuildConfig.DEBUG) {
			//Timber initialization;;;;''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''''---------------------

			/**
			Timber.plant();**/
			var tree = new Timber.DebugTree() {
				@Override
				protected String createStackElementTag(StackTraceElement element) {
					return "AR-AR " + super.createStackElementTag(element) + ":" + element.getLineNumber();
				}
			};

			Timber.plant(tree);
		}
		super.onCreate();
		mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

		PACKAGE_NAME = getApplicationContext().getPackageName();
		applicationHandler = new Handler(getApplicationContext().getMainLooper());
		screenWidthDp = AndroidUtils.pxToDp(AndroidUtils.getScreenWidth(getApplicationContext()));
		injector = new Injector();
		Prefs prefs = injector.providePrefs(getApplicationContext());
		if (!prefs.isMigratedSettings()) {
			prefs.migrateSettings();
		}

		initProofMode();

		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(AUDIO_BECOMING_NOISY);
		audioOutputChangeReceiver = new AudioOutputChangeReceiver();
		registerReceiver(audioOutputChangeReceiver, intentFilter);

		try {
			TelephonyManager mTelephonyMgr = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			//Call Binder.clearCallingIdentity to avoid SecurityException being thrown
			Binder.clearCallingIdentity();
			mTelephonyMgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
		} catch (Exception e) {
			Timber.e(e);
		}
//		FirebaseApp.initializeApp(this);
	}

	private void initProofMode () {
		ProofModeUtils.INSTANCE.setProofPoints(this);
		checkAndGeneratePublicKey();

		// Add notarization providers
		ProofModeUtils.INSTANCE.addDefaultNotarizationProviders(this);
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		//This method is never called on real Android devices
		injector.releaseMainPresenter();
		injector.closeTasks();

		unregisterReceiver(audioOutputChangeReceiver);
	}

	private static class AudioOutputChangeReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			String actionOfIntent = intent.getAction();
			if (actionOfIntent != null && actionOfIntent.equals(AUDIO_BECOMING_NOISY)){
				PlayerContractNew.Player player = injector.provideAudioPlayer();
				player.pause();
			}
		}
	}

	private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
		@Override
		public void onCallStateChanged(int state, String incomingNumber) {
			if ((state == TelephonyManager.CALL_STATE_RINGING)
					|| (state == TelephonyManager.CALL_STATE_OFFHOOK)) {
				//Pause playback when incoming call or on hold
				PlayerContractNew.Player player = injector.provideAudioPlayer();
				player.pause();
			}
			super.onCallStateChanged(state, incomingNumber);
		}
	};
}
