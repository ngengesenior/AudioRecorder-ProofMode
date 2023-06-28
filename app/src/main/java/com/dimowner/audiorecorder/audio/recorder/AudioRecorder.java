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

package com.dimowner.audiorecorder.audio.recorder;

import static com.dimowner.audiorecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.dimowner.audiorecorder.exception.InvalidOutputFile;
import com.dimowner.audiorecorder.exception.RecorderInitException;
import com.dimowner.audiorecorder.util.AndroidUtils;
import com.proofmode.proofmodelib.utils.ProofModeUtils;
import com.proofmode.proofmodelib.worker.GenerateProofWorker;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

public class AudioRecorder implements RecorderContract.Recorder {

    private final Context context;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final Handler handler = new Handler();
    private MediaRecorder recorder = null;
    private File recordFile = null;
    private long updateTime = 0;
    private long durationMills = 0;
    private RecorderContract.RecorderCallback recorderCallback;

	/*private static class RecorderSingletonHolder {
		private static final AudioRecorder singleton = new AudioRecorder();

		public static AudioRecorder getSingleton() {
			return RecorderSingletonHolder.singleton;
		}
	}*/

	/*public static AudioRecorder getInstance(Context context) {
		return RecorderSingletonHolder.getSingleton();
	}*/

    /*private AudioRecorder() { }*/
    public AudioRecorder(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }


    @Override
    public void setRecorderCallback(RecorderContract.RecorderCallback callback) {
        this.recorderCallback = callback;
    }

    @Override
    public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate) {
        recordFile = new File(outputFile);
        if (recordFile.exists() && recordFile.isFile()) {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(channelCount);
            recorder.setAudioSamplingRate(sampleRate);
            recorder.setAudioEncodingBitRate(bitrate);
            recorder.setMaxDuration(-1); //Duration unlimited or use RECORD_MAX_DURATION
            recorder.setOutputFile(recordFile.getAbsolutePath());
            try {
                recorder.prepare();
                recorder.start();
                updateTime = System.currentTimeMillis();
                isRecording.set(true);
                scheduleRecordingTimeUpdate();
                if (recorderCallback != null) {
                    recorderCallback.onStartRecord(recordFile);
                }
                isPaused.set(false);
            } catch (IOException | IllegalStateException e) {
                Timber.e(e, "prepare() failed");
                if (recorderCallback != null) {
                    recorderCallback.onError(new RecorderInitException());
                }
            }
        } else {
            if (recorderCallback != null) {
                recorderCallback.onError(new InvalidOutputFile());
            }
        }
    }

    @Override
    public void resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused.get()) {
            try {
                recorder.resume();
                updateTime = System.currentTimeMillis();
                scheduleRecordingTimeUpdate();
                if (recorderCallback != null) {
                    recorderCallback.onResumeRecord();
                }
                isPaused.set(false);
            } catch (IllegalStateException e) {
                Timber.e(e, "unpauseRecording() failed");
                if (recorderCallback != null) {
                    recorderCallback.onError(new RecorderInitException());
                }
            }
        }
    }

    @Override
    public void pauseRecording() {
        if (isRecording.get()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!isPaused.get()) {
                    try {
                        recorder.pause();
                        durationMills += System.currentTimeMillis() - updateTime;
                        pauseRecordingTimer();
                        if (recorderCallback != null) {
                            recorderCallback.onPauseRecord();
                        }
                        isPaused.set(true);
                    } catch (IllegalStateException e) {
                        Timber.e(e, "pauseRecording() failed");
                        if (recorderCallback != null) {
                            //TODO: Fix exception
                            recorderCallback.onError(new RecorderInitException());
                        }
                    }
                }
            } else {
                stopRecording();
            }
        }
    }

    @Override
    public void stopRecording() {
        if (isRecording.get()) {
            stopRecordingTimer();
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                Timber.e(e, "stopRecording() problems");
            }
            recorder.release();
            if (recorderCallback != null) {
                recorderCallback.onStopRecord(recordFile);
            }

            // Generate the proof here
            //AndroidUtils.generateProofWithWorkManager(context,recordFile);
            durationMills = 0;
            recordFile = null;
            isRecording.set(false);
            isPaused.set(false);
            recorder = null;
        } else {
            Timber.e("Recording has already stopped or hasn't started");
        }
    }

    private void generateProof(Uri uri, Context context) {
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(GenerateProofWorker.class);
        Constraints constraints = new Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build();
        Data data = ProofModeUtils.INSTANCE.createData(ProofModeUtils.MEDIA_HASH, uri);
        requestBuilder.setConstraints(constraints)
                .setInputData(data);
        WorkManager workManager = WorkManager.getInstance(context);
        workManager.beginUniqueWork(uri.toString(), ExistingWorkPolicy.REPLACE, requestBuilder.build()).enqueue();
        workManager.getWorkInfosForUniqueWorkLiveData(uri.toString())
                .observe((LifecycleOwner) context, workInfos -> {
                    WorkInfo workInfo = workInfos.get(0);
                    if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                        Log.d("Data", "generateProof: success");
                    }
                });
    }

    private void scheduleRecordingTimeUpdate() {
        handler.postDelayed(() -> {
            if (recorderCallback != null && recorder != null) {
                try {
                    long curTime = System.currentTimeMillis();
                    durationMills += curTime - updateTime;
                    updateTime = curTime;
                    recorderCallback.onRecordProgress(durationMills, recorder.getMaxAmplitude());
                } catch (IllegalStateException e) {
                    Timber.e(e);
                }
                scheduleRecordingTimeUpdate();
            }
        }, RECORDING_VISUALIZATION_INTERVAL);
    }

    private void stopRecordingTimer() {
        handler.removeCallbacksAndMessages(null);
        updateTime = 0;
    }

    private void pauseRecordingTimer() {
        handler.removeCallbacksAndMessages(null);
        updateTime = 0;
    }

    @Override
    public boolean isRecording() {
        return isRecording.get();
    }

    @Override
    public boolean isPaused() {
        return isPaused.get();
    }
}
