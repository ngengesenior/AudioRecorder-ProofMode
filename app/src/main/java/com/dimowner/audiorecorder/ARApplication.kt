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
@file:Suppress("DEPRECATION")

package com.dimowner.audiorecorder

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.dimowner.audiorecorder.app.proofmode.ProofModeUtils
import com.dimowner.audiorecorder.app.proofmode.ProofModeUtils.PREFS_KEY_PASSPHRASE
import com.dimowner.audiorecorder.c2pa.C2paUtils
import com.dimowner.audiorecorder.pgp.PgpUtils
import com.dimowner.audiorecorder.util.AndroidUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPException
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.IOException
import java.security.Security
import java.util.concurrent.Executors
import kotlin.random.Random


//import com.google.firebase.FirebaseApp;
class ARApplication : Application() {

    private var audioOutputChangeReceiver: AudioOutputChangeReceiver? = null
    private var rebootReceiver: RebootReceiver? = null
    private var mPgpUtils:PgpUtils? = null
    private val mPrefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    fun initPgpKey(){
        if (mPgpUtils == null) {
            PgpUtils.init(this,mPrefs.getString(ProofModeUtils.PREFS_KEY_PASSPHRASE,ProofModeUtils.PREFS_PASSPHRASE_DEFAULT))
            mPgpUtils = PgpUtils.getInstance()
            initContentCredentials()

        }
    }

    fun initContentCredentials () {
        val email = "info@proofmode.org"
        var display : String? = null
        var key : String? = "0x" + mPgpUtils?.publicKeyFingerprint
        var uri : String? = null

        if (email?.isNotEmpty() == true)
        {
            display = "${email.replace("@"," at ")}"
        }

        uri =
            "https://keys.openpgp.org/search?q=" + mPgpUtils?.publicKeyFingerprint

        C2paUtils.init(this)
        C2paUtils.setC2PAIdentity(display, uri, email, key)
        if (key != null) {
            C2paUtils.initCredentials(this, email, key)
        }
    }

