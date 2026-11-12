import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class SignApk {
    public static void main(String[] args) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        String keyPassword = System.getenv("FLYME_PICKER_KEY_PASSWORD");
        if (keyPassword == null || keyPassword.isEmpty()) {
            throw new IllegalArgumentException("Set FLYME_PICKER_KEY_PASSWORD to sign the APK");
        }
        char[] password = keyPassword.toCharArray();
        try (FileInputStream input = new FileInputStream(args[0])) {
            keyStore.load(input, password);
        }
        PrivateKey key = (PrivateKey) keyStore.getKey("probe", password);
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("probe");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
            "probe", key, Collections.singletonList(certificate)).build();

        File output = new File(args[3]);
        File unsigned = File.createTempFile("unsigned-", ".apk");
        try {
            try (ZipOutputStream apk = new ZipOutputStream(Files.newOutputStream(unsigned.toPath()))) {
                // 30-byte ZIP header + 14-byte entry name puts this first entry on a 4-byte boundary.
                try (ZipFile manifest = new ZipFile(args[1])) {
                    ZipEntry resources = manifest.getEntry("resources.arsc");
                    if (resources != null) {
                        copyEntry(manifest, resources, apk, ZipEntry.STORED);
                    }
                }
                addEntries(new File(args[1]), apk);
                addEntries(new File(args[2]), apk);
            }
            new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build().sign();
        } finally {
            Files.deleteIfExists(unsigned.toPath());
        }
        ApkVerifier.Result verification = new ApkVerifier.Builder(output).build().verify();
        if (!verification.isVerified()) {
            throw new IllegalStateException(verification.getAllErrors().toString());
        }
        System.out.println("Verified APK: " + output + " (v2=" + verification.isVerifiedUsingV2Scheme()
            + ", v3=" + verification.isVerifiedUsingV3Scheme() + ")");
    }

    private static void addEntries(File file, ZipOutputStream output) throws IOException {
        try (ZipFile input = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().equals("resources.arsc")) {
                    copyEntry(input, entry, output, entry.getMethod());
                }
            }
        }
    }

    private static void copyEntry(ZipFile input, ZipEntry entry, ZipOutputStream output, int method)
            throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setMethod(method);
        if (method == ZipEntry.STORED) {
            copy.setSize(entry.getSize());
            copy.setCrc(entry.getCrc());
        }
        output.putNextEntry(copy);
        try (InputStream stream = input.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.closeEntry();
    }
}
