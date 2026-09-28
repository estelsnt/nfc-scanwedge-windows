import javax.smartcardio.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NfcWedge - reads the UID of any PC/SC contactless reader and types it
 * as keyboard input. Runs in the system tray when the window is closed.
 *
 * Run with:  java --add-opens java.smartcardio/sun.security.smartcardio=ALL-UNNAMED NfcWedge
 * Start hidden in the tray:  ... NfcWedge --minimized
 */
public class NfcWedge {

    private static final byte[] GET_UID_APDU = {
        (byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00
    };

    private static final long COOLDOWN_MS = 2000;
    private static final long POLL_MS = 150;
    private static final boolean SEND_ENTER = true;

    // Readers whose names contain any of these (case-insensitive) are ignored.
    private static final String[] EXCLUDED_NAME_PARTS = {
        "WINDOWS HELLO", "VIRTUAL", "REMOTE"
    };

    private static JFrame frame;
    private static JLabel connectionStatusLabel;
    private static JLabel readerNameLabel;
    private static JLabel statusLabel;
    private static JLabel lastUidLabel;
    private static JLabel errorLabel;
    private static JButton exitButton;

    private static TrayIcon trayIcon;
    private static Image iconIdle;
    private static Image iconActive;

    private static volatile boolean running = true;
    private static volatile boolean typingEnabled = true;

    private static Robot robot;
    private static String lastUid = null;
    private static long lastReadTime = 0;

    public static void main(String[] args) {

        final boolean startMinimized =
            args.length > 0 && "--minimized".equalsIgnoreCase(args[0]);

        SwingUtilities.invokeLater(() -> createUI(startMinimized));

        try {
            robot = new Robot();
            robot.setAutoDelay(15);
        } catch (AWTException e) {
            showStartupError("Could not create keyboard controller: " + e.getMessage());
            return;
        }

        Thread scannerThread = new Thread(
            NfcWedge::scannerLoop,
            "NfcWedge-Scanner"
        );

        scannerThread.setDaemon(true);
        scannerThread.start();
    }

    // ------------------------------------------------------------------
    // UI + tray
    // ------------------------------------------------------------------

    private static void createUI(boolean startMinimized) {

        iconIdle = createIcon(new Color(150, 150, 150), 64);
        iconActive = createIcon(new Color(40, 167, 69), 64);

        frame = new JFrame("NfcWedge");
        frame.setIconImage(iconIdle);

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(420, 350);
        frame.setMinimumSize(new Dimension(380, 320));
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                hideToTrayOrExit();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

        JLabel titleLabel = new JLabel("NFC Reader to keyboard input");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 22));

        JLabel subtitleLabel = new JLabel(
            "https://github.com/estelsnt/nfc-scanwedge-windows.git"
        );

        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(
            new BoxLayout(titlePanel, BoxLayout.Y_AXIS)
        );

        titlePanel.add(titleLabel);
        titlePanel.add(subtitleLabel);

        mainPanel.add(titlePanel, BorderLayout.NORTH);

        JPanel statusPanel = new JPanel(
            new GridLayout(5, 1, 5, 5)
        );

        statusPanel.setBorder(
            BorderFactory.createTitledBorder("Status")
        );

        connectionStatusLabel = createStatusLabel("Reader disconnected");
        readerNameLabel = createStatusLabel("Reader: -");
        statusLabel = createStatusLabel("Status: Starting...");
        lastUidLabel = createStatusLabel("Last scan: -");
        errorLabel = createStatusLabel("Error: -");

        statusPanel.add(connectionStatusLabel);
        statusPanel.add(readerNameLabel);
        statusPanel.add(statusLabel);
        statusPanel.add(lastUidLabel);
        statusPanel.add(errorLabel);

