# PsychConnect button sound

`app/src/main/res/raw/button_click.wav` was created for PsychConnect by deterministic procedural synthesis in [`tools/generate_button_click.py`](tools/generate_button_click.py). The short tone is assembled mathematically from generated sine waves, an envelope, and seeded noise. No recording, sample, music, or third-party audio library was used.

Kristian Simontaival dedicates this sound recording to the public domain under [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/), to the extent rights in it can be held and waived. This is separate from the MIT license for source code and the CC BY 4.0 license for project artwork. The generator script remains covered by the root MIT license.

## Historical audio cleanup

The earlier WAV with unknown provenance has been removed from the rewritten publication history. The generated WAV above is the only `button_click.wav` retained in the published refs. The historical removal does not change the CC0 dedication for the current procedurally generated sound.

Reproduce the checked-in WAV from the repository root with:

```sh
python3 tools/generate_button_click.py
```
