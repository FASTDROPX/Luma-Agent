package dev.luma.agent;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
public final class TestKeys {
 private static Provider original;
 public static void install() {
  original=Security.getProvider("AndroidKeyStore"); Security.removeProvider("AndroidKeyStore");
  Provider p=new Provider("AndroidKeyStore",1.0,"Isolated test keys"){};
  p.put("KeyStore.AndroidKeyStore",TestKeyStore.class.getName());p.put("KeyGenerator.AES",TestGenerator.class.getName());
  Security.addProvider(p);TestKeyStore.keys.clear();
 }
 public static void restore() { Security.removeProvider("AndroidKeyStore");if(original!=null)Security.addProvider(original); }
    public static final class TestGenerator extends KeyGeneratorSpi {
        protected void engineInit(SecureRandom random) { }
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) { }
        protected void engineInit(int keysize, SecureRandom random) { }
        protected SecretKey engineGenerateKey() {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            SecretKey key = new SecretKeySpec(bytes, "AES");
            TestKeyStore.keys.put("luma.vault.v1", key); return key;
        }
    }
    public static final class TestKeyStore extends KeyStoreSpi {
        static final Map<String, Key> keys = new HashMap<>();
        public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
        public Certificate[] engineGetCertificateChain(String alias) { return null; }
        public Certificate engineGetCertificate(String alias) { return null; }
        public Date engineGetCreationDate(String alias) { return null; }
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { keys.put(alias, key); }
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
        public void engineSetCertificateEntry(String alias, Certificate cert) { throw new UnsupportedOperationException(); }
        public void engineDeleteEntry(String alias) { keys.remove(alias); }
        public Enumeration<String> engineAliases() { return Collections.enumeration(keys.keySet()); }
        public boolean engineContainsAlias(String alias) { return keys.containsKey(alias); }
        public int engineSize() { return keys.size(); }
        public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
        public boolean engineIsCertificateEntry(String alias) { return false; }
        public String engineGetCertificateAlias(Certificate cert) { return null; }
        public void engineStore(OutputStream stream, char[] password) { }
        public void engineLoad(InputStream stream, char[] password) { }
    }
}
