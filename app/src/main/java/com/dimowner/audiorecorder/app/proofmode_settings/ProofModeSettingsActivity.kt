package com.dimowner.audiorecorder.app.proofmode_settings

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.preference.PreferenceManager
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dimowner.audiorecorder.databinding.ActivityProofmodeSettingsBinding
import com.proofmode.proofmodelib.utils.ProofModeUtils.getLocationProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.getNetworkProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.getNotaryProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.getPhoneStateProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.saveLocationProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.saveNetworkProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.saveNotaryProofPref
import com.proofmode.proofmodelib.utils.ProofModeUtils.savePhoneStateProofPref

class ProofModeSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProofmodeSettingsBinding
    private lateinit var switchLocation: CheckBox
    private lateinit var switchNetwork: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox

    private lateinit var networkPermLauncher: ActivityResultLauncher<String>
    private lateinit var phonePermLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermLauncher: ActivityResultLauncher<Array<String>>


    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProofmodeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
        initOnCheckedChangeListeners()
        initPermLaunchers()
        setProofPointsOnInit()

    }

    override fun onResume() {
        super.onResume()
        setProofPointsOnInit()
    }

    private fun initViews() {
        switchLocation = binding.switchLocation
        switchNetwork = binding.switchNetwork
        switchDevice = binding.switchDevice
        switchNotarize = binding.switchNotarize
    }

    private fun setProofPointsOnInit() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            switchDevice.isChecked = prefs.getPhoneStateProofPref()
        }

        if (anyLocationPermissionAccepted()) {
            switchLocation.isChecked = prefs.getLocationProofPref()
        }

        switchNetwork.isChecked = prefs.getNetworkProofPref()

        switchNotarize.isChecked = prefs.getNotaryProofPref()
    }

    private fun anyLocationPermissionAccepted() =
        locationPermissions.any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }


    private fun openAppSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun initPermLaunchers() {
        networkPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) {
                    prefs.saveNetworkProofPref(it)
                } else {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            Manifest.permission.ACCESS_NETWORK_STATE
                        )
                    ) {
                        createDialogAndLaunch(message = "Network state is required to save network proof data using ProofMode",
                            onOkClick = {
                                networkPermLauncher.launch(Manifest.permission.ACCESS_NETWORK_STATE)
                            }, onCancelClick = {
                                prefs.saveNetworkProofPref(false)
                                switchNetwork.isChecked = false
                            })
                    } else {
                        Toast.makeText(
                            this,
                            "You can turn on network permission in settings to enable network data to be saved with proof",
                            Toast.LENGTH_SHORT
                        ).show()
                        switchNetwork.isChecked = false
                        prefs.saveNetworkProofPref(false)
                    }
                }

            }

        phonePermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                prefs.savePhoneStateProofPref(it)
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.READ_PHONE_STATE
                    )
                ) {
                    createDialogAndLaunch(message = "Phone state is used to save certain phone info with proof data",
                        onOkClick = {
                            phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }, onCancelClick = {
                            prefs.savePhoneStateProofPref(false)
                            switchDevice.isChecked = false
                        })
                } else {
                    Toast.makeText(
                        this,
                        "You can turn on phone permission in settings to enable phone data to be saved with proof",
                        Toast.LENGTH_SHORT
                    ).show()
                    createDialogAndLaunch(message = "You previously turned down phone permissions.You can turn on phone permission in settings to enable phone data to be saved with proof",
                        okButtonText = "AppSettings",
                        onOkClick = {
                            openAppSettings()
                        }, onCancelClick = {
                            switchDevice.isChecked = false
                        })
                    switchDevice.isChecked = false

                }
            }

        }

        locationPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permResults ->
                if (permResults.values.any { it }) {
                    prefs.saveLocationProofPref(true)
                } else {
                    if (locationPermissions.any {
                            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                        }) {
                        createDialogAndLaunch(message = "Location permission is used required to save location data with generated proof",
                            onOkClick = {
                                locationPermLauncher.launch(locationPermissions)
                            }, onCancelClick = {
                                prefs.saveLocationProofPref(false)
                                switchLocation.isChecked = false
                            })

                    } else {

                        createDialogAndLaunch(message = "You previously turned down location permissions.You can turn on the permissions in settings to enable location data to be saved with proof",
                            okButtonText = "AppSettings",
                            onOkClick = {
                                openAppSettings()
                            }, onCancelClick = {
                                switchLocation.isChecked = false
                                //prefs.saveLocationProofPref(false)
                            })

                    }


                }
            }
    }

    private fun createDialogAndLaunch(
        message: String,
        onOkClick: () -> Unit,
        onCancelClick: (() -> Unit)? = null,
        okButtonText: String = getString(android.R.string.ok),
        cancelButtonText: String = getString(android.R.string.cancel)
    ) {
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(okButtonText) { _, _ ->
                onOkClick()
            }
            .setNegativeButton(cancelButtonText) { _, _ ->
                onCancelClick?.invoke()

            }
            .setCancelable(false)
        dialog.show()
    }

    private fun initOnCheckedChangeListeners() {
        switchNetwork.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_NETWORK_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    networkPermLauncher.launch(Manifest.permission.ACCESS_NETWORK_STATE)
                }

            } else {
                prefs.saveNetworkProofPref(false)
            }


        }

        switchDevice.setOnCheckedChangeListener { _, isChecked ->

            if (isChecked) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_PHONE_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    phonePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            } else {
                prefs.savePhoneStateProofPref(false)
            }

        }

        switchNotarize.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveNotaryProofPref(isChecked)
        }

        switchLocation.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                prefs.saveLocationProofPref(false)
            } else {
                if (locationPermissions.any {
                        ActivityCompat.checkSelfPermission(
                            this,
                            it
                        ) != PackageManager.PERMISSION_GRANTED

                    }) {
                    locationPermLauncher.launch(locationPermissions)

                } else {
                    prefs.saveLocationProofPref(false)
                }
            }

        }


    }


    companion object {
        private val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }


}