package Yggdrasil.core;

import java.nio.ByteBuffer;

/** Common state and serialization for internal and leaf tree nodes. */
public abstract class BTreeNode extends Page {

    private final byte pageType;
    private short keyCount;
    private int parentPageID;

    protected BTreeNode(int pageID, int pageNumber, byte pageType) {
        super(pageID, pageNumber);
        this.pageType = pageType;
        this.parentPageID = -1;
    }

    protected void serializeCommonHeader(ByteBuffer buffer) {
        buffer.put(OFFSET_PAGE_TYPE, pageType);
        buffer.putShort(OFFSET_KEY_COUNT, keyCount);
        buffer.putInt(OFFSET_PARENT_ID, parentPageID);
    }

    public byte getPageType() {
        return pageType;
    }

    public short getKeyCount() {
        return keyCount;
    }

    public void setKeyCount(short keyCount) {
        this.keyCount = keyCount;
    }

    public int getParentPageID() {
        return parentPageID;
    }

    public void setParentPageID(int parentPageID) {
        this.parentPageID = parentPageID;
    }
}
