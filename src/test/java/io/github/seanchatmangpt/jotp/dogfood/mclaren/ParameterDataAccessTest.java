package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.concurrent.TimeUnit;
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParameterDataAccess} — the gen_server Proc that implements SQL Race
 * {@code IParameterDataAccess}.
 *
 * <p>Tests cover: sample ingestion, data status tagging, GoTo/GetNextSamples, ring buffer cap,
 * stats, and the {@link org.acme.CrashRecovery}-backed spawn.
 */
class ParameterDataAccessTest implements WithAssertions {

    private Proc<ParameterDataAccess.State, PdaMsg> proc;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (proc != null && proc.thread().isAlive()) {
            proc.stop();
        }
    }

    private static SqlRaceParameter vCar() {
        return SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph");
    }

    private static SqlRaceChannel vCarChannel() {
        return SqlRaceChannel.periodic(1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit);
    }

    // ── Spawn ─────────────────────────────────────────────────────────────────

    @Test
    void spawnSucceeds() {
        var result = ParameterDataAccess.spawn(vCar(), vCarChannel());

        assertThat(result.isSuccess()).isTrue();
        proc = result.orElseThrow();
        assertThat(proc.thread().isAlive()).isTrue();
    }

    @Test
    void spawnOrThrowReturnsRunningProcess() {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());

        assertThat(proc.thread().isAlive()).isTrue();
    }

    // ── AddSamples: good path ─────────────────────────────────────────────────

    @Test
    void goodSampleStoredAndRetrieved() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        long ts = 1_000_000L;

        proc.tell(new PdaMsg.AddSamples(new long[] {ts}, new double[] {237.5}));
        Thread.sleep(20); // let the virtual thread process
        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 10, StepDirection.Forward);

        assertThat(values.count()).isEqualTo(1);
        assertThat(values.data()[0]).isEqualTo(237.5);
        assertThat(values.timestamps()[0]).isEqualTo(ts);
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.Good);
    }

    @Test
    void multipleSamplesInBatch() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());

        proc.tell(new PdaMsg.AddSamples(
                new long[] {1L, 2L, 3L, 4L, 5L},
                new double[] {100.0, 150.0, 200.0, 250.0, 300.0}));
        Thread.sleep(20);
        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 10, StepDirection.Forward);

        assertThat(values.count()).isEqualTo(5);
        assertThat(values.goodSamples().count()).isEqualTo(5);
    }

    // ── Out-of-range: tagged, process NOT crashed ─────────────────────────────

    @Test
    void outOfRangeSampleTaggedNotCrashed() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());

        // 450 kph is above max 400 → OutOfRange
        proc.tell(new PdaMsg.AddSamples(new long[] {1L}, new double[] {450.0}));
        Thread.sleep(20);
        assertThat(proc.thread().isAlive()).isTrue();

        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 1, StepDirection.Forward);
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.OutOfRange);
    }

    @Test
    void invalidDataTaggedForNonFiniteValues() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());

        proc.tell(new PdaMsg.AddSamplesWithStatus(
                new long[] {1L},
                new double[] {Double.NaN},
                new DataStatusType[] {DataStatusType.InvalidData}));
        Thread.sleep(20);
        assertThat(proc.thread().isAlive()).isTrue();

        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 1, StepDirection.Forward);
        assertThat(values.dataStatus()[0]).isEqualTo(DataStatusType.InvalidData);
    }

    // ── GoTo + GetNextSamples ─────────────────────────────────────────────────

    @Test
    void goToSetsStartPositionForForwardScan() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        proc.tell(new PdaMsg.AddSamples(
                new long[] {10L, 20L, 30L, 40L, 50L},
                new double[] {1.0, 2.0, 3.0, 4.0, 5.0}));
        Thread.sleep(20);

        proc.tell(new PdaMsg.GoTo(25L)); // seek to between 20 and 30
        var values = ParameterDataAccess.getNextSamples(proc, 2, StepDirection.Forward);

        // Should return 30 and 40 (first two entries ≥ 25)
        assertThat(values.count()).isEqualTo(2);
        assertThat(values.timestamps()[0]).isEqualTo(30L);
        assertThat(values.timestamps()[1]).isEqualTo(40L);
    }

    @Test
    void reverseStepReturnsDescendingOrder() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        proc.tell(new PdaMsg.AddSamples(
                new long[] {10L, 20L, 30L, 40L, 50L},
                new double[] {1.0, 2.0, 3.0, 4.0, 5.0}));
        Thread.sleep(20);

        proc.tell(new PdaMsg.GoTo(45L));
        var values = ParameterDataAccess.getNextSamples(proc, 3, StepDirection.Reverse);

        // headMap(45, inclusive).descendingMap → 40, 30, 20
        assertThat(values.count()).isEqualTo(3);
        assertThat(values.timestamps()[0]).isEqualTo(40L);
        assertThat(values.timestamps()[1]).isEqualTo(30L);
        assertThat(values.timestamps()[2]).isEqualTo(20L);
    }

    @Test
    void emptyBufferReturnsEmptyValues() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());

        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 10, StepDirection.Forward);

        assertThat(values.count()).isZero();
    }

    // ── Ring buffer cap ───────────────────────────────────────────────────────

    @Test
    void ringBufferCapEvictsOldestSamples() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        int cap = ParameterDataAccess.RING_BUFFER_CAP;

        // Add cap+1 samples
        long[] timestamps = new long[cap + 1];
        double[] values = new double[cap + 1];
        for (int i = 0; i < cap + 1; i++) {
            timestamps[i] = i + 1;
            values[i] = 100.0;
        }
        proc.tell(new PdaMsg.AddSamples(timestamps, values));
        Thread.sleep(100); // let the virtual thread process all

        // Buffer should be capped at RING_BUFFER_CAP; oldest (ts=1) evicted
        var future = new java.util.concurrent.CompletableFuture<PdaStats>();
        proc.tell(new PdaMsg.GetStats(future));
        var stats = future.get(5, TimeUnit.SECONDS);

        assertThat(stats.bufferSize()).isEqualTo(cap);
    }

    // ── GetStats ──────────────────────────────────────────────────────────────

    @Test
    void getStatsReturnsAccurateCounts() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        proc.tell(new PdaMsg.AddSamples(
                new long[] {1L, 2L, 3L},
                new double[] {100.0, 450.0, 200.0})); // 450 is out-of-range
        Thread.sleep(20);

        var stats = ParameterDataAccess.getStats(proc);

        assertThat(stats.paramIdentifier()).isEqualTo("vCar:Chassis");
        assertThat(stats.totalSamples()).isEqualTo(3);
        assertThat(stats.goodSamples()).isEqualTo(2); // 100 and 200 are good
        assertThat(stats.bufferSize()).isEqualTo(3);
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    void clearEmptiesBuffer() throws Exception {
        proc = ParameterDataAccess.spawnOrThrow(vCar(), vCarChannel());
        proc.tell(new PdaMsg.AddSamples(new long[] {1L, 2L, 3L},
                new double[] {100.0, 200.0, 300.0}));
        Thread.sleep(20);

        proc.tell(new PdaMsg.Clear());
        Thread.sleep(20);

        proc.tell(new PdaMsg.GoTo(0L));
        var values = ParameterDataAccess.getNextSamples(proc, 10, StepDirection.Forward);
        assertThat(values.count()).isZero();
    }
}
