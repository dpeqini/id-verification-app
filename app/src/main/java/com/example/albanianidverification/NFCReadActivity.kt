package com.example.albanianidverification

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
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

        displayMRZInfo()

        binding.instructionText.text = "Hold your phone near the ID card's chip"
        binding.statusText.text = "Waiting for NFC tag..."
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
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun disableNFCForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null && !isReading) {
                readNFCTag(tag)
            }
        }
    }

    private fun readNFCTag(tag: Tag) {
        isReading = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.statusText.text = "Reading NFC chip..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val isoDep = IsoDep.get(tag)
                    isoDep.timeout = 5000
                    val reader = PassportReader(isoDep)

                    mrzData?.let { mrz ->
                        reader.readPassport(
                            mrz.documentNumber,
                            mrz.dateOfBirth,
                            mrz.expiryDate
                        )
                    } ?: throw IllegalStateException("MRZ data is null")
                }

                if (result.isAuthenticated) {
                    binding.statusText.text = "Successfully read chip data!"
                    displayChipData(result)
                } else {
                    binding.statusText.text = "Failed to read chip"
                    val errorMsg =  "Unknown error"
                    Toast.makeText(
                        this@NFCReadActivity,
                        "Could not read NFC chip: $errorMsg",
                        Toast.LENGTH_LONG
                    ).show()

                    // Still display what we got (for debugging)
                    if (result.personalData != null ) {
                        displayChipData(result)
                    }
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
        binding.chipDataLayout.visibility = android.view.View.VISIBLE

        binding.chipInfoText.text = buildString {
            append("=== Chip Data ===\n\n")

            // Show error message if any
//            chipData.errorMessage?.let {
//                append("⚠️ Error: $it\n\n")
//            }

            chipData.personalData?.let {
                append("Full Name: ${it.name}\n")
                append("Nationality: ${it.nationality}\n")
                append("Date of Birth: ${it.dateOfBirth}\n")
                append("Gender: ${it.gender}\n")
                append("Document Number: ${it.documentNumber}\n")
                append("Expiry Date: ${it.expiryDate}\n")
            } ?: run {
                append("No personal data extracted\n")
            }

            append("\nData Groups Read: ${chipData.dataGroupsRead.joinToString(", ")}\n")
            append("Authentication: ${if (chipData.isAuthenticated) "✓ Success" else "✗ Failed"}\n")

            if (chipData.faceImage != null) {
                append("Face Image: ✓ ${chipData.faceImage.size} bytes\n")
            } else {
                append("Face Image: ✗ Not extracted\n")
            }
        }

        chipData.faceImage?.let { imageBytes ->
            try {
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) {
                    binding.faceImageView.setImageBitmap(bitmap)
                    binding.faceImageView.visibility = android.view.View.VISIBLE
                } else {
                    Toast.makeText(this, "Could not decode face image (bitmap is null)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Could not decode face image: ${e.message}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("NFCReadActivity", "Error decoding image", e)
            }
        }

        // Only show verify button if we have both personal data and face image
        if (chipData.personalData != null && chipData.faceImage != null) {
            binding.verifyButton.visibility = android.view.View.VISIBLE
            binding.verifyButton.setOnClickListener {
                // Navigate to face verification activity
                val intent = Intent(this, FaceVerificationActivity::class.java)
                intent.putExtra(FaceVerificationActivity.EXTRA_CHIP_FACE_IMAGE, chipData.faceImage)
                startActivity(intent)
            }
        }
    }
}