        mainPanel.add(statusPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(
            new FlowLayout(FlowLayout.RIGHT)
        );

        boolean trayReady = setupTray();

        if (trayReady) {
            JButton hideButton = new JButton("Hide to tray");
            hideButton.addActionListener(e -> frame.setVisible(false));
            buttonPanel.add(hideButton);
        }

        exitButton = new JButton("Exit");
        exitButton.addActionListener(e -> shutdown());
        buttonPanel.add(exitButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        frame.setContentPane(mainPanel);

        // Only start hidden if the tray is actually available.
        frame.setVisible(!(startMinimized && trayReady));
    }

    private static boolean setupTray() {

        if (!SystemTray.isSupported()) {
            return false;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            PopupMenu menu = new PopupMenu();

            MenuItem showItem = new MenuItem("Show window");
            showItem.addActionListener(e -> showWindow());

            CheckboxMenuItem typingItem =
                new CheckboxMenuItem("Typing enabled", true);
            typingItem.addItemListener(e -> typingEnabled = typingItem.getState());

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> shutdown());

            menu.add(showItem);
            menu.add(typingItem);
            menu.addSeparator();
            menu.add(exitItem);

            java.awt.Dimension size = tray.getTrayIconSize();
            trayIcon = new TrayIcon(
                createIcon(new Color(150, 150, 150), Math.max(size.width, 16)),
                "NfcWedge - waiting for reader",
                menu
            );
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> showWindow()); // double-click

            tray.add(trayIcon);
            return true;

        } catch (AWTException e) {
            System.err.println("Could not add tray icon: " + e.getMessage());
            trayIcon = null;
            return false;
        }
    }

    private static void hideToTrayOrExit() {

        if (trayIcon != null) {

            frame.setVisible(false);

            trayIcon.displayMessage(
                "NfcWedge",
                "Still running in the background. Use the tray icon to reopen or exit.",
                TrayIcon.MessageType.INFO
            );

        } else {
            shutdown();
        }
    }

    private static void showWindow() {

        SwingUtilities.invokeLater(() -> {

            if (frame == null) {
                return;
            }

            frame.setVisible(true);
            frame.setExtendedState(JFrame.NORMAL);
            frame.toFront();
            frame.requestFocus();
        });
    }

    private static Image createIcon(Color color, int size) {

        BufferedImage img =
            new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = img.createGraphics();

        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON
        );

        int pad = Math.max(1, size / 8);

        g.setColor(color);
        g.fillOval(pad, pad, size - pad * 2, size - pad * 2);

        g.setColor(Color.WHITE);
        g.setStroke(new java.awt.BasicStroke(Math.max(1f, size / 12f)));

        int c = size / 2;
        int r1 = size / 6;
        int r2 = size / 3;

        g.drawArc(c - r1, c - r1, r1 * 2, r1 * 2, -45, 90);
        g.drawArc(c - r2, c - r2, r2 * 2, r2 * 2, -45, 90);

        g.dispose();

        return img;
    }

    private static JLabel createStatusLabel(String text) {

        JLabel label = new JLabel(text);

        label.setFont(
            new Font("SansSerif", Font.PLAIN, 14)
        );

        return label;
    }

    // ------------------------------------------------------------------
    // Scanner
    // ------------------------------------------------------------------

    private static void scannerLoop() {

        TerminalFactory factory = TerminalFactory.getDefault();

        Map<String, Boolean> cardPresent = new HashMap<>();
        String lastReaderSummary = null;
        long lastContextReset = 0;

        updateStatus("Looking for readers...", false);

        while (running) {

            try {

                List<CardTerminal> readers = listReaders(factory);

                if (readers.isEmpty()) {

                    if (lastReaderSummary != null || cardPresent.size() > 0) {
                        setDisconnected();
                    }

                    lastReaderSummary = null;
                    cardPresent.clear();

                    // Recover from a stale PC/SC context after replugging.
                    long now = System.currentTimeMillis();

                    if (now - lastContextReset > 5000) {
                        resetPcscContext();
                        lastContextReset = now;
                    }

                    sleep(2000);
                    continue;
                }

                String summary = summarize(readers);

                if (!summary.equals(lastReaderSummary)) {

                    setConnected(summary);

                    updateStatus("Waiting for card...", false);

                    lastReaderSummary = summary;
                }

                Set<String> names = new HashSet<>();

                for (CardTerminal terminal : readers) {

                    String name = terminal.getName();
                    names.add(name);

                    boolean nowPresent;

                    try {
                        nowPresent = terminal.isCardPresent();
                    } catch (CardException e) {

                        updateError(
                            "Reader error (" + name + "): " + e.getMessage()
                        );

                        cardPresent.put(name, false);
                        continue;
                    }

                    boolean wasPresent =
                        cardPresent.getOrDefault(name, false);

                    cardPresent.put(name, nowPresent);

                    // Only act on the moment a card arrives.
                    if (nowPresent && !wasPresent) {
                        handleCard(terminal);
                    }
                }

                cardPresent.keySet().retainAll(names);

                sleep(POLL_MS);

            } catch (Exception e) {

                updateError(
                    "Unexpected error: " + e.getMessage()
                );

                e.printStackTrace();

                sleep(1500);
            }
        }
    }

    private static void handleCard(CardTerminal terminal) {

        updateStatus("Card detected. Reading UID...", false);

        String uid = readUid(terminal);

        if (uid == null) {

            updateStatus("Could not read card UID", true);
            return;
        }

        long now = System.currentTimeMillis();

        boolean sameCardStillCoolingDown =
            uid.equals(lastUid)
            && (now - lastReadTime) < COOLDOWN_MS;

        if (sameCardStillCoolingDown) {

            updateStatus("Scan ignored (cooldown). Waiting for card...", false);
            return;
        }

        updateLastUid(uid);

        if (typingEnabled) {

            updateStatus("Typing UID...", false);

            typeString(robot, uid);

            if (SEND_ENTER) {
                pressEnter(robot);
            }

            updateStatus("Scan complete. Waiting for card...", false);

        } else {

            updateStatus("Typing paused. Waiting for card...", false);
        }

        lastUid = uid;
        lastReadTime = now;
    }

    /** Lists every usable reader, skipping virtual/excluded ones. */
    private static List<CardTerminal> listReaders(TerminalFactory factory) {

        List<CardTerminal> result = new ArrayList<>();

        try {

            for (CardTerminal terminal : factory.terminals().list()) {

                if (!isExcluded(terminal.getName())) {
                    result.add(terminal);
                }
            }

        } catch (CardException e) {
            // "No readers available" is reported as an exception on Windows.
            // Treat it as an empty list; the loop will retry.
        }

        return result;
    }

    private static boolean isExcluded(String readerName) {

        String upper = readerName.toUpperCase();

        for (String part : EXCLUDED_NAME_PARTS) {

            if (upper.contains(part)) {
                return true;
            }
        }

        return false;
    }

    private static String summarize(List<CardTerminal> readers) {

        String first = readers.get(0).getName();

        if (readers.size() == 1) {
            return first;
        }

        return first + " (+" + (readers.size() - 1) + " more)";
    }

    /**
     * Re-establishes the PC/SC context so readers plugged in after startup
     * get picked up. Needs:
     *   --add-opens java.smartcardio/sun.security.smartcardio=ALL-UNNAMED
     * Fails quietly if that flag is missing.
     */
    private static void resetPcscContext() {

        try {

            Class<?> pcscTerminals =
                Class.forName("sun.security.smartcardio.PCSCTerminals");

            java.lang.reflect.Field contextId =
                pcscTerminals.getDeclaredField("contextId");
            contextId.setAccessible(true);

            if (contextId.getLong(pcscTerminals) != 0L) {

                Class<?> pcsc =
                    Class.forName("sun.security.smartcardio.PCSC");

                java.lang.reflect.Method establish =
                    pcsc.getDeclaredMethod("SCardEstablishContext", Integer.TYPE);
                establish.setAccessible(true);

                java.lang.reflect.Field scope =
                    pcsc.getDeclaredField("SCARD_SCOPE_USER");
                scope.setAccessible(true);

                long newId =
                    (Long) establish.invoke(pcsc, scope.getInt(pcsc));

                contextId.setLong(pcscTerminals, newId);
            }

        } catch (Exception ignored) {
        }
    }

    private static String readUid(
        CardTerminal terminal
    ) {

        Card card = null;

        try {

            card = terminal.connect("*");

            CardChannel channel = card.getBasicChannel();

            ResponseAPDU response =
                channel.transmit(new CommandAPDU(GET_UID_APDU));

            int statusWord = response.getSW();

            if (statusWord != 0x9000) {

                updateError(
                    "Reader returned SW="
                    + String.format("%04X", statusWord)
                );

                return null;
            }

            byte[] data = response.getData();

            if (data.length == 0) {

                updateError("Reader returned an empty UID");

                return null;
            }

            return bytesToHex(data);

        } catch (CardException e) {

            updateError("Error reading card: " + e.getMessage());

            return null;

        } finally {

            if (card != null) {

                try {
                    card.disconnect(false);
                } catch (CardException ignored) {
                }
            }
        }
    }

    private static String bytesToHex(byte[] bytes) {

        StringBuilder sb = new StringBuilder();

        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Keyboard output
    // ------------------------------------------------------------------

    private static void typeString(
        Robot robot,
        String text
    ) {

        for (char c : text.toCharArray()) {
            typeChar(robot, c);
        }
    }

    private static void typeChar(
        Robot robot,
        char c
    ) {

        int keyCode;

        if (c >= '0' && c <= '9') {

            keyCode = KeyEvent.VK_0 + (c - '0');

        } else if (c >= 'a' && c <= 'z') {

            keyCode = KeyEvent.VK_A + (c - 'a');

        } else if (c >= 'A' && c <= 'Z') {

            keyCode = KeyEvent.VK_A + (c - 'A');

        } else {

            return;
        }

        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
    }

    private static void pressEnter(
        Robot robot
    ) {

        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
    }

    // ------------------------------------------------------------------
    // Status helpers
    // ------------------------------------------------------------------

    private static void setConnected(
        String readerName
    ) {

        SwingUtilities.invokeLater(() -> {

            if (connectionStatusLabel != null) {
                connectionStatusLabel.setText("Reader connected");
            }

            if (readerNameLabel != null) {
                readerNameLabel.setText("Reader: " + readerName);
            }

            if (trayIcon != null) {
                trayIcon.setImage(iconActive);
                trayIcon.setToolTip("NfcWedge - " + readerName);
            }
        });
    }

    private static void setDisconnected() {

        SwingUtilities.invokeLater(() -> {

            if (connectionStatusLabel != null) {
                connectionStatusLabel.setText("Reader disconnected");
            }

            if (readerNameLabel != null) {
                readerNameLabel.setText("Reader: -");
            }

            if (statusLabel != null) {
                statusLabel.setText("Status: Waiting for reader...");
            }

            if (trayIcon != null) {
                trayIcon.setImage(iconIdle);
                trayIcon.setToolTip("NfcWedge - waiting for reader");
            }
        });
    }

    private static void updateStatus(
        String message,
        boolean error
    ) {

        SwingUtilities.invokeLater(() -> {

            if (statusLabel != null) {
                statusLabel.setText("Status: " + message);
            }

            if (!error && errorLabel != null) {
                errorLabel.setText("Error: -");
            }
        });
    }

    private static void updateError(
        String message
    ) {

        System.err.println(message);

        SwingUtilities.invokeLater(() -> {

            if (errorLabel != null) {
                errorLabel.setText("Error: " + message);
            }
        });
    }

    private static void updateLastUid(
        String uid
    ) {

        SwingUtilities.invokeLater(() -> {

            if (lastUidLabel != null) {
                lastUidLabel.setText("Last scan: " + uid);
            }
        });
    }

    private static void showStartupError(
        String message
    ) {

        SwingUtilities.invokeLater(() -> {

            JOptionPane.showMessageDialog(
                null,
                message,
                "NfcWedge Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }

    private static void shutdown() {

        running = false;

        SwingUtilities.invokeLater(() -> {

            if (exitButton != null) {
                exitButton.setEnabled(false);
            }

            if (statusLabel != null) {
                statusLabel.setText("Status: Shutting down...");
            }
        });

        new Thread(() -> {

            sleep(300);

            SwingUtilities.invokeLater(() -> {

                if (trayIcon != null) {
                    SystemTray.getSystemTray().remove(trayIcon);
                }

                if (frame != null) {
                    frame.dispose();
                }

                System.exit(0);
            });

        }).start();
    }

    private static void sleep(long ms) {

        try {

            Thread.sleep(ms);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }
}