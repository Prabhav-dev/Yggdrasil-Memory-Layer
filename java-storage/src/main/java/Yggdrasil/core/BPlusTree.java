package Yggdrasil.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BPlusTree class implements an index engine for key-value (TupleID) pairs.
 * Supports point lookups, range scans, and slotted page data integration.
 */
public class BPlusTree {
    
    private final StorageManager storageManager;

    public BPlusTree(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /**
     * Gets the current root page ID of the B+Tree index.
     * Returns -1 if the tree is empty.
     */
    public synchronized int getRootID() {
        return storageManager.getMetadata().getRootPageID();
    }

    public enum Operator {
        EQUAL, 
        LESS_THAN, 
        LESS_THAN_OR_EQUAL, 
        GREATER_THAN, 
        GREATER_THAN_OR_EQUAL
    }

    public record Entry(int key, long value) {}

    /**
     * High-level helper: Inserts raw record bytes into a SlottedPage and indexes 
     * the generated TupleID locator under the specified key.
     */
    public synchronized long insertRecord(int targetDataPageID, int searchKey, byte[] recordData) throws IOException {
        SlottedPage dataPage = (SlottedPage) storageManager.getPage(-1, targetDataPageID);
        long tid;

        try {
            tid = dataPage.insertRecord(recordData);
            if (tid != -1L) {
                dataPage.setDirty(true);
            } else {
                // Page full: Allocate a new slotted page if target cannot fit record
                SlottedPage newPage = (SlottedPage) storageManager.allocateNewPage(StorageManager.TYPE_SLOTTED_DATA);
                try {
                    tid = newPage.insertRecord(recordData);
                    if (tid != -1L) {
                        newPage.setDirty(true);
                    }
                } finally {
                    storageManager.unpinPage(newPage.getPageNumber());
                }
            }
        } finally {
            storageManager.unpinPage(dataPage.getPageNumber());
        }

        if (tid != -1L) {
            insert(searchKey, tid); // Index the packed TupleID
        }

        return tid;
    }

    /**
     * Inserts a key and its packed 64-bit Tuple ID pointer into the B+Tree.
     */
    public synchronized void insert(int key, long value) throws IOException {
        int rootID = storageManager.getMetadata().getRootPageID();

        // Fixed: Check purely rootID == -1 so allocated data pages don't block index creation
        if (rootID == -1) {
            LeafNode root = (LeafNode) storageManager.allocateNewPage(StorageManager.TYPE_LEAF);
            root.insert(key, value);
            root.setDirty(true);

            storageManager.setRootPageID(root.getPageID());
            storageManager.unpinPage(root.getPageNumber());
            return;
        }

        LeafNode targetLeaf = findLeafNode(key);
        try {
            boolean success = targetLeaf.insert(key, value);
            
            if (!success) {
                boolean isDuplicate = false;
                for (int i = 0; i < targetLeaf.getKeyCount(); i++) {
                    if (targetLeaf.getKeys()[i] == key) {
                        isDuplicate = true;
                        break;
                    }
                }
                
                if (isDuplicate) {
                    return; // Retains unpin via finally block
                }

                splitLeaf(targetLeaf, key, value);
            } else {
                targetLeaf.setDirty(true);
            }
        } finally {
            storageManager.unpinPage(targetLeaf.getPageNumber());
        }
    }

    /**
     * Executes point lookups. Returns packed 64-bit TupleID or null if missing.
     */
    public synchronized Long search(int key) throws IOException {
        int rootID = storageManager.getMetadata().getRootPageID();

        // Fixed: Skip check for isNewDatabase() to prevent false null returns
        if (rootID == -1) {
            return null;
        }

        LeafNode leaf = findLeafNode(key);
        try {
            int low = 0;
            int high = leaf.getKeyCount() - 1;
            
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int midKey = leaf.getKeys()[mid];

                if (midKey == key) {
                    return leaf.getValues()[mid];
                } else if (midKey < key) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }

            return null;
        } finally {
            storageManager.unpinPage(leaf.getPageNumber());
        }
    }

