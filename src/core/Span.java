// This file is part of OpenTSDB.
// Copyright (C) 2010  StumbleUpon, Inc.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Represents a read-only sequence of continuous data points.
 * <p>
 * This class stores a continuous sequence of {@link RowSeq}s in memory.
 */
final class Span implements DataPoints {

  private static final Logger LOG = LoggerFactory.getLogger(Span.class);

  /** The {@link TSDB} instance we belong to. */
  private final TSDB tsdb;

  /** All the rows in this span. */
  private ArrayList<RowSeq> rows = new ArrayList<RowSeq>();

  Span(final TSDB tsdb) {
    this.tsdb = tsdb;
  }

  private void checkNotEmpty() {
    if (rows.size() == 0) {
      throw new IllegalStateException("empty Span");
    }
  }

  public String metricName() {
    checkNotEmpty();
    return rows.get(0).metricName();
  }

  public Map<String, String> getTags() {
    checkNotEmpty();
    return rows.get(0).getTags();
  }

  public List<String> getAggregatedTags() {
    return Collections.emptyList();
  }

  public int size() {
    int size = 0;
    for (final RowSeq row : rows) {
      size += row.size();
    }
    return size;
  }

  public int aggregatedSize() {
    return 0;
  }

  /**
   * Adds an HBase row to this span, using a Result from a scanner.
   * @param result The HBase row to add to this span.
   * @throws IllegalArgumentException if the argument and this span are for
   * two different time series.
   * @throws IllegalArgumentException if the argument represents a row for
   * data points that are older than those already added to this span.
   */
  void addRow(final Result result) {
    long last_ts = 0;
    if (rows.size() != 0) {
      // Verify that we have the same metric id and tags.
      final byte[] row = result.getRow();
      final RowSeq last = rows.get(rows.size() - 1);
      final short metric_width = tsdb.metrics.width();
      final short tags_offset = (short) (metric_width + Const.TIMESTAMP_BYTES);
      final short tags_bytes = (short) (row.length - tags_offset);
      String error = null;
      if (row.length != last.row.length) {
        error = "row length mismatch";
      } else if (Bytes.compareTo(row, 0, metric_width,
                          last.row, 0, metric_width) != 0) {
        error = "metric ID mismatch";
      } else if (Bytes.compareTo(row, tags_offset, tags_bytes,
                                 last.row, tags_offset, tags_bytes) != 0) {
        error = "tags mismatch";
      }
      if (error != null) {
        throw new IllegalArgumentException(error + ". "
            + "This Span's last row is " + Arrays.toString(last.row)
            + " whereas the row being added is " + Arrays.toString(row)
            + " and metric_width=" + metric_width);
      }
      last_ts = last.timestamp(last.size() - 1);
      // Optimization: check whether we can put all the data points of
      // `result' into the last RowSeq object we created, instead of making a
      // new RowSeq.  If the time delta between the timestamp encoded in the
      // row key of the last RowSeq we created and the timestamp of the last
      // data point in `result' is small enough, we can merge `result' into
      // the last RowSeq.
      if (RowSeq.canTimeDeltaFit(lastTimestampInRow(metric_width, result)
                                 - last.baseTime())) {
        last.addRow(result);
        return;
      }
    }

    final RowSeq row = new RowSeq(tsdb);
    row.setRow(result);
    if (last_ts >= row.timestamp(0)) {
      throw new IllegalArgumentException("New RowSeq added out of order to this"
          + " Span! Last RowSeq = " + rows.get(rows.size() - 1)
          + ", new RowSeq = " + row);
    }
    rows.add(row);
  }

  /**
   * Package private helper to access the last timestamp in an HBase row.
   * @param metric_width The number of bytes on which metric IDs are stored.
   * @param row The row coming straight out of HBase.
   * @return A strictly positive 32-bit timestamp.
   * @throws IllegalArgumentException if {@code row} doesn't contain any cell.
   */
  static long lastTimestampInRow(final short metric_width, final Result row) {
    final KeyValue[] kvs = row.raw();
    if (kvs.length < 1) {
      throw new IllegalArgumentException("empty row: " + row);
    }
    final KeyValue lastkv = kvs[kvs.length - 1];
    final long base_time = RowKey.baseTime(metric_width, row.getRow());
    final short last_delta = (short)
      ((Bytes.toShort(lastkv.getQualifier()) & 0xFFFF) >>> Const.FLAG_BITS);
    return base_time + last_delta;
  }

