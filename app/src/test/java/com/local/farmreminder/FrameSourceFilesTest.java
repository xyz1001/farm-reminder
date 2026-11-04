package com.local.farmreminder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Properties;
import static org.junit.Assert.*;

public class FrameSourceFilesTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root, dir, image, manifest;
    private Properties p;
    private void setup() throws Exception {
        root = temp.newFolder("frames-v1").getCanonicalFile(); dir = new File(root, ID); assertTrue(dir.mkdir());
        image = new File(dir, "image.png"); Files.write(image.toPath(), new byte[]{1, 2, 3, 4});
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image.toPath()));
        StringBuilder hex = new StringBuilder(); for (byte b : hash) hex.append(String.format("%02x", b & 255));
        p = new Properties(); p.setProperty("schemaVersion", "1"); p.setProperty("frameId", ID);
        p.setProperty("captureSessionId", ID); p.setProperty("enqueueSequence", "1"); p.setProperty("captureSequence", "1");
        p.setProperty("captureWallMs", "1"); p.setProperty("captureUptimeMs", "1"); p.setProperty("windowId", "-1");
        p.setProperty("captureMethod", "test"); p.setProperty("width", "100"); p.setProperty("height", "200");
        p.setProperty("imageBytes", "4"); p.setProperty("digest", hex.toString()); p.setProperty("state", "COMPLETED"); p.setProperty("attempts", "1");
        manifest = new File(dir, "manifest.properties"); save();
    }
    private void save() throws Exception { try (FileOutputStream out = new FileOutputStream(manifest)) { p.store(out, null); } }
    @Test public void canonicalUuidOnly() {
        assertEquals(ID, SourceFrameRefPolicy.normalize(ID));
        for (String invalid : new String[]{null, "", "../" + ID, ID.toUpperCase(), "1-1-1-1-1", ID + "/image.png"})
            assertEquals("", SourceFrameRefPolicy.normalize(invalid));
    }
    @Test public void validExactManifestAndInitialFallback() throws Exception {
        setup(); assertEquals(image, FrameSourceFiles.resolve(root, ID));
        Files.move(manifest.toPath(), new File(dir, "initial.properties").toPath());
        assertEquals(image, FrameSourceFiles.resolve(root, ID));
        Files.write(manifest.toPath(), new byte[]{1}); // Bad present manifest must not fall back.
        assertNull(FrameSourceFiles.resolve(root, ID));
    }
    @Test public void mismatchUnknownMetadataAndDimensionsFailSoft() throws Exception {
        setup(); p.setProperty("frameId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("frameId", ID); p.setProperty("schemaVersion", "2"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("schemaVersion", "1"); p.setProperty("imageFileName", "../image.png"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("imageFileName", "image.gif"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("imageFileName", "image.png"); p.setProperty("width", "0"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("width", "16385"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("width", "100"); p.remove("captureSequence"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        assertNull(FrameSourceFiles.resolve(root, "../" + ID));
    }
    @Test public void badLengthDigestAndMissingImageFailSoft() throws Exception {
        setup(); p.setProperty("imageBytes", "67108865"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("imageBytes", "3"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("imageBytes", "4"); p.setProperty("digest", "garbage"); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        p.setProperty("digest", "0".repeat(64)); save(); assertNull(FrameSourceFiles.resolve(root, ID));
        assertTrue(image.delete()); assertNull(FrameSourceFiles.resolve(root, ID));
        assertNull(FrameSourceFiles.resolve(new File(root, "missing"), ID));
    }
    @Test public void symlinkedImageAndDirectoryAreRejectedWhereSupported() throws Exception {
        setup(); File external = temp.newFile("external"); Files.write(external.toPath(), new byte[]{1, 2, 3, 4});
        assertTrue(image.delete());
        try { Files.createSymbolicLink(image.toPath(), external.toPath()); }
        catch (Exception e) { Assume.assumeNoException("symlink creation unavailable", e); }
        assertNull(FrameSourceFiles.resolve(root, ID));
        Files.delete(image.toPath());
        Files.write(image.toPath(), new byte[]{1, 2, 3, 4});
        File metadataCopy = temp.newFile("metadata-copy"); Files.copy(manifest.toPath(), metadataCopy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(manifest.toPath()); Files.createSymbolicLink(manifest.toPath(), metadataCopy.toPath());
        assertNull(FrameSourceFiles.resolve(root, ID));
        Files.delete(manifest.toPath()); Files.copy(metadataCopy.toPath(), manifest.toPath());
        File rootLink = new File(temp.getRoot(), "root-link"); Files.createSymbolicLink(rootLink.toPath(), root.toPath());
        assertNull(FrameSourceFiles.resolve(rootLink, ID));
        File initialLink = new File(dir, "initial.properties"); Files.createSymbolicLink(initialLink.toPath(), metadataCopy.toPath());
        assertNull(FrameSourceFiles.resolve(root, ID)); Files.delete(initialLink.toPath());
        File aliasRoot = temp.newFolder("alias-root");
        try { Files.createSymbolicLink(new File(aliasRoot, ID).toPath(), dir.toPath()); }
        catch (Exception e) { Assume.assumeNoException("symlink creation unavailable", e); }
        assertNull(FrameSourceFiles.resolve(aliasRoot.getCanonicalFile(), ID));
    }
}