    public synchronized Integer maxKey() throws IOException {
        if (storageManager.getMetadata().getRootPageID() == -1) {
            return null;
        }

        LeafNode leaf = findLeafNode(Integer.MAX_VALUE);
        try {
            int count = leaf.getKeyCount();
            return count == 0 ? null : leaf.getKeys()[count - 1];
        } finally {
            storageManager.unpinPage(leaf.getPageNumber());
        }
    }

    /**
     * Latch-Crabbing Downward Traversal Engine with clean resource exception handling.
     */
    LeafNode findLeafNode(int targetKey) throws IOException {
        int currentID = storageManager.getMetadata().getRootPageID();
        if (currentID == -1) {
            throw new IOException("Index Read Error: Core traversal invoked on unallocated tree root.");
        }

        Page page = storageManager.getPage(-1, currentID);

        while (page instanceof BTreeNode && ((BTreeNode) page).getPageType() == StorageManager.TYPE_INTERNAL) {
            InternalNode internal = (InternalNode) page;
            int nextPageNum = internal.findChildPageNumber(targetKey);
            
            Page childPage;
            try {
                childPage = storageManager.getPage(-1, nextPageNum); 
            } catch (Exception e) {
                storageManager.unpinPage(internal.getPageNumber());
                throw e;
            }
            
            storageManager.unpinPage(internal.getPageNumber());
            page = childPage;
        }
        
        if (!(page instanceof LeafNode)) {
            storageManager.unpinPage(page.getPageNumber());
            throw new IOException("Structural Index Error: Target node is not a valid LeafNode.");
        }

        return (LeafNode) page;
    }

    public synchronized void insertIntoParent(BTreeNode leftNode, int splitKey, BTreeNode rightNode) throws IOException {
        if (leftNode.getParentPageID() == -1) {
            InternalNode newRoot = (InternalNode) storageManager.allocateNewPage(StorageManager.TYPE_INTERNAL);

            try {
                int newRootID = newRoot.getPageID();
                leftNode.setParentPageID(newRootID);
                rightNode.setParentPageID(newRootID);

                newRoot.setKeyCount((short) 1);
                newRoot.getKeys()[0] = splitKey;
                
                newRoot.getChildPageIDs()[0] = leftNode.getPageID();
                newRoot.getChildPageIDs()[1] = rightNode.getPageID();

                leftNode.setDirty(true);
                rightNode.setDirty(true);
                newRoot.setDirty(true);

                storageManager.setRootPageID(newRootID);
            } finally {
                storageManager.unpinPage(newRoot.getPageNumber());
            }
            return;
        }

        int parentID = leftNode.getParentPageID();
        Page parentPage = storageManager.getPage(-1, parentID);
        
        if (!(parentPage instanceof InternalNode)) {
            storageManager.unpinPage(parentPage.getPageNumber());
            throw new IOException("Structural Corruption: Parent pointer is not an InternalNode.");
        }
        
        InternalNode parent = (InternalNode) parentPage;

        try {
            if (!parent.insert(splitKey, rightNode.getPageID())) {
                splitInternal(parent, splitKey, rightNode.getPageID());
            } else {
                parent.setDirty(true);
            }
        } finally {
            storageManager.unpinPage(parent.getPageNumber());
        }
    }

    public synchronized void delete(int key) throws IOException {
        if (storageManager.getMetadata().getRootPageID() == -1) {
            return;
        }

        LeafNode targetLeaf = findLeafNode(key);
        try {
            int idx = -1;
            for (int i = 0; i < targetLeaf.getKeyCount(); i++) {
                if (targetLeaf.getKeys()[i] == key) {
                    idx = i;
                    break;
                }
            }

            if (idx == -1) {
                return; 
            }

            for (int i = idx; i < targetLeaf.getKeyCount() - 1; i++) {
                targetLeaf.getKeys()[i] = targetLeaf.getKeys()[i + 1];
                targetLeaf.getValues()[i] = targetLeaf.getValues()[i + 1];
            }
            
            targetLeaf.setKeyCount((short) (targetLeaf.getKeyCount() - 1));
            targetLeaf.setDirty(true);

            if (targetLeaf.getParentPageID() == -1) {
                if (targetLeaf.getKeyCount() == 0) {
                    storageManager.setRootPageID(-1);
                }
                return;
            }

            if (targetLeaf.getKeyCount() < (LeafNode.MAX_KEYS / 2)) {
                balanceLeaf(targetLeaf);
            }
        } finally {
            storageManager.unpinPage(targetLeaf.getPageNumber());
        }
    }

