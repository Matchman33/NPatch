package top.nkbe.npatch.loader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResourcePackageCompatTest {
    @Test public void mapsOriginalDefaultPackageToRenamedPackage() {
        assertEquals("example.renamed", ResourcePackageCompat.mapDefaultPackage(
                "example.original", "example.original", "example.renamed"));
        assertEquals("example.renamed", ResourcePackageCompat.mapDefaultPackage(
                "example.renamed", "example.original", "example.renamed"));
        assertEquals("android", ResourcePackageCompat.mapDefaultPackage(
                "android", "example.original", "example.renamed"));
    }

    @Test public void mapsOnlyFullyQualifiedOriginalResourceNames() {
        assertEquals("example.renamed:layout/smoke", ResourcePackageCompat.mapResourceName(
                "example.original:layout/smoke", "example.original", "example.renamed"));
        assertEquals("example.renamed:layout/smoke", ResourcePackageCompat.mapResourceName(
                "example.renamed:layout/smoke", "example.original", "example.renamed"));
        assertEquals("layout/smoke", ResourcePackageCompat.mapResourceName(
                "layout/smoke", "example.original", "example.renamed"));
        assertEquals("android:string/ok", ResourcePackageCompat.mapResourceName(
                "android:string/ok", "example.original", "example.renamed"));
    }
}
