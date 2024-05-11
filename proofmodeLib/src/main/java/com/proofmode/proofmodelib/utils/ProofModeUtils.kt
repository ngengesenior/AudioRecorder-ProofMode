package com.proofmode.proofmodelib.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.work.Data
import com.proofmode.proofmodelib.BuildConfig
import com.proofmode.proofmodelib.notaries.GoogleSafetyNetNotarizationProvider
import com.proofmode.proofmodelib.notaries.SafetyNetCheck
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.notaries.OpenTimestampsNotarizationProvider
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProofModeUtils {

    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    const val MEDIA_KEY = "audio"
    const val MEDIA_HASH = "mediaHash"
    val TAG = ProofModeUtils::class.simpleName
    private const val DOCUMENT_AUDIO =
            "content://com.android.providers.media.documents/document/audio%3A"
    private const val MEDIA_AUDIO = "content://media/external/audio/media/"
    val BUFFER_SIZE = 1024 * 8

    fun getStorageProvider(context: Context): StorageProvider {
        return DefaultStorageProvider(context.applicationContext)
    }
    fun makeProofZip(proofDirPath: File, context: Context): File {
        val files = proofDirPath.listFiles()
        Timber.d("makeProofZip: files.size = ${files.size}")
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
            val publicKey = getPublicKeyFingerprint()
            zos.write(publicKey.toByteArray())

        }

        return outputZipFile
    }
    fun createZipFileFromUris(context: Context,uris: List<Uri>, outputZipFile: File) {
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { outStream ->
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
            val pubKey = ProofMode.getPublicKeyString()
            var entry = ZipEntry("pubkey.asc")
            outStream.putNextEntry(entry)
            outStream.write(pubKey.toByteArray())
            Timber.d("Adding HowToVerifyProofData.txt")
            val howToFile = "HowToVerifyProofData.txt"
            entry = ZipEntry(howToFile)
            outStream.putNextEntry(entry)
            val `is` = context.applicationContext.resources.assets.open(howToFile)
            var length = `is`.read(buffer)
            while (length != -1) {
                outStream.write(buffer, 0, length)
                length = `is`.read(buffer)
            }
            `is`.close()
            Timber.d("Zip complete")
        }
    }


    /**
     *
     *Given fileZip and Uris, add files to zip file
     * The @param fileZip is the file to create and write to
     */
    fun ZipProof(context: Context,uris:ArrayList<Uri?>, fileZip:File?,
                            storageProvider:StorageProvider,
                 c2paCertPath:String) {
        var origin:BufferedInputStream
        val dest = FileOutputStream(fileZip)
        val out = ZipOutputStream(BufferedOutputStream(dest))
        val data = ByteArray(BUFFER_SIZE)
        val contentResolver = context.applicationContext.contentResolver

        for (uri in uris) {
            try {
                val fileName = getFileNameFromUri(context, uri)
                Timber.d("Adding to zip: $fileName")
                var isProofItem = storageProvider.getProofItem(uri)
                if (isProofItem == null) {
                    isProofItem = contentResolver.openInputStream(uri!!)
                    origin = BufferedInputStream(isProofItem, BUFFER_SIZE)
                    val entry = ZipEntry(fileName)
                    out.putNextEntry(entry)
                    var count:Int
                    while (origin.read(data, 0, BUFFER_SIZE).also { count = it } != -1) {
                        out.write(data, 0, count)
                    }
                    origin.close()
                    out.closeEntry()
                }

            } catch (e:Exception) {
                Timber.e("Failed adding URI to zip: ${uri?.lastPathSegment}")
            }
        }
        Timber.d("Adding public key")
        val pubKey = ProofMode.getPublicKeyString()
        var entry:ZipEntry? = ZipEntry("pubkey.asc")
        out.putNextEntry(entry)
        out.write(pubKey.toByteArray())
        val fileCert = File(context.filesDir,c2paCertPath)
        if (fileCert.exists()) {
            Timber.d("Adding certificate")
            entry = ZipEntry(c2paCertPath)
            out.putNextEntry(entry)
            out.write(fileCert.readBytes())
        }
        Timber.d("Adding HowToVerifyProofData.txt")
        val howToFile = "HowToVerifyProofData.txt"
        entry = ZipEntry(howToFile)
        out.putNextEntry(entry)
        val inputStream = context.applicationContext.resources.assets.open(howToFile)
        val buffer = ByteArray(1024)
        var length = inputStream.read(buffer)
        while (length != -1) {
            out.write(buffer, 0, length)
            length = inputStream.read(buffer)
        }
        inputStream.close()
    }

    fun getFileNameFromUri(context: Context, uri: Uri?): String? {
        val contentResolver = context.applicationContext.contentResolver
        val projection = arrayOfNulls<String>(2)
        val mimeType = contentResolver.getType(uri!!)
        val mimeTypeMap = MimeTypeMap.getSingleton()
        val fileExt = mimeTypeMap.getExtensionFromMimeType(mimeType)
        if (mimeType != null) {
            if (mimeType.startsWith("image")) {
                projection[0] = MediaStore.Images.Media.DATA
                projection[1] = MediaStore.Images.Media.DISPLAY_NAME
            } else if (mimeType.startsWith("video")) {
                projection[0] = MediaStore.Video.Media.DATA
                projection[1] = MediaStore.Video.Media.DISPLAY_NAME
            } else if (mimeType.startsWith("audio")) {
                projection[0] = MediaStore.Audio.Media.DATA
                projection[1] = MediaStore.Audio.Media.DISPLAY_NAME
            }
        } else {
            projection[0] = MediaStore.Audio.Media.DATA
            projection[1] = MediaStore.Audio.Media.DISPLAY_NAME
        }
        val cursor = contentResolver.query(getRealUri(uri)!!, projection, null, null, null)
        val result = false

        //default name with file extension
        var fileName = uri.lastPathSegment
        if (fileExt != null && fileName!!.indexOf(".") == -1) fileName += ".$fileExt"
        if (cursor != null) {
            if (cursor.count > 0) {
                cursor.moveToFirst()
                try {
                    var columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                    val path = cursor.getString(columnIndex)
                    if (path != null) {
                        val fileMedia = File(path)
                        if (fileMedia.exists()) fileName = fileMedia.name
                    }
                    if (TextUtils.isEmpty(fileName)) {
                        columnIndex = cursor.getColumnIndexOrThrow(projection[1])
                        fileName = cursor.getString(columnIndex)
                    }
                } catch (_: IllegalArgumentException) {
                }
            }
            cursor.close()
        }
        if (TextUtils.isEmpty(fileName)) fileName = uri.lastPathSegment
        return fileName
    }

    fun getRealUri(contentUri: Uri?): Uri? {
        val unusablePath = contentUri!!.path
        val startIndex = unusablePath!!.indexOf("external/")
        val endIndex = unusablePath.indexOf("/ACTUAL")
        return if (startIndex != -1 && endIndex != -1) {
            val embeddedPath = unusablePath.substring(startIndex, endIndex)
            val builder = contentUri.buildUpon()
            builder.path(embeddedPath)
            builder.authority("media")
            builder.build()
        } else contentUri
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

    fun shareProofClassic(
        audioUri: Uri, mediaPath: String,
        stringBuffer: StringBuffer,
        context: Context,
        pgpFingerprint: String
    ) :String?{
        val baseFolder = "proofmode"
        val ctx = context.applicationContext
        val hash = HashUtils.getSHA256FromFileContent(ctx.contentResolver.openInputStream(audioUri))
        val fileMedia = File(mediaPath)
        var fileMediaSig = File(mediaPath + ProofMode.OPENPGP_FILE_TAG)
        var fileMediaProof = File(mediaPath + ProofMode.PROOF_FILE_TAG)
        var fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)

        // Check different locations
        if (!fileMediaSig.exists()) {
            fileMediaSig = File(Environment.getExternalStorageDirectory(),"$baseFolder$mediaPath${ProofMode.OPENPGP_FILE_TAG}")
            fileMediaProof = File(Environment.getExternalStorageDirectory(),"$baseFolder$mediaPath${ProofMode.PROOF_FILE_TAG}")
            fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)
            if (!fileMediaSig.exists()) {
                fileMediaSig = File(ctx.getExternalFilesDir(null),mediaPath + ProofMode.OPENPGP_FILE_TAG)
                fileMediaProof = File(ctx.getExternalFilesDir(null),mediaPath + ProofMode.PROOF_FILE_TAG)
                fileMediaProofSig = File(fileMediaProof.absolutePath + ProofMode.OPENPGP_FILE_TAG)

            }

        }


        stringBuffer.apply {
            append(fileMedia.name)
            append(' ')
            append("was last modified on")
            append(' ')
            append(dateFormat.format(fileMedia.lastModified()))
            append(' ')
            append(" It has a SHA-256 hash of")
            append(' ')
            append(hash)
            append("\n\n")
            append("This proof is signed by the PGP key 0x")
            append(pgpFingerprint)
            append("\n")

        }
        return hash
    }


    fun getPublicKeyFingerprint():String {
        return ProofMode.getPublicKeyString()
    }


}