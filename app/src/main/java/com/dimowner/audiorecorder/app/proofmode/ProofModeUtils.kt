package com.dimowner.audiorecorder.app.proofmode

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object ProofModeUtils {

    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    const val AUTO_GENERATED_KEY = "auto_generated"
    const val MEDIA_KEY = "audio"
    const val MEDIA_HASH = "mediaHash"
    val TAG = ProofModeUtils::class.simpleName
    private const val DOCUMENT_AUDIO =
            "content://com.android.providers.media.documents/document/audio%3A"
    private const val MEDIA_AUDIO = "content://media/external/audio/media/"
    const val PREF_OPTION_LOCATION = "location-proof"
    const val PREF_OPTION_NETWORK = "network-proof"
    const val PREF_OPTION_PHONE = "phone-proof"
    const val PREFS_PASSPHRASE_DEFAULT = "default"
    const val PREFS_KEY_PASSPHRASE = "passphrasekey"



    /*fun createZipFileFromUris(context: Context,uris: List<Uri>, outputZipFile: File){
        createZipFileWithOutputStream(context,uris,FileOutputStream(outputZipFile))

    }*/

    /*fun createZipFileInDownloads(context: Context, uris: List<Uri>, fileName: String): Uri? {
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
    }*/

    /*private fun createZipFileWithOutputStream(context: Context, uris: List<Uri>, outputStream: OutputStream) {
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
    }*/






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

    /*fun generateProof(uriMedia: Uri, context: Context): String {
        ProofMode.setProofPoints(context.applicationContext,
            true,
            true, true, true)
        val proofHash = ProofMode.generateProof(context.applicationContext, uriMedia)
        return proofHash ?: ""
    }*/

    /*fun setProofPoints(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        ProofMode.setProofPoints(context.applicationContext,
            prefs.getPhoneStateProofPref(),
            prefs.getLocationProofPref(),
            prefs.getNetworkProofPref(),
            prefs.getNotaryProofPref())
    }*/

    fun getUriForFile(file: File, context: Context, packageName: String): Uri {
        return FileProvider.getUriForFile(
                context.applicationContext,
                "$packageName.app_file_provider",
                file
        )
    }


    fun SharedPreferences.getLocationProofPref(): Boolean {
        return this.getBoolean(PREF_OPTION_LOCATION, false)
    }

    fun SharedPreferences.getNetworkProofPref(): Boolean {
        return this.getBoolean(PREF_OPTION_NETWORK, false)
    }

    fun SharedPreferences.getPhoneStateProofPref(): Boolean {
        return this.getBoolean(PREF_OPTION_PHONE, false)
    }



    fun SharedPreferences.saveLocationProofPref(value: Boolean) {
        saveProofDataPointToPrefs(PREF_OPTION_LOCATION, value)
    }

    fun SharedPreferences.saveNetworkProofPref(value: Boolean) {
        saveProofDataPointToPrefs(PREF_OPTION_NETWORK, value)
    }

    fun SharedPreferences.savePhoneStateProofPref(value: Boolean) {
        saveProofDataPointToPrefs(PREF_OPTION_PHONE, value)
    }


    private fun SharedPreferences.saveProofDataPointToPrefs(key: String, value: Boolean) {
        edit().putBoolean(key,value).apply()

    }





    /*fun getProofDirectory(hash: String, context: Context): File {
        ProofMode.getProofDir(context, hash)
        return ProofMode.getProofDir(context, hash)
    }*/


}