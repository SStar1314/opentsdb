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

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.util.Bytes;

import net.opentsdb.HBaseException;
import net.opentsdb.uid.NoSuchUniqueId;
import net.opentsdb.uid.NoSuchUniqueName;

/**
 * Non-synchronized implementation of {@link Query}.
 */
final class TsdbQuery implements Query {

  private static final Logger LOG = LoggerFactory.getLogger(TsdbQuery.class);

  /** Used whenever there are no results. */
  private static final DataPoints[] NO_RESULT = new DataPoints[0];

  /**
   * Charset to use with our {@link RegexStringComparator}.
   * We use this one because it preserves every possible byte unchanged.
   */
  private static final Charset CHARSET = Charset.forName("ISO-8859-1");

  /** The TSDB we belong to. */
  private final TSDB tsdb;

  /** Start time (UNIX timestamp in seconds) on 32 bits ("unsigned" int). */
  private int start_time;

  /** End time (UNIX timestamp in seconds) on 32 bits ("unsigned" int). */
  private int end_time;

  /** ID of the metric being looked up. */
  private byte[] metric;

  /**
   * Tags of the metrics being looked up.
   * Each tag is a byte array holding the ID of both the name and value
   * of the tag.
   * Invariant: an element cannot be both in this array and in group_bys.
   */
  private ArrayList<byte[]> tags;

  /**
   * Tags by which we must group the results.
   * Each element is a tag ID.
   * Invariant: an element cannot be both in this array and in {@code tags}.
   */
  private ArrayList<byte[]> group_bys;

  /**
   * Values we may be grouping on.
   * For certain elements in {@code group_bys}, we may have a specific list of
   * values IDs we're looking for.  Those IDs are stored in this map.  The key
   * is an element of {@code group_bys} (so a tag name ID) and the values are
   * tag value IDs (at least two).
   */
  private TreeMap<byte[], byte[][]> group_by_values;

  /** If true, use rate of change instead of actual values. */
  private boolean rate;

  /** Aggregator function to use. */
  private Aggregator aggregator;

  /** Constructor. */
  public TsdbQuery(final TSDB tsdb) {
    this.tsdb = tsdb;
  }

  public void setStartTime(final long timestamp) {
    if ((timestamp & 0xFFFFFFFF00000000L) != 0) {
      throw new IllegalArgumentException("Invalid timestamp: " + timestamp);
    } else if (end_time != 0 && timestamp >= getEndTime()) {
      throw new IllegalArgumentException("new start time (" + timestamp
          + ") is greater than or equal to end time: " + getEndTime());
    }
    // Keep the 32 bits.
    start_time = (int) timestamp;
  }

  public long getStartTime() {
    if (start_time == 0) {
      throw new IllegalStateException("setStartTime was never called!");
    }
    return start_time & 0x00000000FFFFFFFFL;
  }

  public void setEndTime(final long timestamp) {
    if ((timestamp & 0xFFFFFFFF00000000L) != 0) {
      throw new IllegalArgumentException("Invalid timestamp: " + timestamp);
    } else if (start_time != 0 && timestamp <= getStartTime()) {
      throw new IllegalArgumentException("new end time (" + timestamp
          + ") is less than or equal to start time: " + getStartTime());
    }
    // Keep the 32 bits.
    end_time = (int) timestamp;
  }

  public long getEndTime() {
    if (end_time == 0) {
      setEndTime(System.currentTimeMillis() / 1000);
    }
    return end_time;
  }

  public void setTimeSeries(final String metric,
                            final Map<String, String> tags,
                            final Aggregator function,
                            final boolean rate) throws NoSuchUniqueName {
    findGroupBys(tags);
    this.metric = tsdb.metrics.getId(metric);
    this.tags = Tags.resolveAll(tsdb, tags);
    aggregator = function;
    this.rate = rate;
  }

