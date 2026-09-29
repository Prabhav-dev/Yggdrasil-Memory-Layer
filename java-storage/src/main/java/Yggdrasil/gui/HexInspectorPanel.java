package Yggdrasil.gui;

import Yggdrasil.core.*;
import Yggdrasil.crypto.Ascon128Engine;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Hexadecimal and ASCII memory block inspector providing byte-level visual breakdown 
 * of physical database page layouts.
 */
public class HexInspectorPanel extends JPanel {

    private final JTable hexTable;
    private final DefaultTableModel tableModel;
    private final JSpinner pageSelector;
    private StorageManager storageManager;
    private int selectedPageNumber = 0;

    public HexInspectorPanel() {
        setLayout(new BorderLayout());
        setBackground(Color.DARK_GRAY);

        // Header controls
        JPanel topBar = new JPanel(new FlowBorderLayout());
        topBar.setBackground(new Color(50, 50, 50));
        
        JLabel pickerLabel = new JLabel("Page Number to Inspect: ");
        pickerLabel.setForeground(Color.WHITE);
        topBar.add(pickerLabel);

        pageSelector = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
        pageSelector.setPreferredSize(new Dimension(80, 25));
        pageSelector.addChangeListener(e -> {
            selectedPageNumber = (int) pageSelector.getValue();
            loadAndDisplayPage();
        });
        topBar.add(pageSelector);

        JButton reloadBtn = new JButton("Refresh");
        reloadBtn.addActionListener(e -> loadAndDisplayPage());
        topBar.add(reloadBtn);

        add(topBar, BorderLayout.NORTH);

        // Grid setup
        String[] headers = new String[18];
        headers[0] = "Offset";
        for (int i = 1; i <= 16; i++) {
            headers[i] = String.format("%02X", i - 1);
        }
        headers[17] = "ASCII";

        tableModel = new DefaultTableModel(headers, 256) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        hexTable = new JTable(tableModel);
        hexTable.setGridColor(new Color(60, 60, 60));
        hexTable.setShowGrid(true);
        hexTable.setFillsViewportHeight(true);
        hexTable.setRowHeight(20);
        hexTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        hexTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        for (int i = 1; i <= 16; i++) {
            hexTable.getColumnModel().getColumn(i).setPreferredWidth(32);
        }
        hexTable.getColumnModel().getColumn(17).setPreferredWidth(120);

        HexCellRenderer cellRenderer = new HexCellRenderer();
        for (int i = 0; i < 18; i++) {
            hexTable.getColumnModel().getColumn(i).setCellRenderer(cellRenderer);
        }

        JScrollPane scrollPane = new JScrollPane(hexTable);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(scrollPane, BorderLayout.CENTER);

        // Color Legend
        JPanel legendPanel = new JPanel(new GridLayout(1, 6, 5, 5));
        legendPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), 
            "Visual Memory Region Legend", 0, 0, null, Color.WHITE
        ));
        legendPanel.setBackground(new Color(40, 40, 40));
        legendPanel.add(buildLegendBadge("Header", new Color(220, 240, 255)));
        legendPanel.add(buildLegendBadge("Crypto Meta", new Color(240, 220, 255)));
        legendPanel.add(buildLegendBadge("Decrypted Hdr", new Color(220, 255, 220)));
        legendPanel.add(buildLegendBadge("Slots Dir", new Color(255, 255, 200)));
        legendPanel.add(buildLegendBadge("Free Space", new Color(240, 240, 240)));
        legendPanel.add(buildLegendBadge("Payloads", new Color(255, 220, 200)));
        add(legendPanel, BorderLayout.SOUTH);
    }

    private JPanel buildLegendBadge(String title, Color bgColor) {
        JPanel badge = new JPanel(new BorderLayout());
        badge.setBackground(bgColor);
        JLabel label = new JLabel(title, SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.BOLD, 10));
        label.setForeground(Color.BLACK);
        badge.add(label, BorderLayout.CENTER);
        badge.setPreferredSize(new Dimension(80, 20));
        return badge;
    }

    public void setStorageManager(StorageManager storageManager) {
        this.storageManager = storageManager;
        loadAndDisplayPage();
    }

    public void setInspectPage(int pageNumber) {
        pageSelector.setValue(pageNumber);
        selectedPageNumber = pageNumber;
        loadAndDisplayPage();
    }

    public void loadAndDisplayPage() {
        if (storageManager == null) return;
        
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() {
                synchronized (storageManager) {
                    try {
                        return readVirtualPageBytes(selectedPageNumber);
                    } catch (Exception ex) {
                        System.err.println("Error constructing virtual inspect block: " + ex.getMessage());
                        return new byte[4096];
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    byte[] rawData = get();
                    populateGrid(rawData);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private byte[] readVirtualPageBytes(int pageNum) throws IOException {
        byte[] pageBuffer = new byte[4096];
        RandomAccessFile raf = storageManager.getRaf();
        if (raf == null || raf.length() < (long) (pageNum + 1) * Page.PAGE_SIZE) {
            return pageBuffer;
        }

        byte[] diskFrame = new byte[4096];
        long offset = (long) pageNum * Page.PAGE_SIZE;
        synchronized (storageManager) {
            long currentPos = raf.getFilePointer();
            raf.seek(offset);
            raf.readFully(diskFrame);
            raf.seek(currentPos);
        }

        if (pageNum == 0) {
            byte[] nonce = new byte[16];
            System.arraycopy(diskFrame, 0, nonce, 0, 16);
            byte[] ciphertext = new byte[4080];
            System.arraycopy(diskFrame, 16, ciphertext, 0, 4080);
            byte[] payload = Ascon128Engine.decryptPayload(storageManager.getSecretKey(), nonce, Page.associatedData(0), ciphertext);
            
            if (payload != null) {
                System.arraycopy(payload, 0, pageBuffer, 0x0000, 16);
                System.arraycopy(nonce, 0, pageBuffer, 0x0010, 16);
                System.arraycopy(diskFrame, 4080, pageBuffer, 0x0020, 16);
                System.arraycopy(payload, 16, pageBuffer, 0x0030, payload.length - 16);
            } else {
                System.arraycopy(diskFrame, 0, pageBuffer, 0, 4096);
            }
            return pageBuffer;
        }

        Page loadedPage = storageManager.getPage(-1, pageNum);
        try {
            byte[] nonce = new byte[Page.NONCE_SIZE];
            System.arraycopy(diskFrame, Page.OFFSET_NONCE, nonce, 0, Page.NONCE_SIZE);
            byte[] authTag = new byte[Page.TAG_SIZE];
            System.arraycopy(diskFrame, Page.OFFSET_TAG, authTag, 0, Page.TAG_SIZE);

            byte[] payload = loadedPage.getPayloadBytes();

            ByteBuffer headerBuffer = ByteBuffer.allocate(16);
            headerBuffer.putInt(0x4153434F);
            headerBuffer.putInt(0xDEADBEEF);
            headerBuffer.putInt(loadedPage.getPageID());
            
            byte pageTypeFlag = (loadedPage instanceof SlottedPage) ? StorageManager.TYPE_SLOTTED_DATA : 
                                ((loadedPage instanceof LeafNode) ? StorageManager.TYPE_LEAF : StorageManager.TYPE_INTERNAL);
            headerBuffer.put(pageTypeFlag);
            headerBuffer.put((byte) (loadedPage.isDirty() ? 1 : 0));
            
            short itemCount = (loadedPage instanceof SlottedPage) ? ((SlottedPage) loadedPage).getSlotCount() : 
                              ((BTreeNode) loadedPage).getKeyCount();
            headerBuffer.putShort(itemCount);
            
            System.arraycopy(headerBuffer.array(), 0, pageBuffer, 0x0000, 16);
            System.arraycopy(nonce, 0, pageBuffer, 0x0010, 16);
            System.arraycopy(authTag, 0, pageBuffer, 0x0020, 16);
            System.arraycopy(payload, 0, pageBuffer, 0x0030, 16);
            System.arraycopy(payload, 16, pageBuffer, 0x0040, Math.min(4064 - 16, 4096 - 0x0040));

        } finally {
            storageManager.unpinPage(pageNum);
        }

        return pageBuffer;
    }

    private void populateGrid(byte[] data) {
        for (int r = 0; r < 256; r++) {
            int rowOffset = r * 16;
            tableModel.setValueAt(String.format("%04X", rowOffset), r, 0);

            StringBuilder asciiText = new StringBuilder();
            for (int c = 0; c < 16; c++) {
                byte val = data[rowOffset + c];
                tableModel.setValueAt(String.format("%02X", val), r, c + 1);

                if (val >= 32 && val < 127) {
                    asciiText.append((char) val);
                } else {
                    asciiText.append('.');
                }
            }
            tableModel.setValueAt(asciiText.toString(), r, 17);
        }
    }

    private static class HexCellRenderer extends DefaultTableCellRenderer {
        private final Font monospaceFont = new Font("Monospaced", Font.PLAIN, 12);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setFont(monospaceFont);

            if (column == 0) {
                c.setBackground(Color.DARK_GRAY);
                c.setForeground(Color.WHITE);
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }

            if (column == 17) {
                c.setBackground(new Color(50, 50, 50));
                c.setForeground(Color.GREEN);
                setHorizontalAlignment(SwingConstants.LEFT);
                return c;
            }

            int index = row * 16 + (column - 1);
            Color cellColor;
            if (index >= 0x0000 && index <= 0x000F) {
                cellColor = new Color(220, 240, 255);
            } else if (index >= 0x0010 && index <= 0x002F) {
                cellColor = new Color(240, 220, 255);
            } else if (index >= 0x0030 && index <= 0x003F) {
                cellColor = new Color(220, 255, 220);
            } else if (index >= 0x0040 && index <= 0x007F) {
                cellColor = new Color(255, 255, 200);
            } else if (index >= 0x0080 && index <= 0x0EB0) {
                cellColor = new Color(240, 240, 240);
            } else {
                cellColor = new Color(255, 220, 200);
            }

            c.setBackground(cellColor);
            c.setForeground(Color.BLACK);
            setHorizontalAlignment(SwingConstants.CENTER);
            return c;
        }
    }

    private static class FlowBorderLayout extends java.awt.FlowLayout {
        public FlowBorderLayout() {
            super(java.awt.FlowLayout.LEFT, 10, 5);
        }
    }
}