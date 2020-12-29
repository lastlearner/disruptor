package com.lmax.disruptor;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.Ref;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.J_Result;
import org.openjdk.jcstress.infra.results.LL_Result;
import org.openjdk.jcstress.infra.results.L_Result;

import static org.openjdk.jcstress.annotations.Expect.*;

public class SequenceStress
{
    /**
     * `Sequence::incrementAndGet` is atomic and should never lose an update, even with multiple threads racing
     */
    @JCStressTest
    @Outcome(id = "1", expect = FORBIDDEN, desc = "One update lost.")
    @Outcome(id = "2", expect = ACCEPTABLE, desc = "Both updates.")
    @State
    public static class IncrementAndGet
    {
        Sequence sequence = new Sequence(0);

        @Actor
        public void actor1()
        {
            sequence.incrementAndGet();
        }

        @Actor
        public void actor2()
        {
            sequence.incrementAndGet();
        }

        @Arbiter
        public void arbiter(L_Result r)
        {
            r.r1 = sequence.get();
        }
    }

    /**
     * `Sequence::compareAndSet` is atomic and should never lose an update, even with multiple threads racing
     */
    @JCStressTest
    @Outcome(id = "0", expect = FORBIDDEN, desc = "Neither update applied")
    @Outcome(id = {"10", "20"}, expect = ACCEPTABLE, desc = "Either updated.")
    @State
    public static class CompareAndSet
    {
        Sequence sequence = new Sequence(0);

        @Actor
        public void actor1()
        {
            sequence.compareAndSet(0, 10);
        }

        @Actor
        public void actor2()
        {
            sequence.compareAndSet(0, 20);
        }

        @Arbiter
        public void arbiter(L_Result r)
        {
            r.r1 = sequence.get();
        }
    }

    /**
     * `Sequence::addAndGet` is atomic and should never lose an update, even with multiple threads racing
     */
    @JCStressTest
    @Outcome(id = "10", expect = FORBIDDEN, desc = "One update lost.")
    @Outcome(id = "20", expect = FORBIDDEN, desc = "One update lost.")
    @Outcome(id = "30", expect = ACCEPTABLE, desc = "Both updates.")
    @State
    public static class AddAndGet
    {
        Sequence sequence = new Sequence(0);

        @Actor
        public void actor1()
        {
            sequence.addAndGet(10);
        }

        @Actor
        public void actor2()
        {
            sequence.addAndGet(20);
        }

        @Arbiter
        public void arbiter(L_Result r)
        {
            r.r1 = sequence.get();
        }
    }

