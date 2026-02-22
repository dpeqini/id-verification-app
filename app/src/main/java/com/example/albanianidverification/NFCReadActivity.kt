package com.example.albanianidverification

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.albanianidverification.databinding.ActivityNfcreadBinding
import com.example.albanianidverification.models.MRZData
import com.example.albanianidverification.nfc.PassportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NFCReadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNfcreadBinding
    private var nfcAdapter: NfcAdapter? = null
    private var mrzData: MRZData? = null
    private var isReading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNfcreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mrzData = intent.getParcelableExtra("MRZ_DATA")

        if (mrzData == null) {
            Toast.makeText(this, "MRZ data not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC not supported on this device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable NFC in settings", Toast.LENGTH_LONG).show()
        }

        setupButtons()
        displayMRZInfo()

        binding.instructionText.text = "Hold your phone near the ID card's chip"
        binding.statusText.text = "Waiting for NFC tag..."
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener { finish() }

        binding.rescanButton.setOnClickListener {
            resetNFCReading()
            Toast.makeText(this, "Ready to rescan. Hold card near phone.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetNFCReading() {
        binding.chipDataLayout.visibility = android.view.View.GONE
        binding.nfcStatusCard.visibility  = android.view.View.VISIBLE
        binding.instructionText.text      = "Hold your phone near the ID card's chip"
        binding.statusText.text           = "Waiting for NFC tag..."
        binding.progressBar.visibility    = android.view.View.GONE
        isReading = false
    }

    private fun displayMRZInfo() {
        binding.mrzInfoText.text = buildString {
            append("Document Number: ${mrzData?.documentNumber}\n")
            append("Date of Birth: ${mrzData?.dateOfBirth}\n")
            append("Expiry Date: ${mrzData?.expiryDate}\n")
            append("Nationality: ${mrzData?.nationality}\n")
        }
    }

    override fun onResume() {
        super.onResume()
        enableNFCForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNFCForegroundDispatch()
    }

    private fun enableNFCForegroundDispatch() {
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun disableNFCForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action
        ) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null && !isReading) readNFCTag(tag)
        }
    }

    private fun readNFCTag(tag: Tag) {
        isReading = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.statusText.text        = "Reading NFC chip..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val isoDep = IsoDep.get(tag)
                    val reader = PassportReader(isoDep)
                    mrzData?.let { mrz ->
                        reader.readPassport(mrz.documentNumber, mrz.dateOfBirth, mrz.expiryDate)
                    } ?: throw IllegalStateException("MRZ data is null")
                }

                if (result != null && result.isAuthenticated) {
                    binding.statusText.text = "Successfully read chip data!"
                    displayChipData(result)
                } else {
                    binding.statusText.text = "Failed to read chip"
                    Toast.makeText(this@NFCReadActivity, "Could not read NFC chip", Toast.LENGTH_LONG).show()
                    if (result?.personalData != null) displayChipData(result)
                }

            } catch (e: Exception) {
                binding.statusText.text = "Error: ${e.message}"
                Toast.makeText(
                    this@NFCReadActivity,
                    "Error reading chip: ${e.javaClass.simpleName}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                android.util.Log.e("NFCReadActivity", "Exception in readNFCTag", e)
            } finally {
                isReading = false
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun displayChipData(chipData: PassportReader.ChipData) {
        binding.nfcStatusCard.visibility = android.view.View.GONE
        binding.chipDataLayout.visibility = android.view.View.VISIBLE

        binding.chipInfoText.text = buildString {
            append("=== Chip Data ===\n\n")
            chipData.personalData?.let {
                append("Full Name: ${it.name}\n")
                append("Nationality: ${it.nationality}\n")
                append("Place of Birth: ${it.placeOfBirth}\n")
                append("Date of Birth: ${it.dateOfBirth}\n")
                append("Gender: ${it.gender}\n")
                append("Document Number: ${it.documentNumber}\n")
                append("Expiry Date: ${it.expiryDate}\n")
            } ?: append("No personal data extracted\n")

            append("\nData Groups Read: ${chipData.dataGroupsRead.joinToString(", ")}\n")
            append("Authentication: ${if (chipData.isAuthenticated) "✓ Success" else "✗ Failed"}\n")
            append(if (chipData.faceImage != null) "Face Image: ✓ ${chipData.faceImage.size} bytes\n"
            else "Face Image: ✗ Not extracted\n")
        }

        chipData.faceImage?.let { imageBytes ->
            try {
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.let { bitmap ->
                    binding.faceImageView.setImageBitmap(bitmap)
                    binding.faceImageView.visibility = android.view.View.VISIBLE
                }
            } catch (e: Exception) {
                android.util.Log.e("NFCReadActivity", "Error decoding face image", e)
            }
        }

        // Only offer verification when we have both identity data and a face photo
        if (chipData.personalData != null && chipData.faceImage != null) {
            binding.verifyButton.visibility = android.view.View.VISIBLE
            binding.verifyButton.setOnClickListener {
                launchFaceVerification(chipData)
            }
        }
    }

    /**
     * Launches [FaceVerificationActivity] with all chip data the backend needs.
     *
     * The activity will:
     *   1. Run ML Kit liveness
     *   2. Capture a live selfie
     *   3. POST everything to /api/v1/auth/id-card
     *   4. Navigate to voting on success, or show an error on failure
     */
    private fun launchFaceVerification(chipData: PassportReader.ChipData) {
        val pd = chipData.personalData ?: return

        val intent = Intent(this, FaceVerificationActivity::class.java).apply {
            // Raw chip face bytes — will be base64-encoded inside the activity
            putExtra(FaceVerificationActivity.EXTRA_CHIP_FACE_BYTES, chipData.faceImage)

            // Identity fields from DG1 — sent verbatim to the backend
            putExtra(FaceVerificationActivity.EXTRA_NATIONAL_ID,   pd.documentNumber)
            putExtra(FaceVerificationActivity.EXTRA_NAME,          pd.name)
            putExtra(FaceVerificationActivity.EXTRA_DATE_OF_BIRTH, pd.dateOfBirth)
            putExtra(FaceVerificationActivity.EXTRA_EXPIRY_DATE,   pd.expiryDate)
            putExtra(FaceVerificationActivity.EXTRA_PLACE_OF_BIRTH,   pd.placeOfBirth)
        }
        startActivity(intent)
    }
}