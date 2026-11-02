package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ScenePresenceTest {
    private static OcrLine l(String s,int x,int y,int r,int b) { return new OcrLine(s,x,y,r,b); }
    private static final int W=1000,H=2000;
    private static List<OcrLine> friend(String name) { return Arrays.asList(l(name,160,130,280,160),l("回家",800,1600,870,1640)); }
    @Test public void friendOwnFarmAndChatUseIndependentEvidence() {
        assertTrue(ScenePresence.isFarmScene(friend("名字"),W,H));
        assertTrue(ScenePresence.isFarmScene(friend("148"),W,H)); // scene is not nickname parsing
        assertTrue(ScenePresence.isFarmScene(Arrays.asList(l("玩家",160,130,280,160),l("仓库",50,1900,120,1940),l("商店",200,1900,270,1940)),W,H));
        assertTrue(ScenePresence.isFarmScene(Arrays.asList(l("玩家",160,130,280,160),l("劳动光荣",220,800,340,840)),W,H));
        assertFalse(ScenePresence.isFarmScene(Arrays.asList(l("回家",800,1600,870,1640)),W,H));
        assertFalse(ScenePresence.isFarmScene(Arrays.asList(l("玩家",160,130,280,160),l("商店",200,1900,270,1940)),W,H));
        assertFalse(ScenePresence.isFarmScene(Arrays.asList(l("玩家",160,130,280,160),l("劳动光荣",220,100,340,140)),W,H));
        assertFalse(ScenePresence.isFarmScene(friend("玩家"),0,H));
    }
    @Test public void twoHitsEnterOnceMissesExitOnce() {
        ScenePresence p=new ScenePresence();
        assertEquals(ScenePresence.Change.NONE,p.observe(true));
        assertEquals(ScenePresence.Change.ENTER,p.observe(true));
        assertEquals(ScenePresence.Change.NONE,p.observe(true));
        assertEquals(ScenePresence.Change.NONE,p.observe(false));
        assertEquals(ScenePresence.Change.NONE,p.observe(false)); assertTrue(p.present());
        assertEquals(ScenePresence.Change.NONE,p.observe(true));
        assertEquals(ScenePresence.Change.NONE,p.observe(false));
        assertEquals(ScenePresence.Change.NONE,p.observe(false));
        assertEquals(ScenePresence.Change.EXIT,p.observe(false));
        assertEquals(ScenePresence.Change.NONE,p.observe(false));
    }
    @Test public void explicitForegroundLeaveIsImmediateAndFriendSwitchDoesNotRefresh() {
        ScenePresence p=new ScenePresence();
        assertEquals(ScenePresence.Change.NONE,p.observe(ScenePresence.isFarmScene(friend("甲甲"),W,H)));
        assertEquals(ScenePresence.Change.ENTER,p.observe(ScenePresence.isFarmScene(friend("甲甲"),W,H)));
        assertEquals(ScenePresence.Change.NONE,p.observe(ScenePresence.isFarmScene(friend("乙乙"),W,H)));
        assertEquals(ScenePresence.Change.EXIT,p.leave()); assertEquals(ScenePresence.Change.NONE,p.leave());
        assertEquals(ScenePresence.Change.NONE,p.observe(true));
        assertEquals(ScenePresence.Change.ENTER,p.observe(true));
    }
    @Test public void newWindowDoesNotCombineUnconfirmedOldWindowFrame() {
        ScenePresence p=new ScenePresence();
        assertEquals(ScenePresence.Change.NONE,p.observe(true)); p.breakStreak();
        assertEquals(ScenePresence.Change.NONE,p.observe(true));
        assertEquals(ScenePresence.Change.ENTER,p.observe(true));
        p.breakStreak(); assertTrue(p.present());
    }
    @Test public void feedbackOnlyForConfirmedDistinctResultsAndResetOnExit() {
        RecognitionLogic recognition=new RecognitionLogic(); FeedbackEventPolicy feedback=new FeedbackEventPolicy();
        assertNull(recognition.observe("甲甲",null,1000));
        assertNull(RecognitionLogic.parseCountdown(Arrays.asList("小叶子 01:40:46")));
        assertNull(recognition.observe("甲甲",10000L,1000));
        Long confirmed=recognition.observe("甲甲",9000L,2000); assertNotNull(confirmed);
        assertTrue(feedback.confirmed("甲甲",confirmed));
        assertFalse(feedback.confirmed("甲甲",confirmed+5000));
        assertTrue(feedback.confirmed("甲甲",confirmed+10000));
        assertFalse(feedback.confirmed("甲甲",confirmed));
        assertTrue(feedback.confirmed("乙乙",confirmed));
        feedback.reset(); assertTrue(feedback.confirmed("甲甲",confirmed));
    }
    @Test public void saveOutcomeRequiresSuccessfulCommit() {
        assertEquals(SaveOutcome.ADDED,SaveOutcome.fromCommit(true));
        assertEquals(SaveOutcome.FAILED,SaveOutcome.fromCommit(false));
        assertNotEquals(SaveOutcome.ADDED,SaveOutcome.DUPLICATE);
    }
}