  /**
   * Extracts all the tags we must use to group results.
   * <ul>
   * <li>If a tag has the form {@code name=*} then we'll create one
   *     group per value we find for that tag.</li>
   * <li>If a tag has the form {@code name={v1,v2,..,vN}} then we'll
   *     create {@code N} groups.</li>
   * </ul>
   * In the both cases above, {@code name} will be stored in the
   * {@code group_bys} attribute.  In the second case specifically,
   * the {@code N} values would be stored in {@code group_by_values},
   * the key in this map being {@code name}.
   * @param tags The tags from which to extract the 'GROUP BY's.
   * Each tag that represents a 'GROUP BY' will be removed from the map
   * passed in argument.
   */
  private void findGroupBys(final Map<String, String> tags) {
    final Iterator<Map.Entry<String, String>> i = tags.entrySet().iterator();
    while (i.hasNext()) {
      final Map.Entry<String, String> tag = i.next();
      final String tagvalue = tag.getValue();
      if (tagvalue.equals("*")  // 'GROUP BY' with any value.
          || tagvalue.indexOf('|', 1) >= 0) {  // Multiple possible values.
        if (group_bys == null) {
          group_bys = new ArrayList<byte[]>();
        }
        group_bys.add(tsdb.tag_names.getId(tag.getKey()));
        i.remove();
        if (tagvalue.charAt(0) == '*') {
          continue;  // For a 'GROUP BY' with any value, we're done.
        }
        // 'GROUP BY' with specific values.  Need to split the values
        // to group on and store their IDs in group_by_values.
        final String[] values = Tags.splitString(tagvalue, '|');
        if (group_by_values == null) {
          group_by_values =
            new TreeMap<byte[], byte[][]>(Bytes.BYTES_COMPARATOR);
        }
        final short value_width = tsdb.tag_values.width();
        final byte[][] value_ids = new byte[values.length][value_width];
        group_by_values.put(tsdb.tag_names.getId(tag.getKey()),
                            value_ids);
        for (int j = 0; j < values.length; j++) {
          final byte[] value_id = tsdb.tag_values.getId(values[j]);
          System.arraycopy(value_id, 0, value_ids[j], 0, value_width);
        }
      }
    }
  }

  public DataPoints[] run() throws HBaseException {
    return groupByAndAggregate(findSpans());
  }

  /**
   * Finds all the {@link Span}s that match this query.
   * This is what actually scans the HBase table and loads the data into
   * {@link Span}s.
   * @return A map from HBase row key to the {@link Span} for that row key.
   * Since a {@link Span} actually contains multiple HBase rows, the row key
   * stored in the map has its timestamp zero'ed out.
   */
  private TreeMap<byte[], Span> findSpans() throws HBaseException {
    final short metric_width = tsdb.metrics.width();
    final TreeMap<byte[], Span> spans =  // The key is a row key from HBase.
      new TreeMap<byte[], Span>(new SpanCmp(metric_width));
    int nrows = 0;
    final ResultScanner scanner = getScanner();
    try {
      Result result;
      while ((result = scanner.next()) != null) {
        final byte[] row = result.getRow();
        if (Bytes.compareTo(metric, 0, metric_width,
                            row, 0, metric_width) != 0) {
          throw new AssertionError("HBase returned a row that doesn't match"
              + " our scanner! row=" + Arrays.toString(row) + " does not start"
              + " with " + Arrays.toString(metric) + ", from result="
              + result + " when scanning with " + scanner);
        }
        Span datapoints = spans.get(row);
        if (datapoints == null) {
          datapoints = new Span(tsdb);
          spans.put(row, datapoints);
        }
        datapoints.addRow(result);
        nrows++;
      }
    } catch (IOException e) {
      throw new HBaseException("Error while scanning HBase, scanner="
                               + scanner, e);
    } finally {
      scanner.close();
    }
    LOG.info(this + " matched " + nrows + " rows in " + spans.size() + " spans");
    if (nrows == 0) {
      return null;
    }
    return spans;
  }

