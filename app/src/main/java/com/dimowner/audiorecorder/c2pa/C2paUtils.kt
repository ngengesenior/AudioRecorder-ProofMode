package com.dimowner.audiorecorder.c2pa

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import info.guardianproject.simple_c2pa.ApplicationInfo
import info.guardianproject.simple_c2pa.Certificate
import info.guardianproject.simple_c2pa.CertificateOptions
import info.guardianproject.simple_c2pa.CertificateType
import info.guardianproject.simple_c2pa.ContentCredentials
import info.guardianproject.simple_c2pa.ExifData
import info.guardianproject.simple_c2pa.FileData
import info.guardianproject.simple_c2pa.createCertificate
import info.guardianproject.simple_c2pa.createPrivateKey
import info.guardianproject.simple_c2pa.createRootCertificate
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

class C2paUtils {

    companion object {

        private const val C2PA_CERT_PATH = "cr.cert"
        private const val C2PA_KEY_PATH = "cr.key"

        private const val C2PA_CERT_PATH_PARENT = "crp.cert"
        private const val C2PA_KEY_PATH_PARENT = "crp.key"

        private const val CERT_VALIDITY_DAYS = 365U //5 years

        private var _identityUri = "ProofMode@https://proofmode.org"
        private var _identityName = "ProofMode"
        private var _identityEmail = "info@proofmode.org"
        private var _identityKey = "0x00000000"

        private var userCert : Certificate? = null

        const val IDENTITY_URI_KEY = "id_uri"
        const val IDENTITY_NAME_KEY = "id_name"
        const val IDENTITY_EMAIL_KEY = "id_email"
        const val IDENTITY_PGP_KEY = "id_pgp"

        private const val APP_ICON_URI = "https://proofmode.org/images/avatar.jpg"

        /**
         * Set identity values for certificate and content credentials
         */
        fun setC2PAIdentity (identityName: String?, identityUri: String?, identityEmail: String?, identityKey: String?)
        {
            if (identityName?.isNotEmpty() == true)
                _identityName = identityName

            if (identityUri?.isNotEmpty() == true)
                _identityUri = identityUri

            if (identityEmail?.isNotEmpty() == true)
                _identityEmail = identityEmail

            if (identityKey?.isNotEmpty() == true)
                _identityKey = identityKey

        }

        fun init (context: Context)
        {
            //this needs to be set to
            Os.setenv("TMPDIR",context.cacheDir.absolutePath, true);
        }




        fun generateContentCredentials(context: Context,
                                       inputAudioFilePath:String,
                                       isDirectCapture: Boolean = true,
                                       allowMachineLearning: Boolean=false):File {
            val fileMedia = File(inputAudioFilePath)

            /**
             * Let us just add the content credentials to the file and not create a new one
             */
            if (fileMedia.exists()) {
                addContentCredentials(
                    context,
                    _identityEmail,
                    _identityKey,
                    _identityName,
                    _identityUri,
                    isDirectCapture,
                    allowMachineLearning,
                    fileMedia,
                )


            }
            return fileMedia

        }



        /**
         * Reset all variables and delete all local credential files
         */
        fun resetCredentials (mContext : Context) {

            val fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            val fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            val fileParentKey = File(mContext.filesDir, C2PA_KEY_PATH_PARENT)

            fileUserKey.delete()
            fileUserCert.delete()

            fileParentCert.delete()
            fileParentKey.delete()

            userCert = null
        }

        /**
         * initialize the private keys and certificates for signing C2PA data
         */
        fun initCredentials (mContext : Context, emailAddress: String, pgpFingerprint: String) {

            val fileUserCert = File(mContext.filesDir, C2PA_CERT_PATH)
            val fileUserKey = File(mContext.filesDir, C2PA_KEY_PATH)

            val fileParentCert = File(mContext.filesDir, C2PA_CERT_PATH_PARENT)
            val fileParentKey = File(mContext.filesDir, C2PA_KEY_PATH_PARENT)

            val userKey : FileData

            if (!fileUserKey.exists()) {
                userKey = createPrivateKey()
                fileUserKey.writeBytes(userKey.getBytes())
            }
            else
            {
                userKey = FileData(fileUserKey.absolutePath,fileUserKey.readBytes(),fileUserKey.name)
            }

            if (!fileUserCert.exists()) {

                val parentKey = createPrivateKey();
                fileParentKey.writeBytes(parentKey.getBytes())

                val organization = "ProofMode-Root";
                val rootCert:Certificate? = createRootCertificate(organization, CERT_VALIDITY_DAYS)

                rootCert?.let {

                    fileParentCert.writeBytes(rootCert.getCertificateBytes())

                    val userCertType =
                        CertificateType.ContentCredentials("ProofMode-User", CERT_VALIDITY_DAYS)
                    val userCertOptions = CertificateOptions(
                        userKey,
                        userCertType,
                        rootCert,
                        emailAddress,
                        pgpFingerprint
                    )

                    userCert = createCertificate(userCertOptions)

                    userCert?.let {

                        //this is where we would save the cert data once we have access to it
                        fileUserCert.writeBytes(it.getCertificateBytes())

                    }
                }

            }
            else
            {
                val fileDataParentKey = FileData(fileParentKey.absolutePath,fileParentKey.readBytes(),fileParentKey.name)
                val parentCert = Certificate(FileData(fileParentCert.absolutePath,fileParentCert.readBytes(),fileParentCert.name), fileDataParentKey, null)
                userCert = Certificate(FileData(fileUserCert.absolutePath,fileUserCert.readBytes(),fileUserKey.name), userKey, parentCert)
            }
        }

        /**
         * add new C2PA Content Credential assertions and then embed and sign them
         */
        fun addContentCredentials(mContext : Context,
                                  emailAddress: String,
                                  pgpFingerprint: String,
                                  emailDisplay: String?,
                                  webLink: String?,
                                  isDirectCapture: Boolean,
                                  allowMachineLearning: Boolean,
                                  fileIn: File) {

            if (userCert == null)
                initCredentials(mContext, emailAddress, pgpFingerprint)

            val appLabel = getAppName(mContext)
            val appVersion = getAppVersionName(mContext)
            val appIconUri = APP_ICON_URI

            val appInfo = ApplicationInfo(appLabel,appVersion,appIconUri)
            val mediaFile = FileData(fileIn.absolutePath,
                null,
                fileIn.name)
            val contentCreds = userCert?.let {
                ContentCredentials(it,
                mediaFile,
                appInfo)
            }

            if (isDirectCapture)
                contentCreds?.addCreatedAssertion()
            else
                contentCreds?.addPlacedAssertion()

            if (!allowMachineLearning)
                contentCreds?.addRestrictedAiTrainingAssertions()
            else
                contentCreds?.addPermissiveAiTrainingAssertions()

            emailDisplay?.let { contentCreds?.addEmailAssertion(emailAddress, it) }
            pgpFingerprint?.let { contentCreds?.addPgpAssertion(it, it) }
            webLink?.let { contentCreds?.addWebsiteAssertion(it) }

            val exifMake = Build.MANUFACTURER
            val exifModel = Build.MODEL
            val exifTimestamp = Date().toGMTString()

            val exifGpsVersion = "2.2.0.0"
            var exifLat: String? = null
            var exifLong: String? = null

            val gpsTracker = GPSTracker(mContext)
            gpsTracker.updateLocation()
            val location = gpsTracker.getLocation()
            location?.let {
                exifLat = GPSTracker.getLatitudeAsDMS(location, 3)
                exifLong = GPSTracker.getLongitudeAsDMS(location, 3)
            }

            val exifData = ExifData(exifGpsVersion, exifLat, exifLong,
                null, null, exifTimestamp, null,
                null, null, null, null,
                null, null, null, null,
                null, null, exifMake, exifModel, null,
                null, null)
            contentCreds?.addExifAssertion(exifData)
            Timber.d("Exif:$exifData")
            contentCreds?.embedManifest(fileIn.absolutePath)

        }


        /**
         * Helper functions for getting app name and version
         */
        fun getAppVersionName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return appVersionName
        }

        fun getAppName(context: Context): String {
            var appVersionName = ""
            try {
                appVersionName =
                    context.packageManager.getPackageInfo(context.packageName, 0).applicationInfo.name
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return appVersionName
        }


        @Throws(IOException::class)
        fun copy(src: File?, dst: File?) {
            val inStream = FileInputStream(src)
            val outStream = FileOutputStream(dst)
            val inChannel = inStream.channel
            val outChannel = outStream.channel
            inChannel.transferTo(0, inChannel.size(), outChannel)
            inStream.close()
            outStream.close()
        }

    }
}
