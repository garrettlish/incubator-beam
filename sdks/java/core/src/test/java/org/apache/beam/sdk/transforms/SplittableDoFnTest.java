/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.transforms;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.beam.sdk.transforms.DoFn.ProcessContinuation.resume;
import static org.apache.beam.sdk.transforms.DoFn.ProcessContinuation.stop;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.BigEndianIntegerCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.RunnableOnService;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.testing.UsesSplittableParDo;
import org.apache.beam.sdk.transforms.DoFn.BoundedPerElement;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.MutableDateTime;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for <a href="https://s.apache.org/splittable-do-fn>splittable</a> {@link DoFn} behavior.
 */
@RunWith(JUnit4.class)
public class SplittableDoFnTest {
  static class OffsetRange implements Serializable {
    public final int from;
    public final int to;

    OffsetRange(int from, int to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public String toString() {
      return "OffsetRange{" + "from=" + from + ", to=" + to + '}';
    }
  }

  private static class OffsetRangeTracker implements RestrictionTracker<OffsetRange> {
    private OffsetRange range;
    private Integer lastClaimedIndex = null;

    OffsetRangeTracker(OffsetRange range) {
      this.range = checkNotNull(range);
    }

    @Override
    public OffsetRange currentRestriction() {
      return range;
    }

    @Override
    public OffsetRange checkpoint() {
      if (lastClaimedIndex == null) {
        OffsetRange res = range;
        range = new OffsetRange(range.from, range.from);
        return res;
      }
      OffsetRange res = new OffsetRange(lastClaimedIndex + 1, range.to);
      this.range = new OffsetRange(range.from, lastClaimedIndex + 1);
      return res;
    }

    boolean tryClaim(int i) {
      checkState(lastClaimedIndex == null || i > lastClaimedIndex);
      if (i >= range.to) {
        return false;
      }
      lastClaimedIndex = i;
      return true;
    }
  }

  static class PairStringWithIndexToLength extends DoFn<String, KV<String, Integer>> {
    @ProcessElement
    public ProcessContinuation process(ProcessContext c, OffsetRangeTracker tracker) {
      for (int i = tracker.currentRestriction().from; tracker.tryClaim(i); ++i) {
        c.output(KV.of(c.element(), i));
        if (i % 3 == 0) {
          return resume();
        }
      }
      return stop();
    }

    @GetInitialRestriction
    public OffsetRange getInitialRange(String element) {
      return new OffsetRange(0, element.length());
    }

    @SplitRestriction
    public void splitRange(
        String element, OffsetRange range, OutputReceiver<OffsetRange> receiver) {
      receiver.output(new OffsetRange(range.from, (range.from + range.to) / 2));
      receiver.output(new OffsetRange((range.from + range.to) / 2, range.to));
    }

    @NewTracker
    public OffsetRangeTracker newTracker(OffsetRange range) {
      return new OffsetRangeTracker(range);
    }
  }

