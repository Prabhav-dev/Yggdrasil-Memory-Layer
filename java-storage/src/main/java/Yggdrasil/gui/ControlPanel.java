package Yggdrasil.gui;

import Yggdrasil.core.*;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Control and operational monitoring panel for manually testing data operations
 * and running synthetic stress workloads against the storage engine.
 */
public class ControlPanel extends JPanel {

    private final JLabel cacheHitRatioLabel;
    private final JLabel cacheHitsLabel;
    private final JLabel cacheMissesLabel;
    private final JLabel ioOpsLabel;
    private final JLabel ioThroughputLabel;

    private final JSpinner keySpinner;
    private final JTextField valueField;
    private final JTextArea resultArea;

    private final JToggleButton workloadToggle;
    private ScheduledExecutorService workloadService;
    private final Random randomGenerator = new Random();

    private StorageManager storageManager;
    private RecordManager recordManager;
    private Runnable onEngineMutationCallback;

    private long previousDiskReadBytes = 0;
    private long previousDiskWriteBytes = 0;
    private long previousTimestamp = System.currentTimeMillis();

    public ControlPanel() {
        setLayout(new BorderLayout());
        setBackground(new Color(30, 40, 50));

        // Metric Counters Header
        JPanel metricsContainer = new JPanel(new GridLayout(2, 3, 10, 10));
        metricsContainer.setBackground(new Color(40, 55, 70));
        metricsContainer.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), 
            "Performance Counters (Live)", 0, 0, null, Color.WHITE
        ));

        cacheHitRatioLabel = buildStatLabel("Cache Hit Ratio: 100.0%");
        cacheHitsLabel = buildStatLabel("Cache Hits: 0");
        cacheMissesLabel = buildStatLabel("Cache Misses: 0");
        ioOpsLabel = buildStatLabel("Total I/O Ops: 0");
        ioThroughputLabel = buildStatLabel("Throughput: 0 B/s");

        metricsContainer.add(cacheHitRatioLabel);
        metricsContainer.add(cacheHitsLabel);
        metricsContainer.add(cacheMissesLabel);
        metricsContainer.add(ioOpsLabel);
        metricsContainer.add(ioThroughputLabel);
        metricsContainer.add(new JLabel());

        add(metricsContainer, BorderLayout.NORTH);

        // Interactive Execution Section
        JPanel workspacePanel = new JPanel(new GridLayout(1, 2, 10, 0));
        workspacePanel.setBackground(new Color(30, 40, 50));
        workspacePanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // Manual Query Panel
        JPanel queryGroup = new JPanel();
        queryGroup.setLayout(new BoxLayout(queryGroup, BoxLayout.Y_AXIS));
        queryGroup.setBackground(new Color(35, 48, 60));
        queryGroup.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), 
            "Manual Execution Control", 0, 0, null, Color.WHITE
        ));

        JPanel formFields = new JPanel(new GridLayout(2, 2, 5, 5));
        formFields.setBackground(new Color(35, 48, 60));

        JLabel keyLabel = new JLabel("Key (Int):", SwingConstants.RIGHT);
        keyLabel.setForeground(Color.WHITE);
        formFields.add(keyLabel);

        keySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 99999, 1));
        formFields.add(keySpinner);

        JLabel valLabel = new JLabel("Value (String):", SwingConstants.RIGHT);
        valLabel.setForeground(Color.WHITE);
        formFields.add(valLabel);

        valueField = new JTextField("Payload");
        formFields.add(valueField);
        queryGroup.add(formFields);

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        actionButtons.setBackground(new Color(35, 48, 60));

        JButton insertButton = new JButton("PUT");
        insertButton.addActionListener(e -> executeManualPut());

        JButton fetchButton = new JButton("GET");
        fetchButton.addActionListener(e -> executeManualGet());

        JButton removeButton = new JButton("DELETE");
        removeButton.addActionListener(e -> executeManualDelete());

        actionButtons.add(insertButton);
        actionButtons.add(fetchButton);
        actionButtons.add(removeButton);
        queryGroup.add(actionButtons);

        resultArea = new JTextArea(3, 20);
        resultArea.setEditable(false);
        resultArea.setBackground(new Color(20, 30, 40));
        resultArea.setForeground(Color.CYAN);
        resultArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        queryGroup.add(new JScrollPane(resultArea));

        workspacePanel.add(queryGroup);

        // Automated Workload Panel
        JPanel workloadGroup = new JPanel(new BorderLayout());
        workloadGroup.setBackground(new Color(35, 48, 60));
        workloadGroup.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), 
            "Automated Workload Generator", 0, 0, null, Color.WHITE
        ));

        workloadToggle = new JToggleButton("Start Workload Generator");
        workloadToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        workloadToggle.setBackground(new Color(50, 100, 150));
        workloadToggle.setForeground(Color.WHITE);
        workloadToggle.setFocusPainted(false);
        workloadToggle.addActionListener(e -> toggleWorkload());
        workloadGroup.add(workloadToggle, BorderLayout.CENTER);

        JLabel description = new JLabel(
            "<html><body style='text-align: center; color: #CCCCCC;'><br>Simulates high concurrent client load.<br>Generates inserts, reads, and deletes.<br>Triggers B+ Tree splits and leaf borrows.</body></html>", 
            SwingConstants.CENTER
        );
        workloadGroup.add(description, BorderLayout.SOUTH);

        workspacePanel.add(workloadGroup);
        add(workspacePanel, BorderLayout.CENTER);

        // Periodic counter telemetry refresh
        new javax.swing.Timer(500, e -> refreshPerformanceMetrics()).start();
    }

    private JLabel buildStatLabel(String caption) {
        JLabel label = new JLabel(caption);
        label.setForeground(new Color(200, 230, 255));
        label.setFont(new Font("SansSerif", Font.BOLD, 11));
        return label;
    }

    public void setEngine(StorageManager sm, RecordManager rm, Runnable mutationCallback) {
        this.storageManager = sm;
        this.recordManager = rm;
        this.onEngineMutationCallback = mutationCallback;
    }

    private void executeManualPut() {
        if (recordManager == null) return;
        int targetKey = (int) keySpinner.getValue();
        byte[] payloadData = valueField.getText().getBytes(StandardCharsets.UTF_8);

        resultArea.setText("Executing PUT...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                synchronized (storageManager) {
                    recordManager.insert(targetKey, payloadData);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    resultArea.setText("SUCCESS: PUT Key = " + targetKey);
                    if (onEngineMutationCallback != null) onEngineMutationCallback.run();
                } catch (Exception ex) {
                    resultArea.setText("ERROR: " + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void executeManualGet() {
        if (recordManager == null) return;
        int targetKey = (int) keySpinner.getValue();

        resultArea.setText("Executing GET...");
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                synchronized (storageManager) {
                    return recordManager.find(targetKey);
                }
            }

            @Override
            protected void done() {
                try {
                    byte[] payload = get();
                    if (payload == null) {
                        resultArea.setText("NOT FOUND: Key = " + targetKey);
                    } else {
                        String payloadStr = new String(payload, StandardCharsets.UTF_8);
                        resultArea.setText("FOUND: Key = " + targetKey + "\nValue = " + payloadStr);
                    }
                    if (onEngineMutationCallback != null) onEngineMutationCallback.run();
                } catch (Exception ex) {
                    resultArea.setText("ERROR: " + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void executeManualDelete() {
        if (recordManager == null) return;
        int targetKey = (int) keySpinner.getValue();

        resultArea.setText("Executing DELETE...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                synchronized (storageManager) {
                    recordManager.delete(targetKey);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    resultArea.setText("SUCCESS: DELETED Key = " + targetKey);
                    if (onEngineMutationCallback != null) onEngineMutationCallback.run();
                } catch (Exception ex) {
                    resultArea.setText("ERROR: " + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void toggleWorkload() {
        if (workloadToggle.isSelected()) {
            workloadToggle.setText("Stop Workload Generator");
            workloadToggle.setBackground(new Color(150, 50, 50));
            
            workloadService = Executors.newSingleThreadScheduledExecutor();
            workloadService.scheduleWithFixedDelay(this::executeWorkloadIteration, 0, 300, TimeUnit.MILLISECONDS);
            resultArea.setText("Workload Generator started.");
        } else {
            workloadToggle.setText("Start Workload Generator");
            workloadToggle.setBackground(new Color(50, 100, 150));
            if (workloadService != null) {
                workloadService.shutdown();
                workloadService = null;
            }
            resultArea.setText("Workload Generator stopped.");
        }
    }

    private void executeWorkloadIteration() {
        if (recordManager == null) return;
        try {
            int actionIndex = randomGenerator.nextInt(10);
            int targetKey = randomGenerator.nextInt(1000) + 1;
            
            synchronized (storageManager) {
                if (actionIndex < 5) {
                    byte[] payload = ("Value-" + targetKey + "-" + randomGenerator.nextInt(100)).getBytes(StandardCharsets.UTF_8);
                    recordManager.insert(targetKey, payload);
                } else if (actionIndex < 8) {
                    recordManager.find(targetKey);
                } else {
                    recordManager.delete(targetKey);
                }
            }
            
            if (onEngineMutationCallback != null) {
                SwingUtilities.invokeLater(onEngineMutationCallback);
            }
        } catch (Exception ex) {
            System.err.println("Workload error: " + ex.getMessage());
        }
    }

    private void refreshPerformanceMetrics() {
        double hitRatio = EngineTelemetry.getCacheHitRatio();
        long hits = EngineTelemetry.getCacheHits();
        long misses = EngineTelemetry.getCacheMisses();
        long totalIoOps = EngineTelemetry.getIoOperations();
        long totalBytesRead = EngineTelemetry.getDiskBytesRead();
        long totalBytesWritten = EngineTelemetry.getDiskBytesWritten();

        long now = System.currentTimeMillis();
        double elapsedSec = (now - previousTimestamp) / 1000.0;
        
        long readDelta = totalBytesRead - previousDiskReadBytes;
        long writeDelta = totalBytesWritten - previousDiskWriteBytes;
        
        double readSpeed = elapsedSec <= 0 ? 0 : readDelta / elapsedSec;
        double writeSpeed = elapsedSec <= 0 ? 0 : writeDelta / elapsedSec;

        previousDiskReadBytes = totalBytesRead;
        previousDiskWriteBytes = totalBytesWritten;
        previousTimestamp = now;

        SwingUtilities.invokeLater(() -> {
            cacheHitRatioLabel.setText(String.format("Cache Hit Ratio: %.1f%%", hitRatio * 100));
            cacheHitsLabel.setText("Cache Hits: " + hits);
            cacheMissesLabel.setText("Cache Misses: " + misses);
            ioOpsLabel.setText("Total I/O Ops: " + totalIoOps);
            ioThroughputLabel.setText(String.format("I/O Speed: R: %.1f KB/s | W: %.1f KB/s", readSpeed / 1024.0, writeSpeed / 1024.0));
        });
    }

    public void stopWorkload() {
        if (workloadService != null) {
            workloadService.shutdown();
        }
    }
}