    fun checkAndGeneratePublicKey() {
        Executors.newSingleThreadExecutor().execute {

            //Background work here
            var pubKey: String? = null
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)

                if (PgpUtils.keyRingExists(this)) {
                    pubKey = mPgpUtils?.publicKeyFingerprint
                }
                else
                {
                    var newPassPhrase = getRandPassword(12)
                    prefs.edit().putString(PREFS_KEY_PASSPHRASE,newPassPhrase).commit()


                    var accountEmail = "info@proofmode.org" //prefs.getString(ProofMode.PREF_CREDENTIALS_PRIMARY, "")
                    if (accountEmail?.isNotEmpty() == true) {
                        PgpUtils.setKeyid(accountEmail)
                    }

                    pubKey = mPgpUtils?.publicKeyFingerprint

                }

            } catch (e: PGPException) {
                Timber.e(e, "error getting public key")
                showToastMessage("Error generating key")
            } catch (e: IOException) {
                Timber.e(e, "error getting public key")
                showToastMessage("Error generating key")
            }
        }
    }

    private fun showToastMessage(message: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            //UI Thread work here
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }




    fun getRandPassword(n: Int): String
    {
        val characterSet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        val random = Random(System.nanoTime())
        val password = StringBuilder()

        for (i in 0 until n)
        {
            val rIndex = random.nextInt(characterSet.length)
            password.append(characterSet[rIndex])
        }

        return password.toString()
    }






    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            //Timber initialization
            Timber.plant(DebugTree())
        }
        super.onCreate()
        PACKAGE_NAME = applicationContext.packageName
        applicationHandler = Handler(applicationContext.mainLooper)
        screenWidthDp = AndroidUtils.pxToDp(
            AndroidUtils.getScreenWidth(
                applicationContext
            )
        )
        checkAndGeneratePublicKey()
        CoroutineScope(Dispatchers.IO).launch {
            initPgpKey()
        }
        val prefs = injector.providePrefs(applicationContext)
        if (!prefs.isMigratedSettings) {
            prefs.migrateSettings()
        }
        registerAudioOutputChangeReceiver()
        registerRebootReceiver()

        // feature: pause when phone functions ringing or off-hook
        try {
            val telephonyMgr = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT<Build.VERSION_CODES.S) {
                telephonyPreAndroid12(telephonyMgr)
            } else {
                telephonyAndroid12Plus(telephonyMgr)
            }
        } catch (e: Exception) {
            Timber.e(e)
        }
        //		FirebaseApp.initializeApp(this);
    }

    override fun onTerminate() {
        super.onTerminate()
        //This method is never called on real Android devices
        injector.releaseMainPresenter()
        injector.closeTasks()
        unregisterReceiver(audioOutputChangeReceiver)
        unregisterReceiver(rebootReceiver)
    }

    fun pausePlayback() {
        //Pause playback when incoming call or on hold
        val player = injector.provideAudioPlayer()
        player.pause()
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    fun telephonyAndroid12Plus(telephonyMgr: TelephonyManager) {
        if (ContextCompat.checkSelfPermission(applicationContext,
            Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyMgr.registerTelephonyCallback(mainExecutor, callStateListener!!)
        }
    }

    private fun registerAudioOutputChangeReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        audioOutputChangeReceiver = AudioOutputChangeReceiver()
        registerReceiver(audioOutputChangeReceiver, intentFilter)
    }

    private fun registerRebootReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_REBOOT)
        intentFilter.addAction(Intent.ACTION_SHUTDOWN)
        rebootReceiver = RebootReceiver()
        registerReceiver(rebootReceiver, intentFilter)
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private abstract class CallStateListener : TelephonyCallback(),
        TelephonyCallback.CallStateListener {
        abstract override fun onCallStateChanged(state: Int)
    }

    private val callStateListener: CallStateListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) object : CallStateListener() {
            override fun onCallStateChanged(state: Int) {
                if (state == TelephonyManager.CALL_STATE_OFFHOOK ||
                        state == TelephonyManager.CALL_STATE_RINGING) {
                    pausePlayback()
                }
            }
        } else null

    private class AudioOutputChangeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val actionOfIntent = intent.action
            if (actionOfIntent != null && actionOfIntent == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val player = injector.provideAudioPlayer()
                player.pause()
            }
        }
    }

    private class RebootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_REBOOT || intent?.action == Intent.ACTION_SHUTDOWN) {
                val appRecorder = injector.provideAppRecorder(context)
                val audioPlayer = injector.provideAudioPlayer()
                appRecorder.stopRecording()
                audioPlayer.stop()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun telephonyPreAndroid12(telephonyMgr: TelephonyManager) {
        telephonyMgr.listen(mPhoneStateListenerPreAndroid12, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private val mPhoneStateListenerPreAndroid12: PhoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, incomingNumber: String) {
            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                pausePlayback()
            }
            super.onCallStateChanged(state, incomingNumber)
        }
    }

    companion object {
        private var PACKAGE_NAME: String? = null
        @JvmStatic val provider = BouncyCastleProvider()

        @JvmField
		@Volatile
        var applicationHandler: Handler? = null

        /** Screen width in dp  */
        private var screenWidthDp = 0f
        @JvmStatic
		var injector = Injector()
        @JvmStatic
		fun appPackage(): String? {
            return PACKAGE_NAME
        }

        /**
         * Calculate density pixels per second for record duration.
         * Used for visualisation waveform in view.
         * @param durationSec record duration in seconds
         */
		@JvmStatic
		fun getDpPerSecond(durationSec: Float): Float {
            return if (durationSec > AppConstants.LONG_RECORD_THRESHOLD_SECONDS) {
                AppConstants.WAVEFORM_WIDTH * screenWidthDp / durationSec
            } else {
                AppConstants.SHORT_RECORD_DP_PER_SECOND.toFloat()
            }
        }

        @JvmStatic
		val longWaveformSampleCount: Int
            get() = (AppConstants.WAVEFORM_WIDTH * screenWidthDp).toInt()


        init {
            Security.addProvider(provider)
        }
    }
}