  /**
   * Creates the {@link SpanGroup}s to form the final results of this query.
   * @param spans The {@link Span}s found for this query ({@link #findSpans}).
   * Can be {@code null}, in which case the array returned will be empty.
   * @return A possibly empty array of {@link SpanGroup}s built according to
   * any 'GROUP BY' formulated in this query.
   */
  private DataPoints[] groupByAndAggregate(final TreeMap<byte[], Span> spans) {
    if (spans == null || spans.size() <= 0) {
      return NO_RESULT;
    }
    if (group_bys == null) {
      final SpanGroup group = new SpanGroup(tsdb,
                                            getStartTime(), getEndTime(),
                                            spans.values(),
                                            rate,
                                            aggregator);
      return new SpanGroup[] { group };
    }
    final TreeMap<byte[], SpanGroup> groups =
      new TreeMap<byte[], SpanGroup>(Bytes.BYTES_COMPARATOR);
    final short metric_ts_bytes = (short) (tsdb.metrics.width()
                                           + Const.TIMESTAMP_BYTES);
    final short name_width = tsdb.tag_names.width();
    final short value_width = tsdb.tag_values.width();
    final short tag_bytes = (short) (name_width + value_width);
    final byte[] group = new byte[group_bys.size() * value_width];
    for (final Map.Entry<byte[], Span> entry : spans.entrySet()) {
      final byte[] row = entry.getKey();
      byte[] value_id = null;
      int i = 0;
      // TODO(tsuna): The following loop has a quadratic behavior.  We can
      // make it much better since both the row key and group_bys are sorted.
      for (final byte[] tag_id : group_bys) {
        value_id = Tags.getValueId(tsdb, row, tag_id);
        if (value_id == null) {
          break;
        }
        System.arraycopy(value_id, 0, group, i, value_width);
        i += value_width;
      }
      if (value_id == null) {
        LOG.info("Dropping span: " + Arrays.toString(row));
        continue;
      }
      //LOG.info("Span belongs to group " + Arrays.toString(group) + ": " + Arrays.toString(row));
      SpanGroup thegroup = groups.get(group);
      if (thegroup == null) {
        thegroup = new SpanGroup(tsdb, getStartTime(), getEndTime(),
                                 null, rate, aggregator);
        final byte[] group_copy = new byte[group.length];
        System.arraycopy(group, 0, group_copy, 0, group.length);
        groups.put(group_copy, thegroup);
      }
      thegroup.add(entry.getValue());
    }
    //for (final Map.Entry<byte[], SpanGroup> entry : groups.entrySet()) {
    //  LOG.info("group for " + Arrays.toString(entry.getKey()) + ": " + entry.getValue());
    //}
    return groups.values().toArray(new SpanGroup[groups.size()]);
  }

  /**
   * Creates the {@link ResultScanner} to use for this query.
   */
  private ResultScanner getScanner() throws HBaseException {
    final short metric_width = tsdb.metrics.width();
    final byte[] start_row = new byte[metric_width + Const.TIMESTAMP_BYTES];
    final byte[] end_row = new byte[metric_width + Const.TIMESTAMP_BYTES];
    // We search MAX_TIMESPAN seconds before and after the end time as it's
    // quite likely that the exact timestamp we're looking for is in the
    // middle of a row.
    Bytes.putInt(start_row, metric_width,
                 start_time - Const.MAX_TIMESPAN);
    Bytes.putInt(end_row, metric_width,
                 end_time == 0
                 ? -1  // Will scan until the end (0xFFF...).
                 : end_time + Const.MAX_TIMESPAN);
    System.arraycopy(metric, 0, start_row, 0, metric_width);
    System.arraycopy(metric, 0, end_row, 0, metric_width);

    final Scan scan = new Scan(start_row, end_row);
    if (tags.size() > 0 || group_bys != null) {
      createAndSetFilter(scan);
    }
    scan.addFamily(TSDB.FAMILY);
    try {
      return tsdb.timeseries_table.getScanner(scan);
    } catch (IOException e) {
      throw new HBaseException("Failed to obtain a scanner from "
          + Arrays.toString(start_row) + " to " + Arrays.toString(end_row), e);
    }
  }

