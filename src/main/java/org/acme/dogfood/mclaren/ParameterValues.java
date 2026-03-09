package org.acme.dogfood.mclaren;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Timestamped sample array — mirrors {@code MESL.SqlRace.Domain.ParameterValues}.
 *
 * <p>In the SQL Race model, {@code ParameterValues} is the return type from
 * {@code IParameterDataAccess.GetNextSamples()} and the write target for incoming ECU data.
 * Each array index corresponds to a single sample:
 *
 * <pre>
 *   timestamps[i] — nanoseconds since session epoch
 *   data[i]       — engineering value after {@link RationalConversion#apply(double)}
 *   dataStatus[i] — quality flag ({@link DataStatusType})
 * </pre>
 *
 * <p>Typical ATLAS usage in C#:
 *
 * <pre>{@code
 * // Check status before using value:
 * if (pdaSamples.DataStatus[i] != DataStatusType.Missing) {
 *     ProcessSample(pdaSamples.Timestamps[i], pdaSamples.Data[i]);
 * }
 * }</pre>
 *
 * <p><b>Note on array equality:</b> this record uses arrays. {@code equals()} and
 * {@code hashCode()} are overridden to use {@link Arrays#equals} for correct value semantics.
 *
 * @param timestamps  nanosecond timestamps (monotonically increasing within a parameter)
 * @param data        engineering values; same length as {@code timestamps}
 * @param dataStatus  per-sample quality flags; same length as {@code timestamps}
 */
public record ParameterValues(long[] timestamps, double[] data, DataStatusType[] dataStatus) {

    /**
     * Compact constructor: validates parallel array lengths.
     *
     * @throws IllegalArgumentException if any two arrays have different lengths
     */
    public ParameterValues {
        if (timestamps.length != data.length || data.length != dataStatus.length) {
            throw new IllegalArgumentException(
                    "Array length mismatch: timestamps=%d data=%d status=%d"
                            .formatted(timestamps.length, data.length, dataStatus.length));
        }
        // defensive copy so the record is truly immutable
        timestamps = timestamps.clone();
        data = data.clone();
        dataStatus = dataStatus.clone();
    }

    /** Empty sample set — returned when no data exists for a requested time range. */
    public static ParameterValues empty() {
        return new ParameterValues(new long[0], new double[0], new DataStatusType[0]);
    }

    /**
     * Single-sample factory with {@link DataStatusType#Good} status.
     *
     * @param timestampNs nanosecond timestamp
     * @param value       engineering value
     * @return single-sample {@code ParameterValues}
     */
    public static ParameterValues single(long timestampNs, double value) {
        return new ParameterValues(
                new long[] {timestampNs},
                new double[] {value},
                new DataStatusType[] {DataStatusType.Good});
    }

    /**
     * Single-sample factory with explicit status.
     *
     * @param timestampNs nanosecond timestamp
     * @param value       engineering value (may be NaN for {@link DataStatusType#Missing})
     * @param status      data quality flag
     * @return single-sample {@code ParameterValues}
     */
    public static ParameterValues single(long timestampNs, double value, DataStatusType status) {
        return new ParameterValues(
                new long[] {timestampNs},
                new double[] {value},
                new DataStatusType[] {status});
    }

    /** Number of samples in this batch. */
    public int count() {
        return timestamps.length;
    }

    /**
     * Return only the samples tagged {@link DataStatusType#Good}.
     *
     * <p>Statistics pipelines use this to exclude predicted, saturated, and missing samples from
     * fastest-lap calculations.
     *
     * @return new {@code ParameterValues} containing only good samples
     */
    public ParameterValues goodSamples() {
        List<Long> ts = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        for (int i = 0; i < timestamps.length; i++) {
            if (dataStatus[i] == DataStatusType.Good) {
                ts.add(timestamps[i]);
                vals.add(data[i]);
            }
        }
        long[] filteredTs = new long[ts.size()];
        double[] filteredVals = new double[ts.size()];
        DataStatusType[] filteredStatus = new DataStatusType[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            filteredTs[i] = ts.get(i);
            filteredVals[i] = vals.get(i);
            filteredStatus[i] = DataStatusType.Good;
        }
        return new ParameterValues(filteredTs, filteredVals, filteredStatus);
    }

    /**
     * Retrieve a single sample by index.
     *
     * @param index 0-based sample index
     * @return an array of {@code [timestampNs, value]} (two elements)
     * @throws ArrayIndexOutOfBoundsException if index is out of range
     */
    public double[] at(int index) {
        return new double[] {timestamps[index], data[index]};
    }

    // Override equals/hashCode for correct value semantics with array components.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParameterValues pv)) return false;
        return Arrays.equals(timestamps, pv.timestamps)
                && Arrays.equals(data, pv.data)
                && Arrays.equals(dataStatus, pv.dataStatus);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(timestamps);
        result = 31 * result + Arrays.hashCode(data);
        result = 31 * result + Arrays.hashCode(dataStatus);
        return result;
    }

    @Override
    public String toString() {
        return "ParameterValues[count=%d]".formatted(timestamps.length);
    }
}
