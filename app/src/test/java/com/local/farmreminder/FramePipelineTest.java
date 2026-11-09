package com.local.farmreminder;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class FramePipelineTest {
    private final List<Integer> recycled = new ArrayList<>();
    private final FramePipeline<Integer> p = new FramePipeline<>(recycled::add, 3, 32L*1024*1024, 3000);
    private FramePipeline.Frame<Integer> capture(int value, long time, long bytes, int generation) {
        long id=p.beginCapture(time); assertNotEquals(0,id); assertTrue(p.endCapture(id));
        return new FramePipeline.Frame<>(value,bytes,10000+time,time,generation,1,id);
    }
    @Test public void captureContinuesWhileOcrIsBusyAndFifoIsMaintained() {
        p.offer(capture(0,0,1,1),0);
        FramePipeline.Frame<Integer> first=p.beginOcr(0); assertEquals(Integer.valueOf(0),first.value);
        for (int t=400;t<=1200;t+=400) p.offer(capture(t,t,1,1),t);
        assertEquals(3,p.pendingCount()); assertNull(p.beginOcr(1500));
        assertTrue(p.finishOcr(first.request));
        assertEquals(Integer.valueOf(400),p.beginOcr(1500).value);
        assertEquals(1100,p.lastQueueWaitMs);
    }
    @Test public void capacityByteCapAndClearRecycleExactlyOnce() {
        for (int i=0;i<4;i++) p.offer(capture(i,i,1,1),i);
        assertEquals(3,p.pendingCount()); assertEquals(List.of(0),recycled); assertEquals(1,p.dropFull);
        p.clear(); assertEquals(List.of(0,1,2,3),recycled); assertEquals(3,p.dropInvalidated);
        p.offer(capture(4,10,20L*1024*1024,2),10);
        p.offer(capture(5,11,20L*1024*1024,2),11);
        assertEquals(1,p.pendingCount()); assertEquals(Integer.valueOf(4),recycled.get(4));
        p.offer(capture(6,12,32L*1024*1024+1,2),12);
        assertEquals(Integer.valueOf(6),recycled.get(5)); assertEquals(20L*1024*1024,p.pendingBytes());
        assertEquals(3,p.maxQueueObserved);
    }
    @Test public void expiryAndOldCallbacksCannotReleaseNewWork() {
        long old=p.beginCapture(0); assertTrue(p.endCapture(old)); long newer=p.beginCapture(1);
        assertFalse(p.endCapture(old)); assertTrue(p.captureBusy()); assertTrue(p.endCapture(newer));
        p.offer(new FramePipeline.Frame<>(1,1,0,0,1,1,old),0);
        FramePipeline.Frame<Integer> oldOcr=p.beginOcr(0);
        p.offer(new FramePipeline.Frame<>(2,1,1,1,1,1,newer),1); p.clear();
        p.offer(capture(3,4000,1,2),4000);
        assertNull(p.beginOcr(4000)); assertFalse(p.finishOcr(newer)); assertTrue(p.finishOcr(oldOcr.request));
        FramePipeline.Frame<Integer> fresh=p.beginOcr(4000); assertEquals(2,fresh.generation);
        assertFalse(p.finishOcr(oldOcr.request)); assertTrue(p.ocrBusy()); assertTrue(p.finishOcr(fresh.request));
        p.offer(capture(4,5000,1,2),5000); assertNull(p.beginOcr(8001));
        assertEquals(1,p.dropExpired); assertEquals(List.of(2,1,3,4),recycled);
    }
    @Test public void captureTimeMappingAndQueuedTimeDoesNotExtendMaturity() {
        assertEquals(1200,FramePipeline.captureUptime(1200,1000,1500));
        assertEquals(99700,FramePipeline.captureWall(1200,1000,99500,1500,100000));
        assertEquals(1000,FramePipeline.captureUptime(1600,1000,1500));
        assertEquals(99500,FramePipeline.captureWall(1600,1000,99500,1500,100000));
        assertEquals(99500,FramePipeline.captureWall(-1,1000,99500,1500,100000));
        RecognitionLogic logic = new RecognitionLogic();
        p.offer(capture(1,1000,1,1),1000);
        FramePipeline.Frame<Integer> first=p.beginOcr(1000);
        assertNull(logic.observe("friend",10000L,first.wall)); assertTrue(p.finishOcr(first.request));
        p.offer(capture(2,3000,1,1),3000);
        FramePipeline.Frame<Integer> queued=p.beginOcr(5000);
        assertEquals(2000,p.lastQueueWaitMs);
        assertEquals(Long.valueOf(21000),logic.observe("friend",8000L,queued.wall));
    }
    @Test public void timedOutOcrBlocksRestartUntilRealCompletionAndDisposesOnce() {
        p.offer(capture(10,100,1,1),100);
        FramePipeline.Frame<Integer> active=p.beginOcr(100);
        p.offer(capture(11,500,1,1),500);
        assertFalse(p.updateTimeouts(12099));
        assertTrue(p.updateTimeouts(12100));
        assertFalse(p.startAllowed()); assertFalse(p.ocrUsable(active.request));
        p.clear(); // service fail-stop clears pending, not the active frame
        assertEquals(List.of(11),recycled);
        assertEquals(0,p.beginCapture(12101)); assertNull(p.beginOcr(12101));
        assertFalse(p.startAllowed()); assertEquals(List.of(11),recycled);
        assertTrue(p.finishOcr(active.request)); assertEquals(List.of(11,10),recycled);
        assertFalse(p.finishOcr(active.request)); assertEquals(List.of(11,10),recycled);
        assertTrue(p.startAllowed()); assertNotEquals(0,p.beginCapture(12102));
    }
    @Test public void timeoutBarrierSurvivesSameAndNewOwnerAndOldWatchdog() {
        p.offer(capture(20,0,1,1),0);
        FramePipeline.Frame<Integer> active=p.beginOcr(0);
        FramePipeline<Integer> sameOwner=p, newOwner=p; // service instances share its static pipeline
        assertTrue(sameOwner.timeoutOcr(active.request,12000));
        sameOwner.clear(); assertFalse(sameOwner.startAllowed());
        newOwner.clear(); assertFalse(newOwner.startAllowed());
        assertEquals(0,newOwner.beginCapture(12001));
        assertFalse(newOwner.timeoutOcr(active.request+1,12001));
        assertTrue(newOwner.finishOcr(active.request)); assertTrue(newOwner.startAllowed());
        long fresh=newOwner.beginCapture(12002); assertNotEquals(0,fresh);
        assertFalse(newOwner.timeoutOcr(active.request,24002));
        assertTrue(newOwner.captureUsable(fresh));
    }
    @Test public void timedOutCaptureWaitsForCallbackAndCannotLeakIntoNewSession() {
        long old=p.beginCapture(200); assertTrue(p.timeoutCapture(old,12200));
        assertTrue(p.captureBusy()); assertFalse(p.startAllowed()); assertEquals(0,p.beginCapture(12201));
        assertFalse(p.captureUsable(old)); // callback closes its hardware buffer without copying/enqueueing
        assertTrue(p.endCapture(old)); assertFalse(p.captureBusy()); assertTrue(p.startAllowed()); assertEquals(0,p.pendingCount());
        long fresh=p.beginCapture(12202); assertNotEquals(0,fresh);
        assertFalse(p.endCapture(old)); assertFalse(p.timeoutCapture(old,24202));
        assertTrue(p.captureUsable(fresh)); assertTrue(p.endCapture(fresh));
        assertTrue(p.startAllowed()); assertEquals(0,p.pendingCount());
    }
    @Test public void dynamicBudgetClampsLowAndHighHeap() {
        long mib=1024L*1024;
        assertEquals(64*mib, FramePipeline.memoryBudget(256*mib));
        assertEquals(96*mib, FramePipeline.memoryBudget(512*mib));
        assertEquals(8*mib, FramePipeline.memoryBudget(16*mib));
    }
    @Test public void defaultCountCapDropsOldestAndClearCountsInvalidated() {
        FramePipeline<Integer> q=new FramePipeline<>(recycled::add,12,100,8000);
        for (int i=0;i<13;i++) q.offer(new FramePipeline.Frame<>(i,1,0,i,1,1,i+1),i);
        assertEquals(12,q.pendingCount()); assertEquals(List.of(0),recycled);
        q.clear(); assertEquals(0,q.pendingCount()); assertEquals(13,q.dropped());
        assertEquals(13,recycled.size());
    }
    @Test public void byteCapAndOversizeDropWithoutExceedingBudget() {
        FramePipeline<Integer> q=new FramePipeline<>(recycled::add,12,10,8000);
        q.offer(new FramePipeline.Frame<>(1,6,0,0,1,1,1),0);
        q.offer(new FramePipeline.Frame<>(2,6,0,1,1,1,2),1);
        q.offer(new FramePipeline.Frame<>(3,11,0,2,1,1,3),2);
        assertEquals(6,q.pendingBytes()); assertEquals(1,q.pendingCount());
        assertEquals(List.of(1,3),recycled); assertEquals(2,q.dropFull);
    }
    @Test public void eightSecondTtlIsInclusive() {
        FramePipeline<Integer> q=new FramePipeline<>(recycled::add,12,100,8000);
        q.offer(new FramePipeline.Frame<>(1,1,0,0,1,1,1),0);
        assertNotNull(q.beginOcr(8000)); assertTrue(q.finishOcr(1));
        q.offer(new FramePipeline.Frame<>(2,1,0,0,1,1,2),0);
        assertNull(q.beginOcr(8001)); assertEquals(1,q.dropExpired);
    }
    @Test public void progressSeparatesPendingFromActiveAndClearPreservesActive() {
        FramePipeline<Integer> q=new FramePipeline<>(recycled::add,12,100,8000);
        q.offer(new FramePipeline.Frame<>(1,10,0,0,1,1,1),0);
        q.offer(new FramePipeline.Frame<>(2,20,0,0,1,1,2),0);
        q.beginOcr(0);
        OcrProgress s=q.progress(4,6,0);
        assertEquals(4,s.processed); assertEquals(6,s.captured); assertEquals(1,s.queued);
        assertEquals(20,s.pendingBytes); assertTrue(s.recognizing);
        q.clear(); s=q.progress(4,6,0);
        assertEquals(0,s.queued); assertEquals(0,s.pendingBytes); assertTrue(s.recognizing); assertEquals(1,s.dropped);
        assertTrue(q.finishOcr(1)); assertFalse(q.progress(5,6,0).recognizing);
    }
}
