package Yggdrasil.gui;

import Yggdrasil.core.*;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Security panel monitoring Ascon-128 cryptographic operations, event logging, 
 * and disk-level data tampering simulation.
 */
public class SecurityPanel extends JPanel implements EngineObserver {

    private final JLabel integrityStatusLabel;
    private final JTextArea logOutputArea;
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private StorageManager storageManager;

    public SecurityPanel() {
        setLayout(new BorderLayout());
        setBackground(new Color(40, 30, 30));

        // Header Panel
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setBackground(new Color(60, 40, 40));
        topContainer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel headerTitle = new JLabel("Security & Cryptographic Telemetry (Bottom-Left)");
        headerTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        headerTitle.setForeground(Color.WHITE);
        topContainer.add(headerTitle, BorderLayout.WEST);

        integrityStatusLabel = new JLabel("INTEGRITY: SECURE", SwingConstants.CENTER);
        integrityStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        integrityStatusLabel.setOpaque(true);
        integrityStatusLabel.setBackground(new Color(50, 150, 50));
        integrityStatusLabel.setForeground(Color.WHITE);
        integrityStatusLabel.setPreferredSize(new Dimension(160, 26));
        integrityStatusLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 1));
        topContainer.add(integrityStatusLabel, BorderLayout.EAST);

        add(topContainer, BorderLayout.NORTH);

        // Console Output
        logOutputArea = new JTextArea();
        logOutputArea.setEditable(false);
        logOutputArea.setBackground(new Color(20, 10, 10));
        logOutputArea.setForeground(new Color(255, 100, 100));
        logOutputArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logOutputArea.setMargin(new Insets(5, 5, 5, 5));

        JScrollPane scrollPane = new JScrollPane(logOutputArea);
        add(scrollPane, BorderLayout.CENTER);

        // Action Toolbar
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        bottomBar.setBackground(new Color(50, 35, 35));

        JButton tamperTrigger = new JButton("Simulate Tampering");
        tamperTrigger.setBackground(new Color(150, 50, 50));
        tamperTrigger.setForeground(Color.WHITE);
        tamperTrigger.setFocusPainted(false);
        tamperTrigger.addActionListener(e -> runTamperSimulation());
        bottomBar.add(tamperTrigger);

        JButton clearLogBtn = new JButton("Clear Logs");
        clearLogBtn.addActionListener(e -> logOutputArea.setText(""));
        bottomBar.add(clearLogBtn);

        JButton resetBadgeBtn = new JButton("Reset Status Badge");
        resetBadgeBtn.addActionListener(e -> setIntegritySecure(true));
        bottomBar.add(resetBadgeBtn);

        add(bottomBar, BorderLayout.SOUTH);

        logEvent("Security Monitor initialized. Awaiting cryptographic events...");
    }

    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public void setIntegritySecure(boolean isSecure) {
        SwingUtilities.invokeLater(() -> {
            if (isSecure) {
                integrityStatusLabel.setText("INTEGRITY: SECURE");
                integrityStatusLabel.setBackground(new Color(50, 150, 50));
            } else {
                integrityStatusLabel.setText("INTEGRITY: COMPROMISED!");
                integrityStatusLabel.setBackground(new Color(200, 50, 50));
            }
        });
    }

    public void logEvent(String entry) {
        SwingUtilities.invokeLater(() -> {
            String timeStr = timestampFormat.format(new Date());
            logOutputArea.append(String.format("[%s] %s\n", timeStr, entry));
            logOutputArea.setCaretPosition(logOutputArea.getDocument().getLength());
        });
    }

    private void runTamperSimulation() {
        if (storageManager == null) {
            logEvent("Simulation Error: StorageManager not set.");
            return;
        }

        logEvent("Tamper simulation: Initiating breach simulation sequence...");
        
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                int pageToCorrupt = 1;
                List<Integer> activePages = storageManager.getMetadata().getDataPageNumbers();
                if (!activePages.isEmpty()) {
                    pageToCorrupt = activePages.get(0);
                }

                RandomAccessFile diskFile = storageManager.getRaf();
                long targetOffset = (long) pageToCorrupt * Page.PAGE_SIZE + 48;
                
                byte originalValue = 0;
                boolean writeCompleted = false;

                publish("Simulation: Target page selected is physical block: " + pageToCorrupt);

                synchronized (storageManager) {
                    if (diskFile.length() > targetOffset) {
                        diskFile.seek(targetOffset);
                        originalValue = diskFile.readByte();
                        
                        diskFile.seek(targetOffset);
                        diskFile.writeByte(originalValue ^ 0xFF);
                        writeCompleted = true;
                        publish("Simulation: Injected byte corruption into encrypted file block at disk pos: " + targetOffset);
                    } else {
                        publish("Simulation Alert: Database file does not contain page " + pageToCorrupt + " yet. Perform some insertions first!");
                    }
                }

                if (!writeCompleted) {
                    return null;
                }

                storageManager.evictPageFromCache(pageToCorrupt);
                publish("Simulation: Evicted page " + pageToCorrupt + " from cache buffer pool.");

                publish("Simulation: Reading page " + pageToCorrupt + " to force integrity verification...");
                
                try {
                    storageManager.getPage(-1, pageToCorrupt);
                    publish("Simulation Warning: Page load completed without verification error. Check encryption settings.");
                } catch (IOException ex) {
                    publish("Simulation Success: Verification caught tampering! Exception thrown: " + ex.getMessage());
                } finally {
                    synchronized (storageManager) {
                        diskFile.seek(targetOffset);
                        diskFile.writeByte(originalValue);
                        publish("Simulation: Restored original disk block values to heal database.");
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> messages) {
                for (String msg : messages) {
                    logEvent(msg);
                }
            }
        }.execute();
    }

    @Override
    public void onPageFlush(int pageNumber, byte[] encryptedFrame) {
        logEvent(String.format("Ascon-128: Encrypted & flushed Page %d to disk (%d bytes)", pageNumber, encryptedFrame.length));
    }

    @Override
    public void onPageFetch(int pageNumber, byte[] decryptedPayload) {
        logEvent(String.format("Ascon-128: Fetched & decrypted Page %d successfully (Associated Data verified)", pageNumber));
    }

    @Override
    public void onAuthFailure(int pageNumber, String errorMessage) {
        logEvent(String.format("CRITICAL SECURITY ALERT: Authentication failed on Page %d: %s", pageNumber, errorMessage));
        setIntegritySecure(false);
        
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                this, 
                "ASCON-128 Cryptographic Signature Verification Mismatch!\n" +
                "Tampered data detected on Page " + pageNumber + ".\n" +
                "Storage Engine closed security gate to prevent data corruption.",
                "Cryptographic Integrity Mismatch", 
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}