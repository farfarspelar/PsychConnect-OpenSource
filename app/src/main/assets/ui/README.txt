PsychConnect UI assets

All menu, mode, lobby, and result screens use the stationary
hands_background.webp image. The artwork includes the wordmark and shows two
figures touching a shared phone screen. Only the six fixed-position,
slow-fading sparkle overlays animate above it. Active gameplay uses its own
touch canvas.

Mode screens layer native text and an editable Android EditText over the blank
large-button, room-code-field, and small-button artwork. Button press feedback
uses a brief brightness/scale change and Android keyboard-tap haptics.

All WebP images in frames/ are decoded once off the UI thread and cached for
the Activity lifetime. Original PNG sources are retained in assets/ui/source/;
only optimized WebP runtime versions are packaged into the app.

The twelve zodiac profile cards are packaged as optimized, transparent WebP
images in avatars/ (zodiac_00.webp through zodiac_11.webp). Each keeps the
portrait and its blank lower name banner. The home profile button draws the
player nickname over that banner and adds a small online/offline marker. The
supplied Avatars_1.png and Avatars_2.png sprite sheets remain in the project
root as the editable source artwork.
