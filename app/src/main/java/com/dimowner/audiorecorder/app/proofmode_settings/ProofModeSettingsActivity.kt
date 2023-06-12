package com.dimowner.audiorecorder.app.proofmode_settings

import android.os.Bundle
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.dimowner.audiorecorder.databinding.ActivityProofmodeSettingsBinding

class ProofModeSettingsActivity:AppCompatActivity() {
    private lateinit var binding:ActivityProofmodeSettingsBinding
    private lateinit var switchLocation: CheckBox
    private lateinit var switchNetwork: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProofmodeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()

    }

    private fun initViews() {
        switchLocation = binding.switchLocation
        switchNetwork = binding.switchNetwork
        switchDevice = binding.switchDevice
        switchNotarize = binding.switchNotarize
    }


}