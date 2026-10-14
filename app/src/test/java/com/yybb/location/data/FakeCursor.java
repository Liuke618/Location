package com.yybb.location.data;

import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;

/**
 * 测试替身：按列名数组 + 行数据数组驱动的 Cursor。
 * Cursor 是接口可在 JVM 直接实现，而 SQLiteDatabase/ContentValues 是 stub 类一调即抛——
 * 这是仓库"仅 JVM 单测"约定下锚定游标映射逻辑的唯一途径。
 */
class FakeCursor implements Cursor {
    private final String[] mColumnNames;
    private final Object[][] mRows;
    private int mPosition = -1;
    private boolean mClosed = false;

    /** 行内元素类型即取值类型：TEXT 列存 String，BIGINT 列存 Long/Integer，与 SQLiteCursor 行为对齐 */
    FakeCursor(String[] columnNames, Object[][] rows) {
        mColumnNames = columnNames;
        mRows = rows;
    }

    /*============================== 遍历 ==============================*/

    @Override
    public int getCount() {
        return mRows.length;
    }

    @Override
    public int getPosition() {
        return mPosition;
    }

    @Override
    public boolean moveToPosition(int position) {
        if (position < -1 || position >= mRows.length) {
            mPosition = position < -1 ? -1 : mRows.length; // 越界收敛到首尾哨兵位，与系统游标一致
            return false;
        }
        mPosition = position;
        return true;
    }

    @Override
    public boolean move(int offset) {
        return moveToPosition(mPosition + offset);
    }

    @Override
    public boolean moveToFirst() {
        return moveToPosition(0);
    }

    @Override
    public boolean moveToLast() {
        return moveToPosition(mRows.length - 1);
    }

    @Override
    public boolean moveToNext() {
        return moveToPosition(mPosition + 1);
    }

    @Override
    public boolean moveToPrevious() {
        return moveToPosition(mPosition - 1);
    }

    @Override
    public boolean isFirst() {
        return mPosition == 0;
    }

    @Override
    public boolean isLast() {
        return mRows.length > 0 && mPosition == mRows.length - 1;
    }

    @Override
    public boolean isBeforeFirst() {
        return mRows.length == 0 || mPosition == -1;
    }

    @Override
    public boolean isAfterLast() {
        return mRows.length == 0 || mPosition == mRows.length;
    }

    /*============================== 取值 ==============================*/

    private Object valueAt(int columnIndex) {
        if (mPosition < 0 || mPosition >= mRows.length) {
            throw new IllegalStateException("cursor not positioned on a row: " + mPosition);
        }
        return mRows[mPosition][columnIndex];
    }

    @Override
    public String getString(int columnIndex) {
        Object value = valueAt(columnIndex);
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(int columnIndex) {
        Object value = valueAt(columnIndex);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong((String) value);
    }

    @Override
    public int getInt(int columnIndex) {
        return (int) longValue(columnIndex);
    }

    @Override
    public long getLong(int columnIndex) {
        return longValue(columnIndex);
    }

    @Override
    public short getShort(int columnIndex) {
        return (short) longValue(columnIndex);
    }

    @Override
    public double getDouble(int columnIndex) {
        return Double.parseDouble(getString(columnIndex));
    }

    @Override
    public float getFloat(int columnIndex) {
        return Float.parseFloat(getString(columnIndex));
    }

    @Override
    public byte[] getBlob(int columnIndex) {
        return (byte[]) valueAt(columnIndex);
    }

    @Override
    public void copyStringToBuffer(int columnIndex, CharArrayBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getType(int columnIndex) {
        Object value = valueAt(columnIndex);
        if (value == null) {
            return FIELD_TYPE_NULL;
        }
        if (value instanceof Float || value instanceof Double) {
            return FIELD_TYPE_FLOAT;
        }
        if (value instanceof Number) {
            return FIELD_TYPE_INTEGER;
        }
        return FIELD_TYPE_STRING;
    }

    @Override
    public boolean isNull(int columnIndex) {
        return valueAt(columnIndex) == null;
    }

    /*============================== 列信息 ==============================*/

    @Override
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < mColumnNames.length; i++) {
            if (mColumnNames[i].equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) {
        int index = getColumnIndex(columnName);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown column: " + columnName);
        }
        return index;
    }

    @Override
    public String getColumnName(int columnIndex) {
        return mColumnNames[columnIndex];
    }

    @Override
    public String[] getColumnNames() {
        return mColumnNames;
    }

    @Override
    public int getColumnCount() {
        return mColumnNames.length;
    }

    /*============================== 生命周期 ==============================*/

    @Override
    public void close() {
        mClosed = true;
    }

    @Override
    public boolean isClosed() {
        return mClosed;
    }

    @SuppressWarnings("deprecation") // Cursor 接口强制要求实现,替身本身不使用该弃用行为
    @Override
    public void deactivate() {
    }

    @SuppressWarnings("deprecation") // Cursor 接口强制要求实现,替身本身不使用该弃用行为
    @Override
    public boolean requery() {
        return true;
    }

    @Override
    public void registerContentObserver(ContentObserver observer) {
    }

    @Override
    public void unregisterContentObserver(ContentObserver observer) {
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
    }

    @Override
    public void setNotificationUri(ContentResolver cr, Uri uri) {
    }

    @Override
    public Uri getNotificationUri() {
        return null;
    }

    // setNotificationUris/getNotificationUris 为接口 default 方法,替身无需覆写

    @Override
    public boolean getWantsAllOnMoveCalls() {
        return false;
    }

    @Override
    public void setExtras(Bundle extras) {
    }

    @Override
    public Bundle getExtras() {
        return null;
    }

    @Override
    public Bundle respond(Bundle extras) {
        return extras;
    }
}
