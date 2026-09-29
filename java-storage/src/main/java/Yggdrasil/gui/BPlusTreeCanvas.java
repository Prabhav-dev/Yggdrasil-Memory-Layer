package Yggdrasil.gui;

import Yggdrasil.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive visual canvas rendering the state of the B+ Tree index with 
 * support for zooming, panning, and inspecting individual page nodes.
 */
public class BPlusTreeCanvas extends JPanel {

    private TreeNodeSnapshot rootSnapshot = null;
    private double zoomScale = 1.0;
    private int panX = 0;
    private int panY = 0;
    private Point lastMousePos = null;

    private final List<NodeBounds> renderedNodes = new ArrayList<>();
    private Consumer<Integer> nodeSelectListener = null;

    public static class TreeNodeSnapshot {
        public final int pageID;
        public final int pageNumber;
        public final boolean isLeaf;
        public final int[] keys;
        public final int keyCount;
        public final int parentID;
        public final int nextLeafPageID;
        public final List<TreeNodeSnapshot> children = new ArrayList<>();
        
        // Calculated screen positioning
        public int posX, posY;
        public int width, height;

        public TreeNodeSnapshot(int pageID, int pageNumber, boolean isLeaf, int[] keys, int keyCount, int parentID, int nextLeafPageID) {
            this.pageID = pageID;
            this.pageNumber = pageNumber;
            this.isLeaf = isLeaf;
            this.keys = keys;
            this.keyCount = keyCount;
            this.parentID = parentID;
            this.nextLeafPageID = nextLeafPageID;
        }
    }

    private static class NodeBounds {
        final int pageNumber;
        final Rectangle boundary;

        NodeBounds(int pageNumber, Rectangle boundary) {
            this.pageNumber = pageNumber;
            this.boundary = boundary;
        }
    }

    public BPlusTreeCanvas() {
        setBackground(new Color(30, 30, 40));
        setDoubleBuffered(true);

        MouseAdapter inputAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    lastMousePos = event.getPoint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || nodeSelectListener == null) {
                    return;
                }
                
                Point mouseClick = event.getPoint();
                double targetX = (mouseClick.x - panX) / zoomScale;
                double targetY = (mouseClick.y - panY) / zoomScale;

                for (NodeBounds item : renderedNodes) {
                    if (item.boundary.contains(targetX, targetY)) {
                        nodeSelectListener.accept(item.pageNumber);
                        break;
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (lastMousePos != null) {
                    Point currentPos = event.getPoint();
                    panX += (currentPos.x - lastMousePos.x);
                    panY += (currentPos.y - lastMousePos.y);
                    lastMousePos = currentPos;
                    repaint();
                }
            }
        };

        addMouseListener(inputAdapter);
        addMouseMotionListener(inputAdapter);

