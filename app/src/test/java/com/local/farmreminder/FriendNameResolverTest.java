package com.local.farmreminder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class FriendNameResolverTest {
    private OcrLine line(String text, int l, int t, int r, int b) { return new OcrLine(text, l, t, r, b); }
    private OcrLine bar() { return line("e169 78万/194万", 113, 233, 405, 278); }
    private OcrLine home() { return line("回家", 1000, 2200, 1100, 2250); }

    @Test public void missingAndGraphicNamesStayUnknown() {
        List<OcrLine> lines = Arrays.asList(bar(), home(), line("好友:其他", 180, 2500, 400, 2540));
        FriendNameResolver.Resolution r = FriendNameResolver.resolve(lines, 1240, 2772);
        assertNull(r.text); assertEquals("unknown", r.source); assertTrue(r.needsAssistance);
        assertNull(OcrFrameParser.friend(lines, 1240, 2772));
        assertNull(FriendNameResolver.resolve(Arrays.asList(line("78万/194万",145,235,405,277), home()),1240,2772).text);
    }

    @Test public void legitimateHeaderNamesAreNotFilteredByContent() {
        for (String name : Arrays.asList("普通昵称", "木.", "123", "88万/142万", "😀")) {
            FriendNameResolver.Resolution r = FriendNameResolver.resolve(Arrays.asList(
                    line(name,198,191,407,233), bar(), home()),1240,2772);
            assertEquals(name, r.text); assertEquals("full", r.source); assertFalse(r.needsAssistance);
        }
    }

    @Test public void assistedTextMustBeInNameRegion() {
        List<OcrLine> full = Arrays.asList(bar(), home());
        FriendNameResolver.Resolution r = FriendNameResolver.resolve(full,
                Collections.singletonList(line("补充昵称",198,191,407,233)),1240,2772);
        assertEquals("补充昵称", r.text); assertEquals("header", r.source); assertFalse(r.needsAssistance);
        r = FriendNameResolver.resolve(full, Collections.singletonList(line("78万",145,235,220,277)),1240,2772);
        assertNull(r.text); assertEquals("unknown", r.source); assertTrue(r.needsAssistance);
        r = FriendNameResolver.resolve(Arrays.asList(home(), line("其他好友",180,2500,400,2540)),
                Collections.singletonList(line("补充昵称",198,191,407,233)),1240,2772);
        assertEquals("补充昵称", r.text); assertEquals("header", r.source);
    }

    @Test public void regionScalesAndClamps() {
        FriendNameResolver.Resolution a = FriendNameResolver.resolve(Arrays.asList(bar(), home()),1240,2772);
        FriendNameResolver.Resolution b = FriendNameResolver.resolve(Arrays.asList(
                line("e169 78万/194万",226,466,810,556),line("回家",2000,4400,2200,4500)),2480,5544);
        assertTrue(a.region.left <= 198 && a.region.right >= 407);
        assertTrue(a.region.top <= 191 && a.region.bottom >= 233);
        assertEquals(a.region.bottom * 2, b.region.bottom);
        assertEquals("昵称", FriendNameResolver.resolve(Arrays.asList(
                line("昵称",396,382,814,466),line("78万/194万",226,466,810,556)),2480,5544).text);
        FriendNameResolver.Region tiny = FriendNameResolver.resolve(Collections.emptyList(),1,1).region;
        assertEquals(0,tiny.left); assertEquals(0,tiny.top); assertEquals(1,tiny.right); assertEquals(1,tiny.bottom);
    }
}