  /**
   * Creates the server-side filter and sets it on the scanner.
   * In order to find the rows with the relevant tags, we use a
   * {@link RegexStringComparator} on a {@link RowFilter} so the
   * filter is done on the server-side by the region servers.
   * @param scan The scanner on which to add the filter.
   */
  void createAndSetFilter(final Scan scan) {
    if (group_bys != null) {
      Collections.sort(group_bys, Bytes.BYTES_COMPARATOR);
    }
    final short name_width = tsdb.tag_names.width();
    final short value_width = tsdb.tag_values.width();
    final short tagsize = (short) (name_width + value_width);
    // Generate a regexp for our tags.  Say we have 2 tags: { 0 0 1 0 0 2 }
    // and { 4 5 6 9 8 7 }, the regexp will be:
    // "^.{7}(?:.{6})*\\Q\000\000\001\000\000\002\\E(?:.{6})*\\Q\004\005\006\011\010\007\\E(?:.{6})*$"
    final StringBuilder buf = new StringBuilder(
        15  // "^.{N}" + "(?:.{M})*" + "$"
        + ((13 + tagsize) // "(?:.{M})*\\Q" + tagsize bytes + "\\E"
           * (tags.size() + (group_bys == null ? 0 : group_bys.size() * 3))));
    // In order to avoid re-allocations, reserve a bit more w/ groups ^^^

    // Alright, let's build this regexp.  From the beginning...
    buf.append("(?s)"  // Ensure we use the DOTALL flag.
               + "^.{")
       // ... start by skipping the metric ID and timestamp.
       .append(tsdb.metrics.width() + Const.TIMESTAMP_BYTES)
       .append("}");
    final Iterator<byte[]> tags = this.tags.iterator();
    final Iterator<byte[]> group_bys = (this.group_bys == null
                                        ? new ArrayList<byte[]>(0).iterator()
                                        : this.group_bys.iterator());
    byte[] tag = tags.hasNext() ? tags.next() : null;
    byte[] group_by = group_bys.hasNext() ? group_bys.next() : null;
    // Tags and group_bys are already sorted.  We need to put them in the
    // regexp in order by ID, which means we just merge two sorted lists.
    do {
      // Skip any number of tags.
      buf.append("(?:.{").append(tagsize).append("})*\\Q");
      if (isTagNext(name_width, tag, group_by)) {
        addId(buf, tag);
        tag = tags.hasNext() ? tags.next() : null;
      } else {  // Add a group_by.
        addId(buf, group_by);
        final byte[][] value_ids = (group_by_values == null
                                    ? null
                                    : group_by_values.get(group_by));
        if (value_ids == null) {  // We don't want any specific ID...
          buf.append(".{").append(value_width).append('}');  // Any value ID.
        } else {  // We want specific IDs.  List them: /(AAA|BBB|CCC|..)/
          buf.append("(?:");
          for (final byte[] value_id : value_ids) {
            buf.append("\\Q");
            addId(buf, value_id);
            buf.append('|');
          }
          // Replace the pipe of the last iteration.
          buf.setCharAt(buf.length() - 1, ')');
        }
        group_by = group_bys.hasNext() ? group_bys.next() : null;
      }
    } while (tag != group_by);  // Stop when they both become null.
    // Skip any number of tags before the end.
    buf.append("(?:.{").append(tagsize).append("})*$");

    final RegexStringComparator r = new RegexStringComparator(buf.toString());
    // The setCharset method comes from HBASE-2323.
    r.setCharset(CHARSET);
    final RowFilter filter = new RowFilter(RowFilter.CompareOp.EQUAL, r);
    scan.setFilter(filter);
   }