    public synchronized List<Long> query(int targetKey, Operator op) throws IOException {
        List<Long> results = new ArrayList<>();
        if (storageManager.getMetadata().getRootPageID() == -1) {
            return results;
        }
        
        if (op == Operator.EQUAL) {
            Long val = search(targetKey);
            if (val != null) {
                results.add(val);
            }
            return results;
        }
        
        if (op == Operator.GREATER_THAN || op == Operator.GREATER_THAN_OR_EQUAL) {
            LeafNode currentLeaf = findLeafNode(targetKey);

            while (currentLeaf != null) {
                int activePageNum = currentLeaf.getPageNumber();
                int nextID;

                try {
                    int[] keys = currentLeaf.getKeys();
                    long[] values = currentLeaf.getValues();
                    
                    for (int i = 0; i < currentLeaf.getKeyCount(); i++) {
                        if (op == Operator.GREATER_THAN_OR_EQUAL && keys[i] >= targetKey) {
                            results.add(values[i]);
                        } else if (op == Operator.GREATER_THAN && keys[i] > targetKey) {
                            results.add(values[i]);
                        }
                    }
                    
                    nextID = currentLeaf.getNextLeafPageID();
                } finally {
                    storageManager.unpinPage(activePageNum);
                }

                currentLeaf = (nextID == -1) ? null : (LeafNode) storageManager.getPage(-1, nextID);
            }
        }
        
        if (op == Operator.LESS_THAN || op == Operator.LESS_THAN_OR_EQUAL) {
            int rootID = storageManager.getMetadata().getRootPageID();
            Page page = storageManager.getPage(-1, rootID); 

            while (page instanceof BTreeNode && ((BTreeNode) page).getPageType() == StorageManager.TYPE_INTERNAL) {
                InternalNode internal = (InternalNode) page;
                int nextCellPageNum = internal.getChildPageIDs()[0];
                
                Page childPage;
                try {
                    childPage = storageManager.getPage(-1, nextCellPageNum);
                } catch (Exception e) {
                    storageManager.unpinPage(page.getPageNumber());
                    throw e;
                }
                storageManager.unpinPage(page.getPageNumber());
                page = childPage;
            }
            
            if (!(page instanceof LeafNode)) {
                storageManager.unpinPage(page.getPageNumber());
                throw new IOException("Structural Index Error: Leftmost traversal failed.");
            }
                
            LeafNode currentLeaf = (LeafNode) page;
            boolean processing = true;
            
            while (processing && currentLeaf != null) {
                int activePageNum = currentLeaf.getPageNumber();
                int nextID;

                try {
                    int[] keys = currentLeaf.getKeys();
                    long[] values = currentLeaf.getValues();
                    
                    for (int i = 0; i < currentLeaf.getKeyCount(); i++) {
                        if (op == Operator.LESS_THAN && keys[i] >= targetKey) {
                            processing = false;
                            break;
                        }
                        if (op == Operator.LESS_THAN_OR_EQUAL && keys[i] > targetKey) {
                            processing = false;
                            break;
                        }
                        
                        if (op == Operator.LESS_THAN_OR_EQUAL && keys[i] <= targetKey) {
                            results.add(values[i]);
                        } else if (op == Operator.LESS_THAN && keys[i] < targetKey) {
                            results.add(values[i]);
                        }
                    }
                    
                    nextID = currentLeaf.getNextLeafPageID();
                } finally {
                    storageManager.unpinPage(activePageNum);
                }

                currentLeaf = (!processing || nextID == -1) ? null : (LeafNode) storageManager.getPage(-1, nextID);
            }
        }
        
        return results;
    }

