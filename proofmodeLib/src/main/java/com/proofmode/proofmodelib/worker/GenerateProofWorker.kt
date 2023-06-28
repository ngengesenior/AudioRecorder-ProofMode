package com.proofmode.proofmodelib.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.proofmode.proofmodelib.utils.ProofModeUtils
import org.witness.proofmode.ProofMode
import timber.log.Timber

class GenerateProofWorker(
    private val context: Context,
    workParams: WorkerParameters
) : Worker(context.applicationContext, workParams) {
    override fun doWork(): Result {
        val audioUriString = inputData.getString(ProofModeUtils.MEDIA_KEY)
        Timber.d("Media URI:$audioUriString")
        val audioUri = Uri.parse(audioUriString)
        val existingHash = ProofModeUtils.proofExistsForMedia(context, audioUri)
        if (existingHash.isNullOrEmpty()) {
            Timber.d("GenerateProofWorker:Proof does not exist,generating proof")
            val hash: String? = ProofMode.generateProof(context, Uri.parse(audioUriString))
            if (!hash.isNullOrEmpty()) {
                Timber.d("GenerateProofWorker:Proof generated successfully with hash $hash")
                return Result.success(workDataOf(ProofModeUtils.MEDIA_HASH to hash))
            }
            Timber.e("GenerateProofWorker:Failed to generate proof for some reason")
            return Result.failure()

        }
        Timber.d("GenerateProofWorker:Proof already exist with hash:$existingHash")

        return Result.success(workDataOf(ProofModeUtils.MEDIA_HASH to existingHash))
    }
}