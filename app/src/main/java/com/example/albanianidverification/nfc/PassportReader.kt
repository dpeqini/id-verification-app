package com.example.albanianidverification.nfc

import android.nfc.tech.IsoDep
import android.util.Log
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.Security

class PassportReader(private val isoDep: IsoDep) {
    
    companion object {
        private const val TAG = "PassportReader"
        
        init {
            // Register Bouncy Castle as security provider
            Security.insertProviderAt(
                org.bouncycastle.jce.provider.BouncyCastleProvider(),
                1
            )
        }
    }
    
    data class PersonalData(
        val name: String,
        val nationality: String,
        val dateOfBirth: String,
        val gender: String,
        val documentNumber: String,
        val expiryDate: String,
        val placeOfBirth: String
    )
    
    data class ChipData(
        val personalData: PersonalData?,
        val faceImage: ByteArray?,
        val dataGroupsRead: List<String>,
        val isAuthenticated: Boolean
    )
    
    /**
     * Read passport/ID card chip data
     * 
     * @param documentNumber Document number from MRZ
     * @param dateOfBirth Date of birth in YYMMDD format
     * @param expiryDate Expiry date in YYMMDD format
     * @return ChipData or null if reading failed
     */

    fun readPassport(
        documentNumber: String,
        dateOfBirth: String,
        expiryDate: String
    ): ChipData? {
        var cardService: CardService? = null
        var passportService: PassportService? = null

        try {
            Log.d(TAG, "Starting NFC read...")

            // 1. Connect and Configure Timeout
            isoDep.connect()
            isoDep.timeout = 10000 // 10 seconds timeout is critical for BAC

            // 2. Initialize Services
            cardService = CardService.getInstance(isoDep)
            cardService.open()

            passportService = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                true, // FIX: Enable SFI (Short File Identifier) often helps with speed
                false // Check MAC (can keep false for now to reduce overhead)
            )
            passportService.open()

            // --- FIX STARTS HERE ---
            // 3. Explicitly Select the Passport Applet
            // This ensures the chip is ready for BAC and sets up the correct state context.
            try {
                passportService.sendSelectApplet(false)
            } catch (e: Exception) {
                Log.w(TAG, "Warning: could not select applet, trying to proceed anyway: ${e.message}")
            }
            // --- FIX ENDS HERE ---

            // 4. Perform Authentication (BAC)
            val bacKey = BACKey(documentNumber, dateOfBirth, expiryDate)
            Log.d(TAG, "Performing BAC...")

            try {
                passportService.doBAC(bacKey)
                Log.d(TAG, "BAC successful")
            } catch (e: Exception) {
                Log.e(TAG, "BAC failed: ${e.message}", e)
                throw e // Re-throw to stop execution if login fails
            }

            // 5. Read Data Groups (Secure Session is now active)
            val dataGroupsRead = mutableListOf<String>()
            var personalData: PersonalData? = null
            var faceImage: ByteArray? = null

            // Read DG1
            try {
                Log.d(TAG, "Reading DG1...")
                val dg1Stream = passportService.getInputStream(PassportService.EF_DG1)
                val dg1File = DG1File(dg1Stream)
                val mrzInfo = dg1File.mrzInfo

                personalData = PersonalData(
                    name = "${mrzInfo.secondaryIdentifier} ${mrzInfo.primaryIdentifier}".replace("<", " ").trim(),
                    nationality = mrzInfo.nationality,
                    dateOfBirth = mrzInfo.dateOfBirth,
                    gender = mrzInfo.gender.toString(),
                    documentNumber = mrzInfo.documentNumber,
                    expiryDate = mrzInfo.dateOfExpiry,
                    placeOfBirth = ""
                )
                dataGroupsRead.add("DG1")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read DG1: ${e.message}")
            }

            // 2. Read DG11 (Place of Birth)
            try {
                Log.d(TAG, "Reading DG11...")
                val dg11File = DG11File(passportService.getInputStream(PassportService.EF_DG11))
                val place = dg11File.placeOfBirth?.elementAtOrNull(0)?.split(",")?.elementAtOrNull(0)
                personalData = personalData?.copy(placeOfBirth = place?.toString() ?: "")
                dataGroupsRead.add("DG11")
            } catch (e: Exception) { Log.w(TAG, "DG11 (Place of Birth) not accessible: ${e.message}") }

            // Read DG2
            try {
                Log.d(TAG, "Reading DG2...")
                val dg2Stream = passportService.getInputStream(PassportService.EF_DG2)
                val dg2File = DG2File(dg2Stream)

                if (dg2File.faceInfos.isNotEmpty()) {
                    val faceInfo = dg2File.faceInfos[0] as FaceInfo
                    if (faceInfo.faceImageInfos.isNotEmpty()) {
                        val faceImageInfo = faceInfo.faceImageInfos[0] as FaceImageInfo

                        // FIX: Use imageInputStream safely
                        val imageLength = faceImageInfo.imageLength
                        val dataInputStream = java.io.DataInputStream(faceImageInfo.imageInputStream)
                        val buffer = ByteArray(imageLength)
                        dataInputStream.readFully(buffer, 0, imageLength)
                        faceImage = buffer

                        dataGroupsRead.add("DG2")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read DG2: ${e.message}")
            }

            return ChipData(
                personalData = personalData,
                faceImage = faceImage,
                dataGroupsRead = dataGroupsRead,
                isAuthenticated = dataGroupsRead.isNotEmpty()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error reading passport: ${e.message}", e)
            return null
        } finally {
            try {
                passportService?.close()
                cardService?.close()
                if (isoDep.isConnected) isoDep.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing resources", e)
            }
        }
    }
}
