package com.dimowner.audiorecorder.app.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.dimowner.audiorecorder.app.main.MainActivity
import com.dimowner.audiorecorder.databinding.ActivityPermissionsBinding
import com.google.android.material.snackbar.Snackbar

class PermissionsActivity : AppCompatActivity() {

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var binding: ActivityPermissionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        // Check if all the necessary permissions are already granted
        if (allPermissionsGranted()) {
            navigateToMainActivity()
            return
        }

        setContentView(binding.root)

        // Initialize the permission launcher
        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                // Check if all the mandatory permissions are granted
                if (permissions.all {
                        it.value
                    }) {
                    navigateToMainActivity()
                } else {
                    // Explain to the user why the permissions are necessary and prompt again
                    @SuppressLint("NewApi")
                    if (shouldShowPermissionRationale()) {
                        // Show a Snackbar explaining why the permissions are necessary and prompt again when the "OK" button is clicked
                        Snackbar.make(
                            binding.root,
                            "Please grant the permissions to use this app.",
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction(getString(android.R.string.ok)) {
                                requestPermissions()
                            }
                            .show()
                    } else {
                        // Permissions are denied with "never ask again", navigate to app settings or exit app
                        Snackbar
                            .make(
                            binding.root,
                            "Please grant the permissions in Settings to use this app.",
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction(getString(android.R.string.ok)) {
                                navigateToAppSettings()
                            }
                            .addCallback(object : Snackbar.Callback() {
                                override fun onDismissed(
                                    transientBottomBar: Snackbar?,
                                    event: Int
                                ) {
                                    if (event == DISMISS_EVENT_TIMEOUT || event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_CONSECUTIVE) {
                                        finish()
                                    }
                                }
                            })
                            .show()
                    }
                }
            }

        binding.buttonTurnOn.setOnClickListener {

            requestPermissions()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun shouldShowPermissionRationale(): Boolean {
        return getMandatoryPermissions().any {
            shouldShowRequestPermissionRationale(it)
        }
    }


    private fun requestPermissions() {
        // If the device is running Android 6 (Marshmallow) or later, request permission at runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissionLauncher.launch(getMandatoryPermissions())
        }
    }



    private fun getMandatoryPermissions(): Array<String> {
        // The mandatory permissions for recording audio
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )

        // If the device is running below Android 10, add external storage permissions as mandatory
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.plus(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }

        return permissions
    }

    private fun allPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return getMandatoryPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun navigateToAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri: Uri = "package:${packageName}".toUri()
        intent.data = uri
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        if(allPermissionsGranted()) {
            navigateToMainActivity()
        }
    }
}