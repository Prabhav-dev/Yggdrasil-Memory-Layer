package Yggdrasil.gui;

import Yggdrasil.NioServer;
import Yggdrasil.RequestDispatcher;
import Yggdrasil.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Main application window organizing components into a 4-quadrant layout 
 * and controlling engine initialization and system shutdown.
 */
public class SupervisorConsole extends JFrame {

    private StorageManager storageManager;
    private WalManager walManager;
    private BPlusTree bPlusTree;
    private RecordManager recordManager;
    private NioServer nioServer;

    private final BPlusTreeCanvas bPlusTreeCanvas;
    private final HexInspectorPanel hexInspectorPanel;
    private final SecurityPanel securityPanel;
    private final ControlPanel controlPanel;

    private static final byte[] DEFAULT_MASTER_KEY = new byte[16];

    public SupervisorConsole() {
        super("Yggdrasil Storage Engine Supervisor Console");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);

        JPanel gridContainer = new JPanel(new GridLayout(2, 2, 5, 5));
        gridContainer.setBackground(Color.BLACK);
        gridContainer.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        bPlusTreeCanvas = new BPlusTreeCanvas();
        hexInspectorPanel = new HexInspectorPanel();
        securityPanel = new SecurityPanel();
        controlPanel = new ControlPanel();

        gridContainer.add(bPlusTreeCanvas);
        gridContainer.add(hexInspectorPanel);
        gridContainer.add(securityPanel);
        gridContainer.add(controlPanel);

        add(gridContainer, BorderLayout.CENTER);

        bPlusTreeCanvas.setPageSelectionListener(pageNum -> {
            hexInspectorPanel.setInspectPage(pageNum);
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                performShutdown();
            }
        });
    }

    private void bootEngine() {
        try {
            securityPanel.logEvent("Booting Yggdrasil database core...");

            storageManager = new StorageManager(DEFAULT_MASTER_KEY);
            storageManager.boot();

            walManager = new WalManager();
            walManager.open();

            bPlusTree = new BPlusTree(storageManager);
            recordManager = new RecordManager(storageManager, bPlusTree, walManager);
            
            securityPanel.logEvent("Initiating WAL recovery scan...");
            recordManager.redoRecovery();
            securityPanel.logEvent("WAL recovery complete.");

            bPlusTreeCanvas.refreshTree(storageManager);
            hexInspectorPanel.setStorageManager(storageManager);
            securityPanel.setStorageManager(storageManager);
            controlPanel.setEngine(storageManager, recordManager, () -> {
                bPlusTreeCanvas.refreshTree(storageManager);
                hexInspectorPanel.loadAndDisplayPage();
            });

            EngineTelemetry.registerObserver(securityPanel);
            
            EngineTelemetry.registerObserver(new EngineObserver() {
                @Override
                public void onPageFlush(int pageNumber, byte[] encryptedFrame) {
                    bPlusTreeCanvas.refreshTree(storageManager);
                    SwingUtilities.invokeLater(() -> hexInspectorPanel.loadAndDisplayPage());
                }

                @Override
                public void onPageFetch(int pageNumber, byte[] decryptedPayload) {
                    bPlusTreeCanvas.refreshTree(storageManager);
                }

                @Override
                public void onAuthFailure(int pageNumber, String errorMessage) {
                }
            });

            securityPanel.logEvent("Core engine booted successfully.");
            startNioServer();

        } catch (Exception ex) {
            securityPanel.logEvent("CRITICAL BOOT ERROR: " + ex.getMessage());
            ex.printStackTrace();
            JOptionPane.showMessageDialog(
                this, 
                "Failed to boot Yggdrasil Storage Engine:\n" + ex.getMessage(), 
                "Bootstrap Error", 
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void startNioServer() {
        new Thread(() -> {
            try {
                RequestDispatcher dispatcher = new RequestDispatcher(recordManager);
                nioServer = new NioServer(9090, dispatcher, 4);
                securityPanel.logEvent("Starting NIO Reactor server on port 9090...");
                nioServer.start();
            } catch (IOException ex) {
                securityPanel.logEvent("NIO Server failed to start: " + ex.getMessage());
            }
        }, "NioServer-Bootstrap").start();
    }

    private void performShutdown() {
        controlPanel.stopWorkload();
        
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                securityPanel.logEvent("Shutting down NIO reactor loops...");
                if (nioServer != null) {
                    nioServer.stop();
                }

                securityPanel.logEvent("Closing WAL manager file channels...");
                if (walManager != null) {
                    walManager.close();
                }

                securityPanel.logEvent("Evicting page caches and synching database to disk...");
                if (storageManager != null) {
                    storageManager.close();
                }
                return null;
            }

            @Override
            protected void done() {
                System.out.println("Supervisor console shutdown sequence completed.");
                System.exit(0);
            }
        }.execute();
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            SupervisorConsole console = new SupervisorConsole();
            console.setVisible(true);
            console.bootEngine();
        });
    }
}