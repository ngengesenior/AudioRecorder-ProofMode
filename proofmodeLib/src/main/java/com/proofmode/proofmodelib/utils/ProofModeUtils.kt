package com.proofmode.proofmodelib.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.Data
import org.witness.proofmode.ProofMode
import org.witness.proofmode.service.MediaWatcher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProofModeUtils {

    const val MEDIA_KEY = "audio"
    const val MEDIA_HASH = "mediaHash"
    val TAG = ProofModeUtils::class.simpleName

    fun makeProofZip(proofDirPath: File, context: Context): File {
        val outputZipFile = File(context.filesDir, proofDirPath.name + ".zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZipFile))).use { zos ->
            proofDirPath.walkTopDown().forEach { file ->
                val zipFileName = file.absolutePath.removePrefix(proofDirPath.absolutePath).removePrefix("/")
                val entry = ZipEntry( "$zipFileName${(if (file.isDirectory) "/" else "" )}")
                zos.putNextEntry(entry)
                if (file.isFile) {
                    file.inputStream().copyTo(zos)
                }
            }
            val keyEntry = ZipEntry("pubkey.asc");
            zos.putNextEntry(keyEntry);
            val publicKey = ProofMode.getPublicKeyString(context)
            zos.write(publicKey.toByteArray())

        }

        return outputZipFile
    }



    fun createData(key:String, value:Any?) : Data {
        val builder = Data.Builder()
        value?.let {
            builder.putString(key,value.toString())
        }
        return builder.build()
    }

    fun shareZipFile(context: Context, zipFile: File,packageName:String) {
        val authority = "$packageName.provider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Proof Zip"))
    }

    fun generateProof(uriMedia: Uri, context: Context): String {
        ProofMode.setProofPoints(context.applicationContext,true,true,true,true)
        val proofHash = ProofMode.generateProof(context.applicationContext,uriMedia)
        return proofHash ?: ""
    }

    fun generateProof(file: File,context: Context,packageName: String):String {
        val uri = getUriForFile(file,context,packageName)
        return generateProof(uri,context)
    }

    /**
     * public static Uri getUriForFile(File file, Context context) {
    return FileProvider.getUriForFile(context.getApplicationContext(),
    context.getPackageName() + ".app_file_provider", file);
    }
     */

    fun getUriForFile(file: File, context: Context,packageName: String):Uri{
       return FileProvider.getUriForFile(context.applicationContext,"$packageName.app_file_provider",file)
    }


    fun retrieveOrCreateHash(uriMedia: Uri, context: Context):String {
        return MediaWatcher.getInstance(context).processUri(uriMedia,true, Date())
    }

}