package org.proofmode.audio.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Data
import com.dimowner.audiorecorder.BuildConfig
import org.proofmode.audio.notaries.GoogleSafetyNetNotarizationProvider
import org.proofmode.audio.notaries.SafetyNetCheck
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProofModeUtils {

    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    const val AUTO_GENERATED_KEY = "auto_generated"
    const val MEDIA_KEY = "audio"
    const val MEDIA_HASH = "mediaHash"
    val TAG = ProofModeUtils::class.simpleName
    private const val DOCUMENT_AUDIO =
            "content://com.android.providers.media.documents/document/audio%3A"
    private const val MEDIA_AUDIO = "content://media/external/audio/media/"

    fun getStorageProvider(context: Context): StorageProvider {
        return DefaultStorageProvider(context.applicationContext)
    }
    fun createZipFileFromUris(context: Context,uris: List<Uri>, outputZipFile: File){
        createZipFileWithOutputStream(context,uris,FileOutputStream(outputZipFile))

    }

    fun createZipFileInDownloads(context: Context, uris: List<Uri>, fileName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val destinationUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileProvider.getUriForFile(context, "${context.applicationContext.packageName}.app_file_provider", file)
        }

        destinationUri?.let { uri ->
            resolver.openOutputStream(uri)?.use { outputStream ->
                createZipFileWithOutputStream(context, uris, outputStream)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            }
        }

        return destinationUri
    }

    private fun createZipFileWithOutputStream(context: Context, uris: List<Uri>, outputStream: OutputStream) {
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize)

        ZipOutputStream(BufferedOutputStream(outputStream)).use { outStream ->
            uris.forEach { uri ->
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedInputStream(inputStream, bufferSize).use { bufferedInputStream ->
                        val entryName = uri.lastPathSegment ?: uri.toString()
                        outStream.putNextEntry(ZipEntry(entryName))
                        var bytesRead: Int
                        while (bufferedInputStream.read(buffer, 0, bufferSize).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                        }
                        outStream.closeEntry()
                    }
                }
            }

            Timber.d("Adding public key")
            // Add public key
            val password = ""
            val pubKey = ProofMode.getPublicKeyString(context, password)
            var entry = ZipEntry("pubkey.asc")
            outStream.putNextEntry(entry)
            outStream.write(pubKey.toByteArray())

            Timber.d("Adding HowToVerifyProofData.txt")
            val howToFile = "HowToVerifyProofData.txt"
            entry = ZipEntry(howToFile)
            outStream.putNextEntry(entry)
            context.assets.open(howToFile).use { inputStream ->
                var length = inputStream.read(buffer)
                while (length != -1) {
                    outStream.write(buffer, 0, length)
                    length = inputStream.read(buffer)
                }
            }

            Timber.d("Zip complete")
        }
    }




    fun createData(key: String, value: Any?): Data {
        val builder = Data.Builder()
        value?.let {
            builder.putString(key, value.toString())
        }
        return builder.build()
    }
    fun createDataForProofWorker(
        mediaUriString:String,
        proofWasAutogenerated:Boolean? = false):Data{
        val builder = Data.Builder()
        builder.putString(MEDIA_KEY,mediaUriString)
        builder.putBoolean(AUTO_GENERATED_KEY,proofWasAutogenerated?:false)
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
        ProofMode.setProofPoints(context.applicationContext,
            true,
            true, true, true)
        val proofHash = ProofMode.generateProof(context.applicationContext, uriMedia)
        return proofHash ?: ""
    }

    fun setProofPoints(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        ProofMode.setProofPoints(context.applicationContext,
            prefs.getPhoneStateProofPref(),
            prefs.getLocationProofPref(),
            prefs.getNetworkProofPref(),
            prefs.getNotaryProofPref())
    }

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
            ProofMode.addNotarizationProvider(context, GoogleSafetyNetNotarizationProvider(context))
        }catch (ex:Exception) {
            Timber.e(ex)
        }
        try {
            ProofMode.addNotarizationProvider(context,OpenTimestampsNotarizationProvider())
        }catch (ex:Exception) {
            Timber.e(ex)
        }


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
            val storageProvider = getStorageProvider(context)
            if(storageProvider.proofExists(hash)){
                if (storageProvider.proofIdentifierExists(hash,hash+ProofMode.PROOF_FILE_TAG)) {
                    return hash
                }
            }
        }
        return null
    }

    /*fun getProofDirectory(hash: String, context: Context): File {
        ProofMode.getProofDir(context, hash)
        return ProofMode.getProofDir(context, hash)
    }*/


}