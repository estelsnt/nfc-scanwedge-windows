import javax.smartcardio.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.List;

public class NfcWedge {

    private static final byte[] GET_UID_APDU = {
        (byte) 0xFF, (byte) 0xCA, 0x00, 0x00, 0x00
    };

    private static final long COOLDOWN_MS = 2000;
    private static final long WAIT_TIMEOUT_MS = 1000;
    private static final boolean SEND_ENTER = true;
    private static final String READER_NAME_FILTER = "ACR1552";

    private static JFrame frame;
    private static JLabel connectionStatusLabel;
    private static JLabel readerNameLabel;
    private static JLabel statusLabel;
    private static JLabel lastUidLabel;
    private static JLabel errorLabel;
    private static JButton exitButton;

    private static volatile boolean running = true;

    private static Robot robot;
    private static String lastUid = null;
    private static long lastReadTime = 0;

    public static void main(String[] args) {

        SwingUtilities.invokeLater(NfcWedge::createUI);

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

    private static void createUI() {

        frame = new JFrame("NfcWedge");

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(420, 330);
        frame.setMinimumSize(new Dimension(380, 300));
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdown();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

        JLabel titleLabel = new JLabel("NfcWedge");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 22));

        JLabel subtitleLabel = new JLabel(
            "NFC reader → keyboard input"
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

        connectionStatusLabel =
            createStatusLabel("Reader disconnected");

        readerNameLabel =
            createStatusLabel("Reader: -");

        statusLabel =
            createStatusLabel("Status: Starting...");

        lastUidLabel =
            createStatusLabel("Last scan: -");

        errorLabel =
            createStatusLabel("Error: -");

        statusPanel.add(connectionStatusLabel);
        statusPanel.add(readerNameLabel);
        statusPanel.add(statusLabel);
        statusPanel.add(lastUidLabel);
        statusPanel.add(errorLabel);

        mainPanel.add(statusPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(
            new FlowLayout(FlowLayout.RIGHT)
        );

        exitButton = new JButton("Exit");
        exitButton.addActionListener(e -> shutdown());

        buttonPanel.add(exitButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        frame.setContentPane(mainPanel);
        frame.setVisible(true);
    }

    private static JLabel createStatusLabel(String text) {

        JLabel label = new JLabel(text);

        label.setFont(
            new Font("SansSerif", Font.PLAIN, 14)
        );

        return label;
    }

    private static void scannerLoop() {

        TerminalFactory factory =
            TerminalFactory.getDefault();

        updateStatus(
            "Looking for " + READER_NAME_FILTER + "...",
            false
        );

        while (running) {

            try {

                CardTerminal terminal =
                    findReader(factory, READER_NAME_FILTER);

                if (terminal == null) {

                    setDisconnected();

                    sleep(2000);

                    continue;
                }

                setConnected(terminal.getName());

                updateStatus(
                    "Waiting for card...",
                    false
                );

                boolean present =
                    terminal.waitForCardPresent(
                        WAIT_TIMEOUT_MS
                    );

                if (!present) {
                    continue;
                }

                updateStatus(
                    "Card detected. Reading UID...",
                    false
                );

                String uid = readUid(terminal);

                if (uid == null) {

                    updateStatus(
                        "Could not read card UID",
                        true
                    );

                    try {
                        terminal.waitForCardAbsent(0);
                    } catch (CardException ignored) {
                    }

                    continue;
                }

                long now = System.currentTimeMillis();

                boolean sameCardStillCoolingDown =
                    uid.equals(lastUid)
                    && (now - lastReadTime) < COOLDOWN_MS;

                if (!sameCardStillCoolingDown) {

                    updateLastUid(uid);

                    updateStatus(
                        "Typing UID...",
                        false
                    );

                    typeString(robot, uid);

                    if (SEND_ENTER) {
                        pressEnter(robot);
                    }

                    lastUid = uid;
                    lastReadTime = now;

                    updateStatus(
                        "Scan complete. Waiting for card...",
                        false
                    );
                }

                try {

                    terminal.waitForCardAbsent(0);

                } catch (CardException e) {

                    updateError(
                        "Reader disconnected: "
                        + e.getMessage()
                    );
                }

            } catch (CardException e) {

                setDisconnected();

                updateError(
                    "Reader error: " + e.getMessage()
                );

                sleep(1500);

            } catch (Exception e) {

                updateError(
                    "Unexpected error: " + e.getMessage()
                );

                e.printStackTrace();

                sleep(1500);
            }
        }
    }

    private static CardTerminal findReader(
        TerminalFactory factory,
        String nameFilter
    ) {

        try {

            List<CardTerminal> terminals =
                factory.terminals().list();

            for (CardTerminal terminal : terminals) {

                if (terminal.getName()
                    .toUpperCase()
                    .contains(nameFilter.toUpperCase())) {

                    return terminal;
                }
            }

        } catch (CardException e) {

            updateError(
                "Could not list readers: "
                + e.getMessage()
            );
        }

        return null;
    }

    private static String readUid(
        CardTerminal terminal
    ) {

        Card card = null;

        try {

            card = terminal.connect("*");

            CardChannel channel =
                card.getBasicChannel();

            ResponseAPDU response =
                channel.transmit(
                    new CommandAPDU(GET_UID_APDU)
                );

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

                updateError(
                    "Reader returned an empty UID"
                );

                return null;
            }

            return bytesToHex(data);

        } catch (CardException e) {

            updateError(
                "Error reading card: "
                + e.getMessage()
            );

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

            sb.append(
                String.format("%02x", b)
            );
        }

        return sb.toString();
    }

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

            keyCode =
                KeyEvent.VK_0 + (c - '0');

        } else if (c >= 'a' && c <= 'z') {

            keyCode =
                KeyEvent.VK_A + (c - 'a');

        } else if (c >= 'A' && c <= 'Z') {

            keyCode =
                KeyEvent.VK_A + (c - 'A');

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

    private static void setConnected(
        String readerName
    ) {

        SwingUtilities.invokeLater(() -> {

            if (connectionStatusLabel != null) {

                connectionStatusLabel.setText(
                    "Reader connected"
                );
            }

            if (readerNameLabel != null) {

                readerNameLabel.setText(
                    "Reader: " + readerName
                );
            }
        });
    }

    private static void setDisconnected() {

        SwingUtilities.invokeLater(() -> {

            if (connectionStatusLabel != null) {

                connectionStatusLabel.setText(
                    "Reader disconnected"
                );
            }

            if (readerNameLabel != null) {

                readerNameLabel.setText(
                    "Reader: -"
                );
            }

            if (statusLabel != null) {

                statusLabel.setText(
                    "Status: Waiting for reader..."
                );
            }
        });
    }

    private static void updateStatus(
        String message,
        boolean error
    ) {

        SwingUtilities.invokeLater(() -> {

            if (statusLabel != null) {

                statusLabel.setText(
                    "Status: " + message
                );
            }

            if (!error && errorLabel != null) {

                errorLabel.setText(
                    "Error: -"
                );
            }
        });
    }

    private static void updateError(
        String message
    ) {

        System.err.println(message);

        SwingUtilities.invokeLater(() -> {

            if (errorLabel != null) {

                errorLabel.setText(
                    "Error: " + message
                );
            }
        });
    }

    private static void updateLastUid(
        String uid
    ) {

        SwingUtilities.invokeLater(() -> {

            if (lastUidLabel != null) {

                lastUidLabel.setText(
                    "Last scan: " + uid
                );
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

        if (exitButton != null) {
            exitButton.setEnabled(false);
        }

        if (statusLabel != null) {

            statusLabel.setText(
                "Status: Shutting down..."
            );
        }

        new Thread(() -> {

            sleep(300);

            SwingUtilities.invokeLater(() -> {

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