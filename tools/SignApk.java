import com.android.apksig.ApkSigner;
import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

/**
 * apksig Maven 包不含 CLI 主类，这里直接调 ApkSigner API（v1+v2）。
 * 用法: java -cp apksig.jar:. SignApk <ks> <ksPass> <alias> <keyPass> <in.apk> <out.apk>
 */
public class SignApk {
    public static void main(String[] args) throws Exception {
        String ksPath = args[0], ksPass = args[1], alias = args[2], keyPass = args[3];
        String in = args[4], out = args[5];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] sp = ksPass.toCharArray();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(ksPath)) {
            ks.load(fis, sp);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
        List<X509Certificate> chain = new java.util.ArrayList<X509Certificate>();
        for (java.security.cert.Certificate c : ks.getCertificateChain(alias)) {
            chain.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(alias, key, chain).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();
        signer.sign();
        System.out.println("signed -> " + out);
    }
}
