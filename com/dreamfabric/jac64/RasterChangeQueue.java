package com.dreamfabric.jac64;

import java.util.function.IntConsumer;

/**
 * Deferred-change queue for VIC-II register writes that affect sprite
 * rendering. Modeled on VICE's raster_changes mechanism
 * (vice/src/raster/raster-changes.h).
 *
 * <p>VICE stores a pointer to the target variable and the new value.
 * In Java we use an {@link IntConsumer} as a functional pointer:
 * apply.accept(newValue) sets the target field.
 *
 * <p>Entries are kept sorted by raster_x position (via insertion
 * sort — mirrors VICE's raster_changes_add_sorted_int).
 */
public final class RasterChangeQueue {

  private static final int CAPACITY = 256;

  private final int[] where = new int[CAPACITY];
  private final IntConsumer[] apply = new IntConsumer[CAPACITY];
  private final int[] value = new int[CAPACITY];
  private int size;

  public void clear() {
    size = 0;
    // nulls aren't strictly required but help GC
    for (int i = 0; i < apply.length; i++) apply[i] = null;
  }

  public int size() {
    return size;
  }

  public int peekWhere() {
    return size > 0 ? where[0] : Integer.MAX_VALUE;
  }

  /**
   * Add a pending change to apply at raster_x = {@code where}. Inserts
   * in sorted order so drains happen in correct sequence.
   */
  public void addSorted(int where, IntConsumer apply, int value) {
    if (size >= CAPACITY) {
      // Overflow: apply the change immediately and drop (should never
      // happen in practice with CAPACITY=256 — VICE uses 1024).
      apply.accept(value);
      return;
    }
    int i = size - 1;
    while (i >= 0 && this.where[i] > where) {
      this.where[i + 1]  = this.where[i];
      this.apply[i + 1]  = this.apply[i];
      this.value[i + 1]  = this.value[i];
      i--;
    }
    this.where[i + 1] = where;
    this.apply[i + 1] = apply;
    this.value[i + 1] = value;
    size++;
  }

  /**
   * Drain entries whose raster_x position is &lt;= {@code rasterXLimit},
   * invoking each entry's apply consumer in order.
   */
  public void drainUpTo(int rasterXLimit) {
    int kept = 0;
    for (int i = 0; i < size; i++) {
      if (where[i] <= rasterXLimit) {
        apply[i].accept(value[i]);
      } else {
        if (kept != i) {
          where[kept] = where[i];
          apply[kept] = apply[i];
          value[kept] = value[i];
        }
        kept++;
      }
    }
    // clear released slots
    for (int i = kept; i < size; i++) apply[i] = null;
    size = kept;
  }
}
