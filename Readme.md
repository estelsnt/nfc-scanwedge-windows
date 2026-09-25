# NfcWedge

Turns a PC/SC contactless reader (e.g. ACS ACR1552U) into a keyboard-wedge
scanner: tap a card, and its UID gets typed into whatever window/textbox
currently has focus, followed by Enter — just like a barcode scanner.

Uses only standard JDK classes (`javax.smartcardio` for the reader,
`java.awt.Robot` for keystroke injection) — no external dependencies.

## Requirements

- JDK 11+ (jpackage for the .exe step requires JDK 14+)
- The reader's PC/SC driver installed (you already have this — the beep
  you're hearing confirms the driver works)
- Windows Smart Card service running (it usually starts automatically
  once a PC/SC reader driver is installed)

## Run it directly

```
javac NfcWedge.java
java NfcWedge
```

Tap a card on the reader — the UID (hex string) should get typed into
whatever field has focus, then Enter is pressed.

If it can't find your reader, it will print the list of available reader
names. Update the filter string in `findReader(factory, "ACR1552")` in
`NfcWedge.java` to match if needed.

## Configuration

Near the top of `NfcWedge.java`:

- `COOLDOWN_MS` — minimum time between repeat reads of the same card
  (default 2000ms)
- `SEND_ENTER` — whether to press Enter after typing the UID (default true)
- UID format — currently lowercase hex with no separators (e.g. `04a1b2c3`).
  Adjust `bytesToHex` if you need uppercase, spaces, colons, etc. to match
  your member ID format.

## Package as a standalone Windows .exe

This bundles a JRE so the target machine doesn't need Java installed:

```
jar cfe NfcWedge.jar NfcWedge NfcWedge.class
jpackage --input . --name NfcWedge --main-jar NfcWedge.jar --main-class NfcWedge --type exe --win-console
```

This produces `NfcWedge-<version>.exe`. Run the installer on the target
Windows machine.

## Run at login (Windows)

Once installed, add it to Task Scheduler:

1. Task Scheduler → Create Task
2. Trigger: "At log on"
3. Action: start the installed NfcWedge.exe
4. Check "Run whether user is logged on or not" if you want it fully
   background/headless (note: Robot-based keystroke injection generally
   needs an active desktop session, so a real logged-in session is
   required — a pure background service without a UI session won't work
   for the typing part).

## Notes

- `Robot` types into whatever window currently has OS focus — so for this
  to work as intended, the operator needs to click into the target
  textbox before tapping the card (same as any real keyboard-wedge
  barcode scanner).
- Only alphanumeric characters are typed by `typeChar` — if you switch
  the UID format to include separators like `:` or `-`, extend that
  method to handle them.