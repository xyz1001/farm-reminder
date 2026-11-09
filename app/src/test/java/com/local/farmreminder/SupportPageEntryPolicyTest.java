package com.local.farmreminder;

import org.junit.Test;
import static org.junit.Assert.*;

public class SupportPageEntryPolicyTest {
    @Test public void onlyReadyResumedNormalPageEntryCanOffer() {
        assertTrue(SupportPageEntryPolicy.canOffer(true, false, true, false, true, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(false, false, true, false, true, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, true, true, false, true, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, false, false, false, true, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, false, true, true, true, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, false, true, false, false, false, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, false, true, false, true, true, false));
        assertFalse(SupportPageEntryPolicy.canOffer(true, false, true, false, true, false, true));
    }

    @Test public void homeAndSettingsAreNormalPagesButSetupAndUnknownAreNot() {
        assertTrue(SupportPageEntryPolicy.isNormalPage("home"));
        assertTrue(SupportPageEntryPolicy.isNormalPage("settings"));
        assertFalse(SupportPageEntryPolicy.isNormalPage("setup"));
        assertFalse(SupportPageEntryPolicy.isNormalPage("unknown"));
        assertFalse(SupportPageEntryPolicy.isNormalPage(null));
        for (String page : new String[]{"home", "settings"}) {
            assertTrue(SupportPageEntryPolicy.canOffer(true, false,
                    SupportPageEntryPolicy.isNormalPage(page), false, true, false, false));
            assertFalse(SupportPageEntryPolicy.canOffer(true, false,
                    SupportPageEntryPolicy.isNormalPage(page), true, true, false, false));
        }
    }

    @Test public void saveCompletionCannotLaunchFromBackgroundOrRotatedActivity() {
        assertTrue(SupportPageEntryPolicy.canOpenWeChat(true, false, true));
        assertFalse(SupportPageEntryPolicy.canOpenWeChat(false, false, true));
        assertFalse(SupportPageEntryPolicy.canOpenWeChat(true, true, true));
        assertFalse(SupportPageEntryPolicy.canOpenWeChat(true, false, false));
    }

    @Test public void dialogExplainsConsentAndOptionalNatureWithoutPaymentClaim() {
        for (String copy : new String[]{SupportPromptPresentation.AUTOMATIC_MESSAGE,
                SupportPromptPresentation.MANUAL_MESSAGE}) {
            assertTrue(copy.contains("同意"));
            assertTrue(copy.contains("保存"));
            assertTrue(copy.contains("打开微信"));
            assertTrue(copy.contains("手动"));
            assertTrue(copy.contains("自愿"));
            assertTrue(copy.contains("不影响监测"));
            assertFalse(copy.contains("支付成功"));
        }
        assertTrue(SupportPromptPresentation.AUTOMATIC_MESSAGE.contains("不会再自动询问"));
    }
}
