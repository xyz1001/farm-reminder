package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.local.farmreminder.ReminderNotificationActions.Target.*;

public class ReminderNotificationActionsTest {
    @Test public void installedWechatOffersTwoExplicitDestinations() {
        assertArrayEquals(new ReminderNotificationActions.Target[]{WECHAT, APP}, ReminderNotificationActions.available(true));
        assertEquals("打开微信", WECHAT.label);
        assertEquals("打开应用", APP.label);
    }

    @Test public void missingWechatLeavesOnlyRealAppActionAndBodyStillOpensApp() {
        assertArrayEquals(new ReminderNotificationActions.Target[]{APP}, ReminderNotificationActions.available(false));
        assertEquals(APP, ReminderNotificationActions.contentTarget());
    }

    @Test public void requestCodesAreStableDistinctAndNotLegacyRequestZero() {
        assertEquals(701, WECHAT.requestCode);
        assertEquals(702, APP.requestCode);
        assertNotEquals(WECHAT.requestCode, APP.requestCode);
        assertNotEquals(0, APP.requestCode);
        assertNotEquals(0, WECHAT.requestCode);
    }

    @Test public void taggedNamespaceIsStableAndNonempty() {
        assertEquals("maturity_reminder", ReminderNotificationActions.TAG);
        assertFalse(ReminderNotificationActions.TAG.trim().isEmpty());
    }

    @Test public void changingReturnedChoicesCannotMutateFutureNotifications() {
        ReminderNotificationActions.Target[] choices = ReminderNotificationActions.available(true);
        choices[0] = APP;
        assertArrayEquals(new ReminderNotificationActions.Target[]{WECHAT, APP}, ReminderNotificationActions.available(true));
    }
}