  private static class ReifyTimestampsFn<T> extends DoFn<T, TimestampedValue<T>> {
    @ProcessElement
    public void process(ProcessContext c) {
      c.output(TimestampedValue.of(c.element(), c.timestamp()));
    }
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testPairWithIndexBasic() {
    Pipeline p = TestPipeline.create();
    PCollection<KV<String, Integer>> res =
        p.apply(Create.of("a", "bb", "ccccc"))
            .apply(ParDo.of(new PairStringWithIndexToLength()))
            .setCoder(KvCoder.of(StringUtf8Coder.of(), BigEndianIntegerCoder.of()));

    PAssert.that(res)
        .containsInAnyOrder(
            Arrays.asList(
                KV.of("a", 0),
                KV.of("bb", 0),
                KV.of("bb", 1),
                KV.of("ccccc", 0),
                KV.of("ccccc", 1),
                KV.of("ccccc", 2),
                KV.of("ccccc", 3),
                KV.of("ccccc", 4)));

    p.run();
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testPairWithIndexWindowedTimestamped() {
    // Tests that Splittable DoFn correctly propagates windowing strategy, windows and timestamps
    // of elements in the input collection.
    Pipeline p = TestPipeline.create();

    MutableDateTime mutableNow = Instant.now().toMutableDateTime();
    mutableNow.setMillisOfSecond(0);
    Instant now = mutableNow.toInstant();
    Instant nowP1 = now.plus(Duration.standardSeconds(1));
    Instant nowP2 = now.plus(Duration.standardSeconds(2));

    SlidingWindows windowFn =
        SlidingWindows.of(Duration.standardSeconds(5)).every(Duration.standardSeconds(1));
    PCollection<KV<String, Integer>> res =
        p.apply(
                Create.timestamped(
                    TimestampedValue.of("a", now),
                    TimestampedValue.of("bb", nowP1),
                    TimestampedValue.of("ccccc", nowP2)))
            .apply(Window.<String>into(windowFn))
            .apply(ParDo.of(new PairStringWithIndexToLength()))
            .setCoder(KvCoder.of(StringUtf8Coder.of(), BigEndianIntegerCoder.of()));

    assertEquals(windowFn, res.getWindowingStrategy().getWindowFn());

    PCollection<TimestampedValue<KV<String, Integer>>> timestamped =
        res.apply("Reify timestamps", ParDo.of(new ReifyTimestampsFn<KV<String, Integer>>()));

    for (int i = 0; i < 4; ++i) {
      Instant base = now.minus(Duration.standardSeconds(i));
      IntervalWindow window = new IntervalWindow(base, base.plus(Duration.standardSeconds(5)));

      List<TimestampedValue<KV<String, Integer>>> expectedUnfiltered =
          Arrays.asList(
              TimestampedValue.of(KV.of("a", 0), now),
              TimestampedValue.of(KV.of("bb", 0), nowP1),
              TimestampedValue.of(KV.of("bb", 1), nowP1),
              TimestampedValue.of(KV.of("ccccc", 0), nowP2),
              TimestampedValue.of(KV.of("ccccc", 1), nowP2),
              TimestampedValue.of(KV.of("ccccc", 2), nowP2),
              TimestampedValue.of(KV.of("ccccc", 3), nowP2),
              TimestampedValue.of(KV.of("ccccc", 4), nowP2));

      List<TimestampedValue<KV<String, Integer>>> expected = new ArrayList<>();
      for (TimestampedValue<KV<String, Integer>> tv : expectedUnfiltered) {
        if (!window.start().isAfter(tv.getTimestamp())
            && !tv.getTimestamp().isAfter(window.maxTimestamp())) {
          expected.add(tv);
        }
      }
      assertFalse(expected.isEmpty());

      PAssert.that(timestamped).inWindow(window).containsInAnyOrder(expected);
    }
    p.run();
  }

  @BoundedPerElement
  private static class SDFWithMultipleOutputsPerBlock extends DoFn<String, Integer> {
    private static final int MAX_INDEX = 98765;

    private static int snapToNextBlock(int index, int[] blockStarts) {
      for (int i = 1; i < blockStarts.length; ++i) {
        if (index > blockStarts[i - 1] && index <= blockStarts[i]) {
          return i;
        }
      }
      throw new IllegalStateException("Shouldn't get here");
    }

    @ProcessElement
    public ProcessContinuation processElement(ProcessContext c, OffsetRangeTracker tracker) {
      int[] blockStarts = {-1, 0, 12, 123, 1234, 12345, 34567, MAX_INDEX};
      int trueStart = snapToNextBlock(tracker.currentRestriction().from, blockStarts);
      int trueEnd = snapToNextBlock(tracker.currentRestriction().to, blockStarts);
      for (int i = trueStart; i < trueEnd; ++i) {
        if (!tracker.tryClaim(blockStarts[i])) {
          return resume();
        }
        for (int index = blockStarts[i]; index < blockStarts[i + 1]; ++index) {
          c.output(index);
        }
      }
      return stop();
    }

    @GetInitialRestriction
    public OffsetRange getInitialRange(String element) {
      return new OffsetRange(0, MAX_INDEX);
    }

    @NewTracker
    public OffsetRangeTracker newTracker(OffsetRange range) {
      return new OffsetRangeTracker(range);
    }
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testOutputAfterCheckpoint() throws Exception {
    Pipeline p = TestPipeline.create();
    PCollection<Integer> outputs = p.apply(Create.of("foo"))
        .apply(ParDo.of(new SDFWithMultipleOutputsPerBlock()));
    PAssert.thatSingleton(outputs.apply(Count.<Integer>globally()))
        .isEqualTo((long) SDFWithMultipleOutputsPerBlock.MAX_INDEX);
    p.run();
  }

  private static class SDFWithSideInputsAndOutputs extends DoFn<Integer, String> {
    private final PCollectionView<String> sideInput;
    private final TupleTag<String> sideOutput;

    private SDFWithSideInputsAndOutputs(
        PCollectionView<String> sideInput, TupleTag<String> sideOutput) {
      this.sideInput = sideInput;
      this.sideOutput = sideOutput;
    }

    @ProcessElement
    public void process(ProcessContext c, OffsetRangeTracker tracker) {
      checkState(tracker.tryClaim(tracker.currentRestriction().from));
      String side = c.sideInput(sideInput);
      c.output("main:" + side + ":" + c.element());
      c.sideOutput(sideOutput, "side:" + side + ":" + c.element());
    }

    @GetInitialRestriction
    public OffsetRange getInitialRestriction(Integer value) {
      return new OffsetRange(0, 1);
    }

    @NewTracker
    public OffsetRangeTracker newTracker(OffsetRange range) {
      return new OffsetRangeTracker(range);
    }
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testSideInputsAndOutputs() throws Exception {
    Pipeline p = TestPipeline.create();

    PCollectionView<String> sideInput =
        p.apply("side input", Create.of("foo")).apply(View.<String>asSingleton());
    TupleTag<String> mainOutputTag = new TupleTag<>("main");
    TupleTag<String> sideOutputTag = new TupleTag<>("side");

    PCollectionTuple res =
        p.apply("input", Create.of(0, 1, 2))
            .apply(
                ParDo.of(new SDFWithSideInputsAndOutputs(sideInput, sideOutputTag))
                    .withSideInputs(sideInput)
                    .withOutputTags(mainOutputTag, TupleTagList.of(sideOutputTag)));
    res.get(mainOutputTag).setCoder(StringUtf8Coder.of());
    res.get(sideOutputTag).setCoder(StringUtf8Coder.of());

    PAssert.that(res.get(mainOutputTag))
        .containsInAnyOrder(Arrays.asList("main:foo:0", "main:foo:1", "main:foo:2"));
    PAssert.that(res.get(sideOutputTag))
        .containsInAnyOrder(Arrays.asList("side:foo:0", "side:foo:1", "side:foo:2"));

    p.run();
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testLateData() throws Exception {
    Pipeline p = TestPipeline.create();

    Instant base = Instant.now();

    TestStream<String> stream =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(base)
            .addElements("aa")
            .advanceWatermarkTo(base.plus(Duration.standardSeconds(5)))
            .addElements(TimestampedValue.of("bb", base.minus(Duration.standardHours(1))))
            .advanceProcessingTime(Duration.standardHours(1))
            .advanceWatermarkToInfinity();

    PCollection<String> input =
        p.apply(stream)
            .apply(
                Window.<String>into(FixedWindows.of(Duration.standardMinutes(1)))
                    .withAllowedLateness(Duration.standardMinutes(1)));

    PCollection<KV<String, Integer>> afterSDF =
        input
            .apply(ParDo.of(new PairStringWithIndexToLength()))
            .setCoder(KvCoder.of(StringUtf8Coder.of(), BigEndianIntegerCoder.of()));

    PCollection<String> nonLate =
        afterSDF.apply(GroupByKey.<String, Integer>create()).apply(Keys.<String>create());

    // The splittable DoFn itself should not drop any data and act as pass-through.
    PAssert.that(afterSDF)
        .containsInAnyOrder(
            Arrays.asList(KV.of("aa", 0), KV.of("aa", 1), KV.of("bb", 0), KV.of("bb", 1)));

    // But it should preserve the windowing strategy of the data, including allowed lateness:
    // the follow-up GBK should drop the late data.
    assertEquals(afterSDF.getWindowingStrategy(), input.getWindowingStrategy());
    PAssert.that(nonLate).containsInAnyOrder("aa");

    p.run();
  }

  private static class SDFWithLifecycle extends DoFn<String, String> {
    private enum State {
      BEFORE_SETUP,
      OUTSIDE_BUNDLE,
      INSIDE_BUNDLE,
      TORN_DOWN
    }

    private State state = State.BEFORE_SETUP;

    @ProcessElement
    public void processElement(ProcessContext c, OffsetRangeTracker tracker) {
      assertEquals(State.INSIDE_BUNDLE, state);
      assertTrue(tracker.tryClaim(0));
      c.output(c.element());
    }

    @GetInitialRestriction
    public OffsetRange getInitialRestriction(String value) {
      return new OffsetRange(0, 1);
    }

    @NewTracker
    public OffsetRangeTracker newTracker(OffsetRange range) {
      return new OffsetRangeTracker(range);
    }

    @Setup
    public void setUp() {
      assertEquals(State.BEFORE_SETUP, state);
      state = State.OUTSIDE_BUNDLE;
    }

    @StartBundle
    public void startBundle(Context c) {
      assertEquals(State.OUTSIDE_BUNDLE, state);
      state = State.INSIDE_BUNDLE;
    }

    @FinishBundle
    public void finishBundle(Context c) {
      assertEquals(State.INSIDE_BUNDLE, state);
      state = State.OUTSIDE_BUNDLE;
    }

    @Teardown
    public void tearDown() {
      assertEquals(State.OUTSIDE_BUNDLE, state);
      state = State.TORN_DOWN;
    }
  }

  @Test
  @Category({RunnableOnService.class, UsesSplittableParDo.class})
  public void testLifecycleMethods() throws Exception {
    Pipeline p = TestPipeline.create();

    PCollection<String> res =
        p.apply(Create.of("a", "b", "c")).apply(ParDo.of(new SDFWithLifecycle()));

    PAssert.that(res).containsInAnyOrder("a", "b", "c");

    p.run();
  }

  // TODO (https://issues.apache.org/jira/browse/BEAM-988): Test that Splittable DoFn
  // emits output immediately (i.e. has a pass-through trigger) regardless of input's
  // windowing/triggering strategy.
}
