package top.nkbe.npatch.patch.wrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import top.nkbe.npatch.share.WrapperConfig;

public final class WrapperManifest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Set<String> COMPONENTS = Set.of("application", "activity", "activity-alias",
            "service", "receiver", "provider", "instrumentation");
    private static final Set<String> CLASS_ATTRIBUTES = Set.of("backupAgent", "manageSpaceActivity",
            "appComponentFactory", "zygotePreloadName", "parentActivityName", "targetActivity");
    private final Node root;
    private final List<Namespace> namespaces = new ArrayList<>();
    public final String packageName;
    public final int minSdk;
    public final String appComponentFactory;

    public WrapperManifest(byte[] binaryXml) throws IOException {
        List<Node> roots = new ArrayList<>();
        new AxmlReader(binaryXml).accept(new AxmlVisitor() {
            @Override public void ns(String prefix, String uri, int line) {
                namespaces.add(new Namespace(prefix, uri, line));
            }
            @Override public NodeVisitor child(String ns, String name) {
                Node node = new Node(ns, name);
                roots.add(node);
                return node;
            }
        });
        if (roots.size() != 1 || !"manifest".equals(roots.get(0).name)) {
            throw new IOException("APK must contain one manifest");
        }
        root = roots.get(0);
        packageName = root.string(null, "package", "");
        validatePackage(packageName);
        if (!root.string(null, "split", "").isEmpty()) unsupported("split APK");
        if (root.attribute(ANDROID, "sharedUserId") != null) unsupported("sharedUserId");
        if (root.attribute(null, "requiredSplitTypes") != null) unsupported("required split types");
        List<Node> applications = root.children.stream().filter(n -> n.name.equals("application"))
                .collect(java.util.stream.Collectors.toList());
        if (applications.size() != 1) throw new IOException("APK must contain one application");
        Node app = applications.get(0);
        String factory = app.string(ANDROID, "appComponentFactory", "");
        appComponentFactory = factory.isEmpty() ? null : className(factory, packageName);
        Node sdk = root.children.stream().filter(n -> n.name.equals("uses-sdk")).findFirst().orElse(null);
        Object minimum = sdk == null ? null : sdk.value(ANDROID, "minSdkVersion");
        if (minimum != null && !(minimum instanceof Number)) unsupported("preview or resource minSdkVersion");
        minSdk = Math.max(28, minimum instanceof Number ? ((Number) minimum).intValue() : 1);
        validate(root);
    }

    private void validate(Node node) throws IOException {
        if (Set.of("uses-split", "instrumentation", "overlay", "sdk-library", "static-library").contains(node.name)) {
            unsupported(node.name);
        }
        for (String flag : List.of("isolatedProcess", "useAppZygote", "isSplitRequired", "isFeatureSplit")) {
            if (truth(node.value(ANDROID, flag))) unsupported(flag);
        }
        if (node.attribute(ANDROID, "splitName") != null || node.attribute(ANDROID, "requiredSplitTypes") != null) {
            unsupported("split component or required split types");
        }
        if (node.name.equals("meta-data")) {
            String name = node.string(ANDROID, "name", "");
            if (name.equals(WrapperConfig.MARKER) || name.equals("npatch") || name.equals("lspatch") || name.equals("fpa")) {
                unsupported("already wrapped APK; select its original APK");
            }
            if (name.equals("com.android.vending.splits.required") && truth(node.value(ANDROID, "value"))) {
                unsupported("required split APKs");
            }
        }
        for (Attr attr : node.attributes) {
            if (!ANDROID.equals(attr.ns)) continue;
            if ((COMPONENTS.contains(node.name) && attr.name.equals("name"))
                    || CLASS_ATTRIBUTES.contains(attr.name) || attr.name.equals("authorities")) {
                if (!(attr.value instanceof String)) unsupported("non-literal " + attr.name);
            }
        }
        for (Node child : node.children) validate(child);
    }

    public byte[] rewrite(String wrapperPackage) throws IOException {
        return rewrite(wrapperPackage, null);
    }

    public byte[] rewrite(String wrapperPackage, String npatchMetadata) throws IOException {
        validatePackage(wrapperPackage);
        Map<String, String> permissions = new HashMap<>();
        for (Node child : root.children) {
            if (child.name.equals("permission") || child.name.equals("permission-group") || child.name.equals("permission-tree")) {
                String name = child.string(ANDROID, "name", "");
                if (name.isEmpty()) throw new IOException("Unnamed permission declaration");
                permissions.put(name, remap(name, wrapperPackage, "permission"));
            }
        }
        AxmlWriter writer = new AxmlWriter();
        for (Namespace ns : namespaces) writer.ns(ns.prefix, ns.uri, ns.line);
        if (namespaces.stream().noneMatch(ns -> ANDROID.equals(ns.uri))) writer.ns("android", ANDROID, 0);
        write(root, writer, wrapperPackage, permissions, npatchMetadata);
        return writer.toByteArray();
    }

    private void write(Node node, NodeVisitor parent, String target, Map<String, String> permissions, String npatchMetadata) throws IOException {
        NodeVisitor output = parent.child(node.ns, node.name);
        output.line(node.line);
        boolean manifest = node.name.equals("manifest");
        boolean application = node.name.equals("application");
        boolean sdk = node.name.equals("uses-sdk");
        for (Attr attr : node.attributes) {
            Object value = attr.value;
            if (manifest && attr.ns == null && attr.name.equals("package")) value = target;
            if (ANDROID.equals(attr.ns)) {
                if (sdk && attr.name.equals("minSdkVersion")) continue;
                if (application && Set.of("appComponentFactory", "hasCode", "extractNativeLibs").contains(attr.name)) continue;
                if (value instanceof String text) {
                    if ((COMPONENTS.contains(node.name) && attr.name.equals("name")) || CLASS_ATTRIBUTES.contains(attr.name)) {
                        value = className(text, packageName);
                    } else if (node.name.equals("provider") && attr.name.equals("authorities")) {
                        List<String> authorities = new ArrayList<>();
                        for (String authority : text.split(";", -1)) {
                            if (authority.trim().isEmpty()) throw new IOException("Invalid empty provider authority");
                            authorities.add(remap(authority, target, "provider"));
                        }
                        value = String.join(";", authorities);
                    } else if (Set.of("permission", "readPermission", "writePermission", "permissionGroup").contains(attr.name)
                            || (attr.name.equals("name") && (node.name.startsWith("permission") || node.name.startsWith("uses-permission")))) {
                        value = permissions.getOrDefault(text, text);
                    } else if ((attr.name.equals("taskAffinity") || attr.name.equals("process"))
                            && !text.isEmpty() && !text.startsWith(":")) {
                        value = remap(text, target, attr.name);
                    }
                }
            }
            output.attr(attr.ns, attr.name, attr.id, attr.type, value);
        }
        if (sdk) output.attr(ANDROID, "minSdkVersion", 0x0101020c, TYPE_INT, minSdk);
        if (application) {
            output.attr(ANDROID, "appComponentFactory", 0x0101057a, NodeVisitor.TYPE_STRING, WrapperConfig.FACTORY);
            output.attr(ANDROID, "hasCode", 0x0101000c, NodeVisitor.TYPE_INT_BOOLEAN, true);
            output.attr(ANDROID, "extractNativeLibs", 0x010104ea, NodeVisitor.TYPE_INT_BOOLEAN, true);
            NodeVisitor marker = output.child(null, "meta-data");
            marker.attr(ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, WrapperConfig.MARKER);
            marker.attr(ANDROID, "value", 0x01010024, TYPE_INT, WrapperConfig.FORMAT_VERSION);
            marker.end();
            NodeVisitor origin = output.child(null, "meta-data");
            origin.attr(ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, WrapperConfig.ORIGINAL_PACKAGE_MARKER);
            origin.attr(ANDROID, "value", 0x01010024, NodeVisitor.TYPE_STRING, packageName);
            origin.end();
            if (npatchMetadata != null) {
                NodeVisitor npatch = output.child(null, "meta-data");
                npatch.attr(ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, "npatch");
                npatch.attr(ANDROID, "value", 0x01010024, NodeVisitor.TYPE_STRING, npatchMetadata);
                npatch.end();
            }
        }
        if (manifest && root.children.stream().noneMatch(n -> n.name.equals("uses-sdk"))) {
            NodeVisitor added = output.child(null, "uses-sdk");
            added.attr(ANDROID, "minSdkVersion", 0x0101020c, TYPE_INT, minSdk);
            added.end();
        }
        for (Node child : node.children) write(child, output, target, permissions, npatchMetadata);
        for (Text text : node.texts) output.text(text.line, text.value);
        output.end();
    }

    private static final int TYPE_INT = NodeVisitor.TYPE_FIRST_INT;

    public static void validatePackage(String name) throws IOException {
        if (name == null || name.length() > 223 || !name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IOException("Invalid Android package name: " + name);
        }
    }

    private String remap(String name, String target, String kind) {
        if (target.equals(packageName)) return name;
        if (name.equals(packageName)) return target;
        if (name.startsWith(packageName + ".")) return target + name.substring(packageName.length());
        return target + "." + kind + "." + digest(name);
    }

    private static String digest(String name) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("id");
            for (int i = 0; i < 16; i++) result.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static String className(String value, String original) {
        if (value.isEmpty()) return value;
        if (value.startsWith(".")) return original + value;
        return value.contains(".") ? value : original + "." + value;
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }

    private static void unsupported(String reason) throws IOException {
        throw new IOException("Unsupported by the standalone loader: " + reason);
    }

    private record Namespace(String prefix, String uri, int line) {}
    private record Attr(String ns, String name, int id, int type, Object value) {}
    private record Text(int line, String value) {}

    private static final class Node extends NodeVisitor {
        final String ns;
        final String name;
        int line;
        final List<Attr> attributes = new ArrayList<>();
        final List<Node> children = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();
        Node(String ns, String name) { this.ns = ns; this.name = name; }
        @Override public void attr(String ns, String name, int id, int type, Object value) {
            attributes.add(new Attr(ns, name, id, type, value));
        }
        @Override public NodeVisitor child(String ns, String name) {
            Node node = new Node(ns, name);
            children.add(node);
            return node;
        }
        @Override public void line(int line) { this.line = line; }
        @Override public void text(int line, String value) { texts.add(new Text(line, value)); }
        Attr attribute(String ns, String name) {
            return attributes.stream().filter(a -> java.util.Objects.equals(a.ns, ns) && a.name.equals(name)).findFirst().orElse(null);
        }
        Object value(String ns, String name) {
            Attr attr = attribute(ns, name);
            return attr == null ? null : attr.value;
        }
        String string(String ns, String name, String fallback) throws IOException {
            Object value = value(ns, name);
            if (value == null) return fallback;
            if (!(value instanceof String)) throw new IOException("Expected literal " + name);
            return (String) value;
        }
    }
}