        addMouseWheelListener(event -> {
            double factor = 1.1;
            if (event.getWheelRotation() < 0) {
                zoomScale *= factor;
            } else {
                zoomScale /= factor;
            }
            zoomScale = Math.max(0.1, Math.min(zoomScale, 10.0));
            repaint();
        });
    }

    public void setPageSelectionListener(Consumer<Integer> listener) {
        this.nodeSelectListener = listener;
    }

    /**
     * Rebuilds B+ Tree node snapshot on a background thread and triggers repaint on EDT.
     */
    public void refreshTree(StorageManager storageManager) {
        new SwingWorker<TreeNodeSnapshot, Void>() {
            @Override
            protected TreeNodeSnapshot doInBackground() {
                synchronized (storageManager) {
                    int rootId = storageManager.getMetadata().getRootPageID();
                    if (rootId == -1) {
                        return null;
                    }
                    try {
                        return captureSnapshot(storageManager, rootId);
                    } catch (Exception ex) {
                        System.err.println("Error generating tree snapshot: " + ex.getMessage());
                        return null;
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    rootSnapshot = get();
                    recalculateTreeLayout();
                    repaint();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private TreeNodeSnapshot captureSnapshot(StorageManager sm, int pageNum) throws IOException {
        Page page = sm.getPage(-1, pageNum);
        try {
            if (page instanceof LeafNode) {
                LeafNode leaf = (LeafNode) page;
                int count = leaf.getKeyCount();
                int[] keys = Arrays.copyOf(leaf.getKeys(), count);
                return new TreeNodeSnapshot(leaf.getPageID(), leaf.getPageNumber(), true, keys, count, leaf.getParentPageID(), leaf.getNextLeafPageID());
            } else if (page instanceof InternalNode) {
                InternalNode internal = (InternalNode) page;
                int count = internal.getKeyCount();
                int[] keys = Arrays.copyOf(internal.getKeys(), count);
                int[] childPages = internal.getChildPageNumbers();
                
                TreeNodeSnapshot snapshot = new TreeNodeSnapshot(internal.getPageID(), internal.getPageNumber(), false, keys, count, internal.getParentPageID(), -1);
                for (int i = 0; i <= count; i++) {
                    int targetChild = childPages[i];
                    if (targetChild != -1) {
                        snapshot.children.add(captureSnapshot(sm, targetChild));
                    }
                }
                return snapshot;
            }
        } finally {
            sm.unpinPage(pageNum);
        }
        return null;
    }

    private void recalculateTreeLayout() {
        if (rootSnapshot == null) return;
        Map<Integer, Integer> levelRightBoundaries = new HashMap<>();
        positionNodesRecursive(rootSnapshot, 0, 50, levelRightBoundaries);
    }

    private void positionNodesRecursive(TreeNodeSnapshot node, int depth, int startX, Map<Integer, Integer> levelBounds) {
        int currentChildX = startX;
        for (TreeNodeSnapshot child : node.children) {
            positionNodesRecursive(child, depth + 1, currentChildX, levelBounds);
            currentChildX += computeSubtreeWidth(child) + 40;
        }

        node.width = 110;
        node.height = 55;
        node.posY = 40 + depth * 100;

        if (node.isLeaf) {
            int allocatedX = levelBounds.getOrDefault(depth, 40);
            node.posX = allocatedX;
            levelBounds.put(depth, allocatedX + node.width + 30);
        } else {
            if (!node.children.isEmpty()) {
                int leftmostChildX = node.children.get(0).posX;
                int rightmostChildX = node.children.get(node.children.size() - 1).posX;
                node.posX = (leftmostChildX + rightmostChildX) / 2;
            } else {
                int allocatedX = levelBounds.getOrDefault(depth, 40);
                node.posX = allocatedX;
                levelBounds.put(depth, allocatedX + node.width + 30);
            }
        }
    }

    private int computeSubtreeWidth(TreeNodeSnapshot node) {
        if (node == null) return 0;
        if (node.isLeaf || node.children.isEmpty()) return 110;
        int sum = 0;
        for (TreeNodeSnapshot child : node.children) {
            sum += computeSubtreeWidth(child) + 40;
        }
        return sum - 40;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(Color.LIGHT_GRAY);
        g2.drawString("B+ Tree Topology Canvas (Top-Left)", 10, 20);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g2.drawString("Drag: Pan | Scroll: Zoom | Click node: Inspect Page", 10, 35);

        if (rootSnapshot == null) {
            g2.setColor(Color.GRAY);
            g2.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2.drawString("EMPTY TREE INDEX", getWidth() / 2 - 80, getHeight() / 2);
            return;
        }

        renderedNodes.clear();
        AffineTransform previousTransform = g2.getTransform();
        
        g2.translate(panX, panY);
        g2.scale(zoomScale, zoomScale);

        renderTreeConnections(g2, rootSnapshot);
        renderLeafPointers(g2, rootSnapshot, new ArrayList<>());
        renderTreeNodes(g2, rootSnapshot);

        g2.setTransform(previousTransform);
    }

    private void renderTreeConnections(Graphics2D g2, TreeNodeSnapshot node) {
        g2.setColor(new Color(100, 100, 120));
        g2.setStroke(new BasicStroke(1.5f));
        for (TreeNodeSnapshot child : node.children) {
            g2.drawLine(node.posX + node.width / 2, node.posY + node.height, child.posX + child.width / 2, child.posY);
            renderTreeConnections(g2, child);
        }
    }

    private void renderLeafPointers(Graphics2D g2, TreeNodeSnapshot node, List<TreeNodeSnapshot> leafCollector) {
        if (node.isLeaf) {
            leafCollector.add(node);
        } else {
            for (TreeNodeSnapshot child : node.children) {
                renderLeafPointers(g2, child, leafCollector);
            }
        }

        if (node.parentID == -1) {
            g2.setColor(new Color(255, 150, 50, 180));
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0f, new float[]{4f, 4f}, 0.0f));
            for (int i = 0; i < leafCollector.size() - 1; i++) {
                TreeNodeSnapshot current = leafCollector.get(i);
                TreeNodeSnapshot next = leafCollector.get(i + 1);
                if (current.nextLeafPageID == next.pageNumber) {
                    int startX = current.posX + current.width;
                    int startY = current.posY + current.height / 2;
                    int endX = next.posX;
                    g2.drawArc(startX, startY - 15, endX - startX, 30, 0, 180);
                }
            }
        }
    }

    private void renderTreeNodes(Graphics2D g2, TreeNodeSnapshot node) {
        renderedNodes.add(new NodeBounds(node.pageNumber, new Rectangle(node.posX, node.posY, node.width, node.height)));

        Color background;
        Color outline;
        int maxCapacity = node.isLeaf ? LeafNode.MAX_KEYS : InternalNode.MAX_KEYS;
        int minCapacity = maxCapacity / 2;

        if (node.keyCount >= maxCapacity) {
            background = new Color(80, 20, 20);
            outline = Color.RED;
        } else if (node.keyCount < minCapacity) {
            background = new Color(80, 50, 20);
            outline = Color.ORANGE;
        } else {
            background = new Color(20, 50, 30);
            outline = new Color(50, 200, 100);
        }

        g2.setColor(background);
        g2.fillRoundRect(node.posX, node.posY, node.width, node.height, 10, 10);
        g2.setColor(outline);
        g2.setStroke(new BasicStroke(2.0f));
        g2.drawRoundRect(node.posX, node.posY, node.width, node.height, 10, 10);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.drawString((node.isLeaf ? "Leaf Page: " : "Internal Page: ") + node.pageNumber, node.posX + 8, node.posY + 16);

        g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
        g2.setColor(new Color(200, 220, 255));
        StringBuilder keysFormatted = new StringBuilder("Keys: [");
        for (int i = 0; i < Math.min(node.keyCount, 3); i++) {
            if (i > 0) keysFormatted.append(",");
            keysFormatted.append(node.keys[i]);
        }
        if (node.keyCount > 3) keysFormatted.append("..");
        keysFormatted.append("]");
        g2.drawString(keysFormatted.toString(), node.posX + 8, node.posY + 32);

        g2.setFont(new Font("SansSerif", Font.ITALIC, 8));
        g2.setColor(Color.GRAY);
        g2.drawString("Keys count: " + node.keyCount, node.posX + 8, node.posY + 46);

        for (TreeNodeSnapshot child : node.children) {
            renderTreeNodes(g2, child);
        }
    }
}