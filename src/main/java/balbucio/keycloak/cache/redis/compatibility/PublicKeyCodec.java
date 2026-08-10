package balbucio.keycloak.cache.redis.compatibility;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.keycloak.crypto.KeyStatus;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.crypto.PublicKeysWrapper;

/** Encode/decode public keys for Redis JSON without DefaultTyping. */
public final class PublicKeyCodec {

    private PublicKeyCodec() {}

    public static CachedPublicKeys toCached(PublicKeysWrapper wrapper) {
        CachedPublicKeys cached = new CachedPublicKeys();
        if (wrapper == null) {
            return cached;
        }
        cached.setExpirationTime(wrapper.getExpirationTime());
        List<CachedPublicKey> keys = new ArrayList<>();
        if (wrapper.getKeys() != null) {
            for (KeyWrapper key : wrapper.getKeys()) {
                if (key != null) {
                    keys.add(toCachedKey(key));
                }
            }
        }
        cached.setKeys(keys);
        return cached;
    }

    public static PublicKeysWrapper fromCached(CachedPublicKeys cached) {
        if (cached == null || cached.getKeys() == null || cached.getKeys().isEmpty()) {
            return PublicKeysWrapper.EMPTY;
        }
        List<KeyWrapper> keys = new ArrayList<>();
        for (CachedPublicKey ck : cached.getKeys()) {
            KeyWrapper key = fromCachedKey(ck);
            if (key != null) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return PublicKeysWrapper.EMPTY;
        }
        if (cached.getExpirationTime() != null) {
            return new PublicKeysWrapper(keys, cached.getExpirationTime());
        }
        return new PublicKeysWrapper(keys);
    }

    static CachedPublicKey toCachedKey(KeyWrapper key) {
        CachedPublicKey cached = new CachedPublicKey();
        cached.setProviderId(key.getProviderId());
        cached.setProviderPriority(key.getProviderPriority());
        cached.setKid(key.getKid());
        cached.setAlgorithm(key.getAlgorithm());
        cached.setType(key.getType());
        cached.setUse(key.getUse() != null ? key.getUse().name() : null);
        cached.setStatus(key.getStatus() != null ? key.getStatus().name() : null);
        cached.setCurve(key.getCurve());
        if (key.getPublicKey() != null) {
            cached.setPublicKeyBase64(Base64.getEncoder().encodeToString(key.getPublicKey().getEncoded()));
        }
        if (key.getCertificate() != null) {
            try {
                cached.setCertificateBase64(Base64.getEncoder().encodeToString(key.getCertificate().getEncoded()));
            } catch (Exception ignored) {
                // skip unencodable cert
            }
        }
        if (key.getCertificateChain() != null && !key.getCertificateChain().isEmpty()) {
            List<String> chain = new ArrayList<>();
            for (X509Certificate cert : key.getCertificateChain()) {
                try {
                    chain.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
                } catch (Exception ignored) {
                    // skip
                }
            }
            cached.setCertificateChainBase64(chain);
        }
        return cached;
    }

    static KeyWrapper fromCachedKey(CachedPublicKey cached) {
        if (cached == null) {
            return null;
        }
        KeyWrapper key = new KeyWrapper();
        key.setProviderId(cached.getProviderId());
        key.setProviderPriority(cached.getProviderPriority());
        key.setKid(cached.getKid());
        key.setAlgorithm(cached.getAlgorithm());
        key.setType(cached.getType());
        if (cached.getUse() != null) {
            key.setUse(KeyUse.valueOf(cached.getUse()));
        }
        if (cached.getStatus() != null) {
            key.setStatus(KeyStatus.valueOf(cached.getStatus()));
        }
        key.setCurve(cached.getCurve());
        if (cached.getPublicKeyBase64() != null && cached.getType() != null) {
            try {
                byte[] encoded = Base64.getDecoder().decode(cached.getPublicKeyBase64());
                PublicKey publicKey =
                        KeyFactory.getInstance(cached.getType())
                                .generatePublic(new X509EncodedKeySpec(encoded));
                key.setPublicKey(publicKey);
            } catch (Exception e) {
                return null;
            }
        }
        CertificateFactory cf = null;
        try {
            cf = CertificateFactory.getInstance("X.509");
        } catch (Exception ignored) {
            // optional
        }
        if (cf != null && cached.getCertificateBase64() != null) {
            try {
                byte[] der = Base64.getDecoder().decode(cached.getCertificateBase64());
                key.setCertificate(
                        (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            } catch (Exception ignored) {
                // optional
            }
        }
        if (cf != null
                && cached.getCertificateChainBase64() != null
                && !cached.getCertificateChainBase64().isEmpty()) {
            List<X509Certificate> chain = new ArrayList<>();
            for (String b64 : cached.getCertificateChainBase64()) {
                try {
                    byte[] der = Base64.getDecoder().decode(b64);
                    chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
                } catch (Exception ignored) {
                    // skip
                }
            }
            key.setCertificateChain(chain);
        }
        return key;
    }
}
