package com.proofmode.proofmodelib.worker

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.proofmode.proofmodelib.utils.ProofModeUtils
import org.witness.proofmode.ProofMode

class GenerateProofWorker(private val context:Context,
workParams:WorkerParameters):Worker(context.applicationContext,workParams) {
    override fun doWork(): Result {
        val audioUriString = inputData.getString(ProofModeUtils.MEDIA_KEY)
        val hash = ProofMode.generateProof(context, Uri.parse(audioUriString))
        if (hash.isNotEmpty()) {

            return Result.success(workDataOf(ProofModeUtils.MEDIA_HASH to hash))
        }
        return Result.failure()
    }
}