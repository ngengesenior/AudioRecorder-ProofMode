package com.proofmode.proofmodelib.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Data
import com.proofmode.proofmodelib.BuildConfig
import com.proofmode.proofmodelib.notaries.GoogleSafetyNetNotarizationProvider
import com.proofmode.proofmodelib.notaries.SafetyNetCheck
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

object ProofModeUtils {

    const val MEDIA_KEY = "audio"
    const val MEDIA_HASH = "mediaHash"
    val TAG = ProofModeUtils::class.simpleName
    private const val DOCUMENT_AUDIO =
            "content://com.android.providers.media.documents/document/audio%3A"
    private const val MEDIA_AUDIO = "content://media/external/audio/media/"
    const val defaultPassphrase = "12345678"

    fun makeProofZip(proofDirPath: File, context: Context): File {
        val outputZipFile = File(context.filesDir, "proofmode-audio-recorder${proofDirPath.name}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
            proofDirPath.walkTopDown().forEach { file ->
                val zipFileName =
                        file.absolutePath.removePrefix(proofDirPath.absolutePath).removePrefix("/")
                val entry = ZipEntry("$zipFileName${(if (file.isDirectory) "/" else "")}")
                zos.putNextEntry(entry)
                if (file.isFile) {
                    file.inputStream().copyTo(zos)
                }
            }
            val keyEntry = ZipEntry("pubkey.asc");
            zos.putNextEntry(keyEntry);
            val publicKey = ProofMode.getPublicKeyString(context, defaultPassphrase)
            zos.write(publicKey.toByteArray())

        }

        return outputZipFile
    }


    fun createData(key: String, value: Any?): Data {
        val builder = Data.Builder()
        value?.let {
            builder.putString(key, value.toString())
        }
        return builder.build()
    }

    fun shareZipFile(context: Context, zipFile: File, packageName: String) {
        val authority = "$packageName.app_file_provider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share Proof Zip").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.applicationContext.startActivity(chooserIntent)
    }

    fun generateProof(uriMedia: Uri, context: Context): String {
        ProofMode.setProofPoints(context.applicationContext, true, true, true, true)
        val proofHash = ProofMode.generateProof(context.applicationContext, uriMedia)
        return proofHash ?: ""
    }

    fun generateProof(file: File, context: Context, packageName: String): String {
        val uri = getUriForFile(file, context, packageName)
        return generateProof(uri, context)
    }

    /**
     * public static Uri getUriForFile(File file, Context context) {
    return FileProvider.getUriForFile(context.getApplicationContext(),
    context.getPackageName() + ".app_file_provider", file);
    }
     */

    fun getUriForFile(file: File, context: Context, packageName: String): Uri {
        return FileProvider.getUriForFile(
                context.applicationContext,
                "$packageName.app_file_provider",
                file
        )
    }

    fun addDefaultNotarizationProviders(context: Context) {
        SafetyNetCheck.setApiKey(BuildConfig.SAFETY_CHECK_KEY)
        try {
            ProofMode.addNotarizationProvider(context,GoogleSafetyNetNotarizationProvider(context))
        }catch (ex:Exception) {
            Timber.e(ex)
            Timber.e("GoogleSafetyNetNotarizationProvider failed")
        }
        try {
            ProofMode.addNotarizationProvider(context,OpenTimestampsNotarizationProvider())
        }catch (ex:Exception) {
            Timber.e(ex)
            Timber.e("OpenTimestampsNotarizationProvider failed")
        }


    }



    fun retrieveOrCreateHash(uriMedia: Uri, context: Context): String {
        return MediaWatcher.getInstance(context).processUri(uriMedia, true, Date())
    }

    fun SharedPreferences.getLocationProofPref(): Boolean {
        return this.getBoolean(ProofMode.PREF_OPTION_LOCATION, false)
    }

    fun SharedPreferences.getNetworkProofPref(): Boolean {
        return this.getBoolean(ProofMode.PREF_OPTION_NETWORK, false)
    }

    fun SharedPreferences.getPhoneStateProofPref(): Boolean {
        return this.getBoolean(ProofMode.PREF_OPTION_PHONE, false)
    }

    fun SharedPreferences.getNotaryProofPref(): Boolean {
        return this.getBoolean(ProofMode.PREF_OPTION_NOTARY, false)
    }


    fun SharedPreferences.saveLocationProofPref(value: Boolean) {
        saveProofDataPointToPrefs(ProofMode.PREF_OPTION_LOCATION, value)
    }

    fun SharedPreferences.saveNetworkProofPref(value: Boolean) {
        saveProofDataPointToPrefs(ProofMode.PREF_OPTION_NETWORK, value)
    }

    fun SharedPreferences.savePhoneStateProofPref(value: Boolean) {
        saveProofDataPointToPrefs(ProofMode.PREF_OPTION_PHONE, value)
    }


    fun SharedPreferences.saveNotaryProofPref(value: Boolean) {
        saveProofDataPointToPrefs(ProofMode.PREF_OPTION_NOTARY, value)
    }

    private fun SharedPreferences.saveProofDataPointToPrefs(key: String, value: Boolean) {
        edit(commit = true) {
            putBoolean(key, value)
        }
    }

    fun proofExistsForMediaFile(context: Context, file: File): String? {
        val uri = getUriForFile(file, context, context.applicationContext.packageName)
        return proofExistsForMedia(context, uri)
    }

    fun proofExistsForMedia(context: Context, mediaUri: Uri): String? {
        var mediaUri = mediaUri
        var sMediaUri = mediaUri.toString()
        if (sMediaUri.contains(DOCUMENT_AUDIO)) {
            sMediaUri = sMediaUri.replace(DOCUMENT_AUDIO, MEDIA_AUDIO)
            mediaUri = Uri.parse(sMediaUri)
        }
        val hash = HashUtils.getSHA256FromFileContent(
                context.applicationContext.contentResolver.openInputStream(mediaUri)
        )
        if (hash != null) {
            //hashCache[sMediaUri] = hash
            Timber.d("Proof check if exists for URI %s and hash %s", mediaUri, hash)
            val fileFolder = MediaWatcher.getHashStorageDir(context.applicationContext, hash)

            return if (fileFolder != null) {
                val fileMediaProof = File(fileFolder, hash + ProofMode.PROOF_FILE_TAG)
                //generate now?
                if (fileMediaProof.exists()) hash else null
            } else null
        }
        return null
    }

    fun getProofDirectory(hash: String, context: Context): File {
        return ProofMode.getProofDir(context, hash)
    }

    fun getPublicKeyFingerprint(context: Context):String {
        return PgpUtils.getInstance(context.applicationContext, defaultPassphrase).publicKeyFingerprint

    }

    fun publishPublicKey(context: Context) {
        try {
            PgpUtils.getInstance(context, defaultPassphrase).publishPublicKey()
        } catch (ex:Exception) {

        }
    }


}