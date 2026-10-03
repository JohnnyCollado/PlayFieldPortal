import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline signing tool for the official emulator knowledge file (plan AD-7, Appendix A).
 *
 * Single file, JDK 17, no dependencies:
 *
 *   java KbSign.java keygen --out <dir>
 *   java KbSign.java sign   --key <pem> --in emulators.json --out emulators.json.sig
 *   java KbSign.java verify --pub <base64> --in emulators.json --sig emulators.json.sig
 *
 * The signature is a detached Ed25519 signature over the exact file bytes, written as base64 of the
 * 64 raw bytes. The public key is the raw 32 bytes in base64, which is the form pinned in the app.
 * Exit code 0 means success; verify exits 1 on a bad signature and 2 on any usage or I/O error.
 */
public final class KbSign {

    private static final String PRIVATE_KEY_NAME = "pfp-kb.private.pem";
    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    /** X.509 SubjectPublicKeyInfo header for Ed25519, followed by the raw 32-byte key. */
    private static final byte[] SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private KbSign() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (UsageException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) throw new UsageException("missing subcommand");
        Map<String, String> opts = parseOptions(args);
        switch (args[0]) {
            case "keygen":
                return keygen(require(opts, "out"));
            case "sign":
                return sign(require(opts, "key"), require(opts, "in"), require(opts, "out"));
            case "verify":
                return verify(require(opts, "pub"), require(opts, "in"), require(opts, "sig"));
            default:
                throw new UsageException("unknown subcommand " + args[0]);
        }
    }

    private static int keygen(String outDir) throws Exception {
        Path dir = Paths.get(outDir).toAbsolutePath().normalize();
        Path worktree = enclosingWorktree(dir);
        if (worktree != null) {
            throw new IOException("refusing to write a private key inside the git worktree "
                + worktree + ". Choose a directory outside any repository.");
        }
        Files.createDirectories(dir);
        // Resolve symlinks now that the directory exists and check again.
        Path real = dir.toRealPath();
        Path realWorktree = enclosingWorktree(real);
        if (realWorktree != null) {
            throw new IOException("refusing to write a private key inside the git worktree "
                + realWorktree + ".");
        }
        Path keyFile = real.resolve(PRIVATE_KEY_NAME);
        if (Files.exists(keyFile)) {
            throw new IOException(keyFile + " already exists; refusing to overwrite a key.");
        }

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(pair.getPrivate().getEncoded());
        String pem = PEM_BEGIN + "\n" + body + "\n" + PEM_END + "\n";
        Files.write(keyFile, pem.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
        restrictToOwner(keyFile);

        byte[] spki = pair.getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(spki, SPKI_PREFIX.length, spki.length);
        System.err.println("Private key written to: " + keyFile);
        System.err.println("Keep it offline and backed up. Never commit, paste or upload it.");
        System.err.println("Public key (base64, raw 32 bytes) for KbSignatureVerifier.PINNED_KEYS:");
        System.out.println(Base64.getEncoder().encodeToString(raw));
        return 0;
    }

    private static int sign(String keyPath, String inPath, String outPath) throws Exception {
        PrivateKey key = readPrivateKey(Paths.get(keyPath));
        byte[] data = Files.readAllBytes(Paths.get(inPath));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(data);
        String sig = Base64.getEncoder().encodeToString(s.sign());
        Files.write(Paths.get(outPath), (sig + "\n").getBytes(StandardCharsets.US_ASCII));
        System.err.println("Signed " + inPath + " -> " + outPath);
        return 0;
    }

    private static int verify(String pubBase64, String inPath, String sigPath) throws Exception {
        byte[] raw = Base64.getDecoder().decode(pubBase64.trim());
        if (raw.length != 32) throw new IOException("public key must be 32 raw bytes, got " + raw.length);
        byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
        PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));

        byte[] data = Files.readAllBytes(Paths.get(inPath));
        byte[] sig;
        try {
            String text = new String(Files.readAllBytes(Paths.get(sigPath)), StandardCharsets.US_ASCII);
            sig = Base64.getDecoder().decode(text.trim());
        } catch (IllegalArgumentException e) {
            System.out.println("FAILED: signature file is not valid base64");
            return 1;
        }
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(pub);
        s.update(data);
        boolean ok = sig.length == 64 && s.verify(sig);
        System.out.println(ok ? "OK" : "FAILED: signature does not match");
        return ok ? 0 : 1;
    }

    private static PrivateKey readPrivateKey(Path pem) throws Exception {
        String text = new String(Files.readAllBytes(pem), StandardCharsets.US_ASCII);
        int start = text.indexOf(PEM_BEGIN);
        int end = text.indexOf(PEM_END);
        if (start < 0 || end < start) throw new IOException(pem + " is not a PKCS#8 PEM private key");
        String b64 = text.substring(start + PEM_BEGIN.length(), end).replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** The nearest ancestor (or the path itself) holding a .git entry, or null when none does. */
    private static Path enclosingWorktree(Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) return p;
        }
        return null;
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (Windows): rely on the user profile's directory ACLs.
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new UsageException("expected --name value pairs, got " + args[i]);
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null) throw new UsageException("missing --" + name);
        return v;
    }

    private static String usage() {
        return "usage:\n"
            + "  java KbSign.java keygen --out <dir outside any git repo>\n"
            + "  java KbSign.java sign   --key <pem> --in <file> --out <sig file>\n"
            + "  java KbSign.java verify --pub <base64 raw public key> --in <file> --sig <sig file>";
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
