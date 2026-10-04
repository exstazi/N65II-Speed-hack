# v1.1.6 diagnostic note

The stock-firmware diagnostic now sweeps drive-mode values 4, 6 and 7 on command 0x58, reading 0x70 after every probe and restoring the original normal mode between probes. Only one forwarded candidate is left active for a single physical reboot check. TCS (0x5F) and unit (0x55) are deliberately not changed by the sweep because they are real user settings rather than candidate drive-mode values.

# Sources / protocol references

The N65i II firmware builder and target-2 DFU path in this project come from the existing tested N65i II implementation supplied with this project.

For normal non-firmware TOOLS controls, protocol behavior was cross-checked against:

- `Laufbursche42/nv-lb-edition`
- commit `362e37d586532a23a14d40d390f732b0e7d87834`

Observed current normal-control mappings used here include:

- `0x51` lock / unlock
- `0x52` cruise
- `0x6A` start speed / zero-start level
- `0x58` drive mode (`3` Normal, `5` Turbo)
- `0x5F` traction control
- `0x55` display unit
- `0x6F` sub `5` Eco / low-power
- `0x57` auto light
- `0x54` tail light
- `0x6D` sub `3` daytime running light

The existing N65i II project mappings for turn-signal sound (`0x60`), proximity key (`0x61`) and energy recovery (`0x53`) are retained separately.

No upstream `patcher.js`, firmware recipes, firmware catalogue, firmware images, GT/ST/NT/UT model code, navigation code, maps, ride logger, or other multi-model components are included in this source package.

## v1.1.4 mode-4 release research

Cross-check source: `Laufbursche42/nv-lb-edition` (speed-release/latch architecture and NAVEE drive-mode command mapping). The v1.1.4 N65i II BLDC0005 offsets are **not copied from another model**; they were mapped against the bundled stock image with SHA-256 `03c8c60163eeea4e49367edd1b54235d9f9fd0776a1eee84f93a4248d8561b02`.

The exact Meter 2.0.5.8 mode-4 passthrough remains a hardware verification step. The app requires `0x70 DATA[26] == 4` before it will arm the release firmware flash.