  public SeekableView iterator() {
    return spanIterator();
  }

  /**
   * Finds the index of the row of the ith data point and the offset in the row.
   * @param i The index of the data point to find.
   * @return two ints packed in a long.  The first int is the index of the row
   * in {@code rows} and the second is offset in that {@link RowSeq} instance.
   */
  private long getIdxOffsetFor(final int i) {
    int idx = 0;
    int offset = 0;
    for (final RowSeq row : rows) {
      final int sz = row.size();
      if (offset + sz > i) {
        break;
      }
      offset += sz;
      idx++;
    }
    return ((long) idx << 32) | (i - offset);
  }

  public long timestamp(final int i) {
    final long idxoffset = getIdxOffsetFor(i);
    final int idx = (int) (idxoffset >>> 32);
    final int offset = (int) (idxoffset & 0x00000000FFFFFFFF);
    return rows.get(idx).timestamp(offset);
  }

  public boolean isInteger(final int i) {
    final long idxoffset = getIdxOffsetFor(i);
    final int idx = (int) (idxoffset >>> 32);
    final int offset = (int) (idxoffset & 0x00000000FFFFFFFF);
    return rows.get(idx).isInteger(offset);
  }

  public long longValue(final int i) {
    final long idxoffset = getIdxOffsetFor(i);
    final int idx = (int) (idxoffset >>> 32);
    final int offset = (int) (idxoffset & 0x00000000FFFFFFFF);
    return rows.get(idx).longValue(offset);
  }

  public double doubleValue(final int i) {
    final long idxoffset = getIdxOffsetFor(i);
    final int idx = (int) (idxoffset >>> 32);
    final int offset = (int) (idxoffset & 0x00000000FFFFFFFF);
    return rows.get(idx).doubleValue(offset);
  }

  /** Returns a human readable string representation of the object. */
  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("Span(")
       .append(rows.size())
       .append(" rows, [");
    for (int i = 0; i < rows.size(); i++) {
      if (i != 0) {
        buf.append(", ");
      }
      buf.append(rows.get(i).toString());
    }
    buf.append("])");
    return buf.toString();
  }

  /**
   * Finds the index of the row in which the given timestamp should be.
   * @param timestamp A strictly positive 32-bit integer.
   * @return A strictly positive index in the {@code rows} array.
   */
  private short seekRow(final long timestamp) {
    short row_index = 0;
    RowSeq row = null;
    final int nrows = rows.size();
    for (int i = 0; i < nrows; i++) {
      row = rows.get(i);
      final int sz = row.size();
      if (row.timestamp(sz - 1) < timestamp) {
        row_index++;  // The last DP in this row is before 'timestamp'.
      } else {
        break;
      }
    }
    if (row_index == nrows) {  // If this timestamp was too large for the
      --row_index;             // last row, return the last row.
    }
    return row_index;
  }

  /** Package private iterator method to access it as a Span.Iterator. */
  Span.Iterator spanIterator() {
    return new Span.Iterator();
  }

  /** Iterator for {@link Span}s. */
  final class Iterator implements SeekableView {

    /** Index of the {@link RowSeq} we're currently at, in {@code rows}. */
    private short row_index;

    /** Iterator on the current row. */
    private DataPointsIterator current_row;

    Iterator() {
      current_row = rows.get(0).internalIterator();
    }

    public boolean hasNext() {
      return (current_row.hasNext()             // more points in this row
              || row_index < rows.size() - 1);  // or more rows
    }

    public DataPoint next() {
      if (current_row.hasNext()) {
        return current_row.next();
      } else if (row_index < rows.size() - 1) {
        row_index++;
        current_row = rows.get(row_index).internalIterator();
        return current_row.next();
      }
      throw new NoSuchElementException("no more elements");
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }

    public void seek(final long timestamp) {
      row_index = seekRow(timestamp);
      current_row = rows.get(row_index).internalIterator();
      current_row.seek(timestamp);
    }

    public String toString() {
      return "Span.Iterator(row_index=" + row_index
        + ", current_row=" + current_row + ", span=" + Span.this + ')';
    }

  }

}
