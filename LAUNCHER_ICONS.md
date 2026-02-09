# Launcher Icons

The app currently uses adaptive icons with the NFC icon as the foreground.

## To Add Custom Launcher Icons

You'll need to add PNG files for each density:

### Required Files

**For standard launcher icon:**
- `mipmap-mdpi/ic_launcher.png` (48x48)
- `mipmap-hdpi/ic_launcher.png` (72x72)
- `mipmap-xhdpi/ic_launcher.png` (96x96)
- `mipmap-xxhdpi/ic_launcher.png` (144x144)
- `mipmap-xxxhdpi/ic_launcher.png` (192x192)

**For round launcher icon:**
- `mipmap-mdpi/ic_launcher_round.png` (48x48)
- `mipmap-hdpi/ic_launcher_round.png` (72x72)
- `mipmap-xhdpi/ic_launcher_round.png` (96x96)
- `mipmap-xxhdpi/ic_launcher_round.png` (144x144)
- `mipmap-xxxhdpi/ic_launcher_round.png` (192x192)

## Easy Way: Use Android Studio

1. Right-click on `res` folder
2. Select **New → Image Asset**
3. Choose **Launcher Icons (Adaptive and Legacy)**
4. Upload your icon image
5. Android Studio will generate all required sizes automatically

## Online Tools

- https://icon.kitchen/
- https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html
- https://easyappicon.com/

The current adaptive icon setup will work but you may want to customize it with your own design.