  /**
   * Helper comparison function to compare tag name IDs.
   * @param name_width Number of bytes used by a tag name ID.
   * @param tag A tag (array containing a tag name ID and a tag value ID).
   * @param group_by A tag name ID.
   * @return {@code true} number if {@code tag} should be used next (because
   * it contains a smaller ID), {@code false} otherwise.
   */
  private boolean isTagNext(final short name_width,
                            final byte[] tag,
                            final byte[] group_by) {
    if (tag == null) {
      return false;
    } else if (group_by == null) {
      return true;
    }
    final int cmp = Bytes.compareTo(tag, 0, name_width,
                                    group_by, 0, name_width);
    if (cmp == 0) {
      throw new AssertionError("invariant violation: tag ID "
          + Arrays.toString(group_by) + " is both in 'tags' and"
          + " 'group_bys' in " + this);
    }
    return cmp < 0;
  }

  /**
   * Appends the given ID to the given buffer, followed by "\\E".
   */
  private static void addId(final StringBuilder buf, final byte[] id) {
    for (final byte b : id) {
      buf.append((char) (b & 0xFF));
      if (b == '\\') {  // Escape the escape characters that are in the ID.
        buf.append('\\');
      }
    }
    buf.append("\\E");
  }

  public String toString() {
    final StringBuilder buf = new StringBuilder();
    buf.append("TsdbQuery(start_time=")
       .append(getStartTime())
       .append(", end_time=")
       .append(getEndTime())
       .append(", metric=").append(Arrays.toString(metric));
    try {
      buf.append(" (").append(tsdb.metrics.getName(metric));
    } catch (NoSuchUniqueId e) {
      buf.append(" (<").append(e.getMessage()).append('>');
    }
    try {
      buf.append("), tags=").append(Tags.resolveIds(tsdb, tags));
    } catch (NoSuchUniqueId e) {
      buf.append("), tags=<").append(e.getMessage()).append('>');
    }
    buf.append(", rate=").append(rate)
       .append(", aggregator=").append(aggregator)
       .append(", group_bys=(");
    if (group_bys != null) {
      for (final byte[] tag_id : group_bys) {
        try {
          buf.append(tsdb.tag_names.getName(tag_id));
        } catch (NoSuchUniqueId e) {
          buf.append('<').append(e.getMessage()).append('>');
        }
        buf.append(' ')
           .append(Arrays.toString(tag_id));
        if (group_by_values != null) {
          final byte[][] value_ids = group_by_values.get(tag_id);
          if (value_ids == null) {
            continue;
          }
          buf.append("={");
          for (final byte[] value_id : value_ids) {
            try {
              buf.append(tsdb.tag_values.getName(value_id));
            } catch (NoSuchUniqueId e) {
              buf.append('<').append(e.getMessage()).append('>');
            }
            buf.append(' ')
               .append(Arrays.toString(value_id))
               .append(", ");
          }
          buf.append('}');
        }
        buf.append(", ");
      }
    }
    buf.append("))");
    return buf.toString();
  }

  /**
   * Comparator that ignores timestamps in row keys.
   */
  private static final class SpanCmp implements Comparator<byte[]> {

    private final short metric_width;

    public SpanCmp(final short metric_width) {
      this.metric_width = metric_width;
    }

    public int compare(final byte[] a, final byte[] b) {
      final int length = Math.min(a.length, b.length);
      if (a == b) {  // Do this after accessing a.length and b.length
        return 0;    // in order to NPE if either a or b is null.
      }
      int i;
      // First compare the metric ID.
      for (i = 0; i < metric_width; i++) {
        if (a[i] != b[i]) {
          return (a[i] & 0xFF) - (b[i] & 0xFF);  // "promote" to unsigned.
        }
      }
      // Then skip the timestamp and compare the rest.
      for (i += Const.TIMESTAMP_BYTES; i < length; i++) {
        if (a[i] != b[i]) {
          return (a[i] & 0xFF) - (b[i] & 0xFF);  // "promote" to unsigned.
        }
      }
      return a.length - b.length;
    }

  }

}