    public synchronized List<Entry> scanAfter(long afterKey, int limit) throws IOException {
        List<Entry> results = new ArrayList<>();
        if (limit <= 0 || afterKey >= Integer.MAX_VALUE
                || storageManager.getMetadata().getRootPageID() == -1) {
            return results;
        }

        int startKey = afterKey < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) afterKey;
        LeafNode currentLeaf = findLeafNode(startKey);
        while (currentLeaf != null && results.size() < limit) {
            int activePageNum = currentLeaf.getPageNumber();
            int nextID;
            try {
                int[] keys = currentLeaf.getKeys();
                long[] values = currentLeaf.getValues();
                for (int i = 0; i < currentLeaf.getKeyCount() && results.size() < limit; i++) {
                    if (keys[i] > afterKey) {
                        results.add(new Entry(keys[i], values[i]));
                    }
                }
                nextID = currentLeaf.getNextLeafPageID();
            } finally {
                storageManager.unpinPage(activePageNum);
            }

            currentLeaf = results.size() >= limit || nextID == -1
                    ? null
                    : (LeafNode) storageManager.getPage(-1, nextID);
        }
        return results;
    }

    private void splitLeaf(LeafNode leaf, int newKey, long newValue) throws IOException {
        LeafNode sibling = (LeafNode) storageManager.allocateNewPage(StorageManager.TYPE_LEAF);
        try {
            int oldCount = leaf.getKeyCount();
            int total = oldCount + 1;

            int[] keys = new int[total];
            long[] values = new long[total];

            System.arraycopy(leaf.getKeys(), 0, keys, 0, oldCount);
            System.arraycopy(leaf.getValues(), 0, values, 0, oldCount);

            int index = oldCount - 1;
            while (index >= 0 && keys[index] > newKey) {
                keys[index + 1] = keys[index];
                values[index + 1] = values[index];
                index--;
            }

            keys[index + 1] = newKey;
            values[index + 1] = newValue;

            int middle = total / 2;
            leaf.setKeyCount((short) middle);

            for (int i = 0; i < middle; i++) {
                leaf.getKeys()[i] = keys[i];
                leaf.getValues()[i] = values[i];
            }
            for (int i = middle; i < LeafNode.MAX_KEYS; i++) {
                leaf.getKeys()[i] = 0;
                leaf.getValues()[i] = 0L;
            }

            int siblingCount = total - middle;
            sibling.setKeyCount((short) siblingCount);

            for (int i = 0; i < siblingCount; i++) {
                sibling.getKeys()[i] = keys[middle + i];
                sibling.getValues()[i] = values[middle + i];
            }
            for (int i = siblingCount; i < LeafNode.MAX_KEYS; i++) {
                sibling.getKeys()[i] = 0;
                sibling.getValues()[i] = 0L;
            }

            int oldNext = leaf.getNextLeafPageID();
            sibling.setNextLeafPageID(oldNext);
            leaf.setNextLeafPageID(sibling.getPageID());
            sibling.setParentPageID(leaf.getParentPageID());

            leaf.setDirty(true);
            sibling.setDirty(true);

            insertIntoParent(leaf, sibling.getKeys()[0], sibling);
        } finally {
            storageManager.unpinPage(sibling.getPageNumber());
        }
    }

    private void splitInternal(InternalNode node, int newKey, int rightChildPage) throws IOException {
        InternalNode sibling = (InternalNode) storageManager.allocateNewPage(StorageManager.TYPE_INTERNAL);
        try {
            int oldCount = node.getKeyCount();
            int total = oldCount + 1;

            int[] keys = new int[total];
            int[] children = new int[total + 1];

            System.arraycopy(node.getKeys(), 0, keys, 0, oldCount);
            System.arraycopy(node.getChildPageNumbers(), 0, children, 0, oldCount + 1);

            int index = oldCount - 1;
            while (index >= 0 && keys[index] > newKey) {
                keys[index + 1] = keys[index];
                children[index + 2] = children[index + 1];
                index--;
            }

            keys[index + 1] = newKey;
            children[index + 2] = rightChildPage;

            int middle = total / 2;
            int pushUp = keys[middle];

            node.setKeyCount((short) middle);
            node.getChildPageNumbers()[0] = children[0];

            for (int i = 0; i < middle; i++) {
                node.getKeys()[i] = keys[i];
                node.getChildPageNumbers()[i + 1] = children[i + 1];
            }
            for (int i = middle; i < InternalNode.MAX_KEYS; i++) {
                node.getKeys()[i] = 0;
                node.getChildPageNumbers()[i + 1] = -1;
            }

            int siblingCount = total - middle - 1;
            sibling.setKeyCount((short) siblingCount);
            sibling.getChildPageNumbers()[0] = children[middle + 1];

            for (int i = 0; i < siblingCount; i++) {
                sibling.getKeys()[i] = keys[middle + 1 + i];
                sibling.getChildPageNumbers()[i + 1] = children[middle + 2 + i];
            }
            for (int i = siblingCount; i < InternalNode.MAX_KEYS; i++) {
                sibling.getKeys()[i] = 0;
                sibling.getChildPageNumbers()[i + 1] = -1;
            }

            sibling.setParentPageID(node.getParentPageID());
            node.setDirty(true);
            sibling.setDirty(true);

            for (int i = 0; i <= sibling.getKeyCount(); i++) {
                updateChildParent(sibling.getChildPageNumbers()[i], sibling.getPageID());
            }

            insertIntoParent(node, pushUp, sibling);
        } finally {
            storageManager.unpinPage(sibling.getPageNumber());
        }
    }

    private void updateChildParent(int childPageNumber, int parentPageID) throws IOException {
        if (childPageNumber == -1) {
            return;
        }

        Page child = storageManager.getPage(-1, childPageNumber);
        try {
            if (child instanceof BTreeNode) {
                ((BTreeNode) child).setParentPageID(parentPageID);
                child.setDirty(true);
            }
        } finally {
            storageManager.unpinPage(child.getPageNumber());
        }
    }

    private void balanceLeaf(LeafNode target) throws IOException {
        InternalNode parent = (InternalNode) storageManager.getPage(-1, target.getParentPageID());
        try {
            int[] children = parent.getChildPageNumbers();
            int targetIndex = childIndex(children, parent.getKeyCount(), target.getPageID());
            if (targetIndex == -1) {
                throw new IOException("Index Corruption: Leaf is disconnected from parent.");
            }

            if (targetIndex > 0) {
                LeafNode left = (LeafNode) storageManager.getPage(-1, children[targetIndex - 1]);
                try {
                    if (left.getKeyCount() > LeafNode.MAX_KEYS / 2) {
                        for (int i = target.getKeyCount(); i > 0; i--) {
                            target.getKeys()[i] = target.getKeys()[i - 1];
                            target.getValues()[i] = target.getValues()[i - 1];
                        }

                        int last = left.getKeyCount() - 1;
                        target.getKeys()[0] = left.getKeys()[last];
                        target.getValues()[0] = left.getValues()[last];

                        target.setKeyCount((short) (target.getKeyCount() + 1));
                        left.setKeyCount((short) last);

                        parent.getKeys()[targetIndex - 1] = target.getKeys()[0];
                        target.setDirty(true);
                        left.setDirty(true);
                        parent.setDirty(true);
                        return;
                    }
                } finally { 
                    storageManager.unpinPage(left.getPageNumber()); 
                }
            }

            if (targetIndex < parent.getKeyCount()) {
                LeafNode right = (LeafNode) storageManager.getPage(-1, children[targetIndex + 1]);
                try {
                    if (right.getKeyCount() > LeafNode.MAX_KEYS / 2) {
                        int count = target.getKeyCount();
                        target.getKeys()[count] = right.getKeys()[0];
                        target.getValues()[count] = right.getValues()[0];
                        target.setKeyCount((short) (count + 1));

                        for (int i = 0; i < right.getKeyCount() - 1; i++) {
                            right.getKeys()[i] = right.getKeys()[i + 1];
                            right.getValues()[i] = right.getValues()[i + 1];
                        }

                        right.setKeyCount((short) (right.getKeyCount() - 1));
                        parent.getKeys()[targetIndex] = right.getKeys()[0];

                        target.setDirty(true);
                        right.setDirty(true);
                        parent.setDirty(true);
                        return;
                    }
                } finally { 
                    storageManager.unpinPage(right.getPageNumber()); 
                }
            }

            if (targetIndex > 0) {
                LeafNode left = (LeafNode) storageManager.getPage(-1, children[targetIndex - 1]);
                try {
                    appendLeaf(left, target);
                    left.setNextLeafPageID(target.getNextLeafPageID());
                    left.setDirty(true);
                } finally { 
                    storageManager.unpinPage(left.getPageNumber()); 
                }

                storageManager.unpinPage(parent.getPageNumber());
                parent = null;
                removeInternalKey((InternalNode) storageManager.getPage(-1, target.getParentPageID()), targetIndex - 1);
            } else {
                LeafNode right = (LeafNode) storageManager.getPage(-1, children[targetIndex + 1]);
                try {
                    appendLeaf(target, right);
                    target.setNextLeafPageID(right.getNextLeafPageID());
                    target.setDirty(true);
                } finally { 
                    storageManager.unpinPage(right.getPageNumber()); 
                }

                storageManager.unpinPage(parent.getPageNumber());
                parent = null;
                removeInternalKey((InternalNode) storageManager.getPage(-1, target.getParentPageID()), targetIndex);
            }
        } finally {
            if (parent != null) {
                storageManager.unpinPage(parent.getPageNumber());
            }
        }
    }

    private void appendLeaf(LeafNode destination, LeafNode source) {
        for (int i = 0; i < source.getKeyCount(); i++) {
            int index = destination.getKeyCount();
            destination.getKeys()[index] = source.getKeys()[i];
            destination.getValues()[index] = source.getValues()[i];
            destination.setKeyCount((short) (index + 1));
        }
    }

    private int childIndex(int[] children, int keyCount, int pageID) {
        for (int i = 0; i <= keyCount; i++) {
            if (children[i] == pageID) {
                return i;
            }
        }
        return -1;
    }

    private void removeInternalKey(InternalNode node, int keyIndex) throws IOException {
        int count = node.getKeyCount();
        for (int i = keyIndex; i < count - 1; i++) {
            node.getKeys()[i] = node.getKeys()[i + 1];
        }

        for (int i = keyIndex + 1; i < count; i++) {
            node.getChildPageNumbers()[i] = node.getChildPageNumbers()[i + 1];
        }

        node.setKeyCount((short) (count - 1));
        node.setDirty(true);

        if (node.getParentPageID() == -1) {
            if (node.getKeyCount() == 0) {
                int newRoot = node.getChildPageNumbers()[0];
                storageManager.setRootPageID(newRoot);
                updateChildParent(newRoot, -1);
            }
            storageManager.unpinPage(node.getPageNumber());
        } else if (node.getKeyCount() < InternalNode.MAX_KEYS / 2) {
            balanceInternal(node);
        } else {
            storageManager.unpinPage(node.getPageNumber());
        }
    }

    private void balanceInternal(InternalNode target) throws IOException {
        InternalNode parent = (InternalNode) storageManager.getPage(-1, target.getParentPageID());
        boolean targetPinned = true;

        try {
            int targetIndex = childIndex(parent.getChildPageNumbers(), parent.getKeyCount(), target.getPageID());
            if (targetIndex == -1) {
                throw new IOException("Internal node is disconnected from parent.");
            }

            if (targetIndex > 0) {
                InternalNode left = (InternalNode) storageManager.getPage(-1, parent.getChildPageNumbers()[targetIndex - 1]);
                try {
                    if (left.getKeyCount() > InternalNode.MAX_KEYS / 2) {
                        borrowInternalFromLeft(target, left, parent, targetIndex);
                        return;
                    }
                } finally { 
                    storageManager.unpinPage(left.getPageNumber()); 
                }
            }

            if (targetIndex < parent.getKeyCount()) {
                InternalNode right = (InternalNode) storageManager.getPage(-1, parent.getChildPageNumbers()[targetIndex + 1]);
                try {
                    if (right.getKeyCount() > InternalNode.MAX_KEYS / 2) {
                        borrowInternalFromRight(target, right, parent, targetIndex);
                        return;
                    }
                } finally { 
                    storageManager.unpinPage(right.getPageNumber()); 
                }
            }

            if (targetIndex > 0) {
                InternalNode left = (InternalNode) storageManager.getPage(-1, parent.getChildPageNumbers()[targetIndex - 1]);
                try { 
                    mergeInternal(left, target, parent.getKeys()[targetIndex - 1]); 
                } finally { 
                    storageManager.unpinPage(left.getPageNumber()); 
                }

                int parentID = target.getParentPageID();
                storageManager.unpinPage(target.getPageNumber());
                targetPinned = false;

                storageManager.unpinPage(parent.getPageNumber()); 
                parent = null;

                removeInternalKey((InternalNode) storageManager.getPage(-1, parentID), targetIndex - 1);
            } else {
                InternalNode right = (InternalNode) storageManager.getPage(-1, parent.getChildPageNumbers()[targetIndex + 1]);
                try { 
                    mergeInternal(target, right, parent.getKeys()[targetIndex]); 
                } finally { 
                    storageManager.unpinPage(right.getPageNumber()); 
                }

                int parentID = target.getParentPageID();
                storageManager.unpinPage(target.getPageNumber());
                targetPinned = false;

                storageManager.unpinPage(parent.getPageNumber()); 
                parent = null;

                removeInternalKey((InternalNode) storageManager.getPage(-1, parentID), targetIndex);
            }
        } finally {
            if (parent != null) {
                storageManager.unpinPage(parent.getPageNumber());
            }
            if (targetPinned) {
                storageManager.unpinPage(target.getPageNumber());
            }
        }
    }

    private void borrowInternalFromLeft(InternalNode target, InternalNode left, InternalNode parent, int index) throws IOException {
        for (int i = target.getKeyCount(); i > 0; i--) {
            target.getKeys()[i] = target.getKeys()[i - 1];
        }
        for (int i = target.getKeyCount() + 1; i > 0; i--) {
            target.getChildPageNumbers()[i] = target.getChildPageNumbers()[i - 1];
        }

        target.getKeys()[0] = parent.getKeys()[index - 1];
        int child = left.getChildPageNumbers()[left.getKeyCount()];
        target.getChildPageNumbers()[0] = child;

        updateChildParent(child, target.getPageID());

        parent.getKeys()[index - 1] = left.getKeys()[left.getKeyCount() - 1];
        target.setKeyCount((short) (target.getKeyCount() + 1));
        left.setKeyCount((short) (left.getKeyCount() - 1));

        target.setDirty(true); 
        left.setDirty(true); 
        parent.setDirty(true);
    }

    private void borrowInternalFromRight(InternalNode target, InternalNode right, InternalNode parent, int index) throws IOException {
        int count = target.getKeyCount();
        target.getKeys()[count] = parent.getKeys()[index];

        int child = right.getChildPageNumbers()[0];
        target.getChildPageNumbers()[count + 1] = child;

        updateChildParent(child, target.getPageID());
        parent.getKeys()[index] = right.getKeys()[0];

        for (int i = 0; i < right.getKeyCount() - 1; i++) {
            right.getKeys()[i] = right.getKeys()[i + 1];
        }
        for (int i = 0; i < right.getKeyCount(); i++) {
            right.getChildPageNumbers()[i] = right.getChildPageNumbers()[i + 1];
        }

        target.setKeyCount((short) (count + 1)); 
        right.setKeyCount((short) (right.getKeyCount() - 1));

        target.setDirty(true); 
        right.setDirty(true); 
        parent.setDirty(true);
    }

    private void mergeInternal(InternalNode destination, InternalNode source, int separator) throws IOException {
        int count = destination.getKeyCount();
        destination.getKeys()[count++] = separator;

        for (int i = 0; i < source.getKeyCount(); i++) {
            destination.getKeys()[count] = source.getKeys()[i];
            int child = source.getChildPageNumbers()[i];
            destination.getChildPageNumbers()[count] = child;

            updateChildParent(child, destination.getPageID());
            count++;
        }

        int finalChild = source.getChildPageNumbers()[source.getKeyCount()];
        destination.getChildPageNumbers()[count] = finalChild;

        updateChildParent(finalChild, destination.getPageID());
        destination.setKeyCount((short) count);
        destination.setDirty(true);
    }
}