    /**
     * Updates to non-volatile long values in Java are issued as two separate 32-bit writes.
     * Sequence should store its underlying value as a volatile long and therefore should not experience this effect
     * even when a non-volatile UNSAFE set method is used.
     */
    @JCStressTest
    @Outcome(id = "0", expect = ACCEPTABLE, desc = "Seeing the default value: writer had not acted yet.")
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "Seeing the full value.")
    @Outcome(expect = FORBIDDEN, desc = "Other cases are forbidden.")
    @Ref("https://docs.oracle.com/javase/specs/jls/se11/html/jls-17.html#jls-17.7")
    @State
    public static class LongFullSet
    {
        Sequence sequence = new Sequence(0);

        @Actor
        public void writer()
        {
            sequence.set(0xFFFFFFFF_FFFFFFFFL);
        }

        @Actor
        public void reader(J_Result r)
        {
            r.r1 = sequence.get();
        }
    }

    /**
     * Updates to non-volatile long values in Java are issued as two separate 32-bit writes.
     * Sequence should store its underlying value as a volatile long and therefore should not experience this effect.
     */
    @JCStressTest
    @Outcome(id = "0", expect = ACCEPTABLE, desc = "Seeing the default value: writer had not acted yet.")
    @Outcome(id = "-1", expect = ACCEPTABLE, desc = "Seeing the full value.")
    @Outcome(expect = FORBIDDEN, desc = "Other cases are forbidden.")
    @Ref("https://docs.oracle.com/javase/specs/jls/se11/html/jls-17.html#jls-17.7")
    @State
    public static class LongFullSetVolatile
    {
        Sequence sequence = new Sequence(0);

        @Actor
        public void writer()
        {
            sequence.setVolatile(0xFFFFFFFF_FFFFFFFFL);
        }

        @Actor
        public void reader(J_Result r)
        {
            r.r1 = sequence.get();
        }
    }


    /**
     * In absence of synchronization, the order of independent reads is undefined.
     * In our case, the value in Sequence is volatile which mandates the writes to the same
     * variable to be observed in a total order (that implies that _observers_ are also ordered)
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "Doing both reads early.")
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Doing both reads late.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Doing first read early, not surprising.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "Violates coherence.")
    @State
    public static class SameVolatileRead
    {
        private final Holder h1 = new Holder();
        private final Holder h2 = h1;

        private static class Holder
        {
            Sequence sequence = new Sequence(0);
        }

        @Actor
        public void actor1()
        {
            h1.sequence.set(1);
        }

        @Actor
        public void actor2(LL_Result r)
        {
            Holder h1 = this.h1;
            Holder h2 = this.h2;

            r.r1 = h1.sequence.get();
            r.r2 = h2.sequence.get();
        }
    }


    /**
     * The value field in Sequence is volatile so we should never see an update to it without seeing the update to a
     * previously set value also.
     * <p>
     * If the value was not volatile there would be no ordering rules stopping it being seen updated before the
     * other value.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "Doing both reads early.")
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Doing both reads late.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Caught in the middle: $x is visible, $y is not.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "Seeing $y, but not $x!")
    @State
    public static class SetVolatileGuard
    {
        long x = 0;
        Sequence y = new Sequence(0);

        @Actor
        public void actor1()
        {
            x = 1;
            y.setVolatile(1);
        }

        @Actor
        public void actor2(LL_Result r)
        {
            r.r1 = y.get();
            r.r2 = x;
        }
    }

    /**
     * The value field in Sequence is volatile so we should never see an update to it without seeing the update to a
     * previously set value also.
     * <p>
     * If the value was not volatile there would be no ordering rules stopping it being seen updated before the
     * other value.
     * <p>
     * This is a property of the field, not a property of the method used to set the value of it.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "Doing both reads early.")
    @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "Doing both reads late.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Caught in the middle: $x is visible, $y is not.")
    @Outcome(id = "1, 0", expect = FORBIDDEN, desc = "Seeing $y, but not $x!")
    @State
    public static class SetGuard
    {
        long x = 0;
        Sequence y = new Sequence(0);

        @Actor
        public void actor1()
        {
            x = 1;
            y.set(1);
        }

        @Actor
        public void actor2(LL_Result r)
        {
            r.r1 = y.get();
            r.r2 = x;
        }
    }


    /**
     * Volatile setting will experience total ordering
     */
    @JCStressTest
    @Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = ACCEPTABLE, desc = "Trivial under sequential consistency")
    @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "Violates sequential consistency")
    @State
    public static class SetVolatileDekker
    {
        Sequence x = new Sequence(0);
        Sequence y = new Sequence(0);

        @Actor
        public void actor1(LL_Result r)
        {
            x.setVolatile(1);
            r.r1 = y.get();
        }

        @Actor
        public void actor2(LL_Result r)
        {
            y.setVolatile(1);
            r.r2 = x.get();

        }
    }

    /**
     * Non-volatile setting will not experience total ordering, those gets can be re-ordered and happen before either set
     */
    @JCStressTest
    @Outcome(id = {"0, 1", "1, 0", "1, 1"}, expect = ACCEPTABLE, desc = "Trivial under sequential consistency")
    @Outcome(id = "0, 0", expect = ACCEPTABLE_INTERESTING, desc = "Violates sequential consistency")
    @State
    public static class SetDekker
    {
        Sequence x = new Sequence(0);
        Sequence y = new Sequence(0);

        @Actor
        public void actor1(LL_Result r)
        {
            x.set(1);
            r.r1 = y.get();
        }

        @Actor
        public void actor2(LL_Result r)
        {
            y.set(1);
            r.r2 = x.get();
        }
    }
}
