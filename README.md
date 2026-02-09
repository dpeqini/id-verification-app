# Albanian ID Card Verification App

An Android application for verifying Albanian ID cards using NFC chip reading and MRZ (Machine Readable Zone) detection.

## Features

- **MRZ Detection**: Uses camera and ML Kit to scan and extract data from the Machine Readable Zone
- **NFC Chip Reading**: Reads encrypted data from the ID card's contactless chip
- **Data Extraction**: Retrieves personal information and face image from the chip
- **Security**: Implements BAC (Basic Access Control) for secure chip communication

## Requirements

- Android device with:
  - Android 7.0 (API 24) or higher
  - NFC capability
  - Camera
- Android Studio Arctic Fox or newer
- Kotlin 1.9.20 or newer

## Setup Instructions

### 1. Clone or Extract the Project

```bash
# If you have a git repository
git clone <repository-url>

# Or extract the ZIP file
unzip AlbanianIDVerification.zip
```

### 2. Open in Android Studio

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `AlbanianIDVerification` folder
4. Click "OK"

### 3. Sync Gradle

Android Studio should automatically sync Gradle dependencies. If not:
- Click "File" → "Sync Project with Gradle Files"
- Wait for dependencies to download

### 4. Build and Run

1. Connect an Android device with NFC capability (or use an emulator for testing MRZ only)
2. Enable USB debugging on your device
3. Click the "Run" button (green play icon) in Android Studio
4. Select your device from the list

## How to Use

### Step 1: Capture MRZ

1. Launch the app
2. Grant camera permissions when prompted
3. Position the Albanian ID card so the MRZ (bottom section with text) is within the green frame
4. Tap "Capture MRZ"
5. The app will automatically detect and extract the MRZ data

### Step 2: Read NFC Chip

1. After successful MRZ capture, you'll be taken to the NFC reading screen
2. Hold your phone's NFC reader area close to the ID card's chip (usually center/right side)
3. Keep the phone steady while the chip is being read
4. The app will display:
   - Personal information from the chip
   - Face photo from the chip
   - Verification status

## Technical Details

### Libraries Used

- **CameraX**: Modern camera API for capturing images
- **ML Kit Text Recognition**: OCR for MRZ detection
- **JMRTD**: Java Machine Readable Travel Documents library for reading e-passports/ID chips
- **Scuba**: Smart card utilities for Android
- **Bouncy Castle**: Cryptographic provider for BAC authentication

### Architecture

```
com.example.albanianidverification/
├── MainActivity.kt              # Camera and MRZ capture
├── NFCReadActivity.kt          # NFC chip reading
├── models/
│   └── MRZData.kt             # MRZ data model
├── nfc/
│   └── PassportReader.kt      # NFC chip reader implementation
└── utils/
    └── MRZParser.kt           # MRZ text parsing logic
```

### Data Flow

1. **MRZ Capture**: Camera → ML Kit OCR → MRZParser → MRZData
2. **NFC Reading**: MRZData (for BAC keys) → PassportReader → Chip Data
3. **Verification**: Compare chip photo with live capture (to be implemented)

## Supported Data Groups

Currently reads:
- **DG1**: MRZ data (name, nationality, dates, document number)
- **DG2**: Face image (JPEG/JPEG2000)

Can be extended to read:
- **DG3**: Fingerprints (requires Extended Access Control)
- **DG7**: Signature
- **DG11**: Additional personal details
- **DG12**: Additional document details

## Security Considerations

### Implemented

- ✅ BAC (Basic Access Control) authentication
- ✅ Secure channel establishment
- ✅ MRZ validation before chip reading
- ✅ Camera permissions handling

### Recommended Additions

- Passive Authentication (verify chip signatures)
- Active Authentication (verify chip is genuine, not cloned)
- Face liveness detection
- Secure storage for sensitive data
- GDPR/Data protection compliance measures
- Certificate chain validation for Albanian government certificates

## Known Limitations

1. **NFC Reading**: Success depends on:
   - Phone's NFC antenna position
   - ID card chip position
   - Environmental interference
   - Proper alignment

2. **MRZ Detection**: Works best when:
   - Good lighting conditions
   - MRZ is clearly visible
   - Card is flat and in focus

3. **Compatibility**: Albanian ID cards issued before 2009 may not have NFC chips

## Troubleshooting

### MRZ Not Detected

- Ensure good lighting
- Keep the card flat and within the frame
- Try cleaning the card surface
- Manually adjust camera focus if needed

### NFC Reading Fails

- Enable NFC in phone settings
- Try different positions of the phone on the card
- Ensure the card is not inside a wallet with RFID blocking
- Check if the card chip is damaged

### Build Errors

```bash
# Clean and rebuild
./gradlew clean
./gradlew build

# Or in Android Studio: Build → Clean Project → Rebuild Project
```

## Privacy & Legal

⚠️ **Important**: This application processes biometric and personal data. Ensure compliance with:

- Albanian Data Protection Law
- EU GDPR (if applicable)
- Obtain proper user consent
- Implement data minimization
- Provide clear privacy notices
- Secure data storage and transmission

## Future Enhancements

- [ ] Face liveness detection
- [ ] Live face vs chip photo comparison
- [ ] Fingerprint verification (if accessible)
- [ ] Certificate validation
- [ ] Backend integration for verification logging
- [ ] Multi-language support
- [ ] Offline mode with encrypted local storage
- [ ] Support for other document types (passports, etc.)

## Contributing

Contributions are welcome! Please ensure:
- Code follows Kotlin style guidelines
- Security best practices are maintained
- Privacy regulations are respected
- Proper error handling is implemented

## License

[Specify your license here]

## Disclaimer

This is a demonstration project. For production use:
- Conduct thorough security audits
- Implement proper error handling
- Add comprehensive logging
- Ensure regulatory compliance
- Test with various ID card versions
- Consider professional security review

## Support

For issues or questions:
- Check existing GitHub issues
- Create a new issue with detailed description
- Include device info and Android version

---

**Note**: This application requires physical Albanian ID cards with NFC chips for full testing.
