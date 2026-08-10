package balbucio.keycloak.cache.redis.compatibility;

/**
 * Serializable snapshot of a public {@link org.keycloak.crypto.KeyWrapper} for Redis L2.
 * Private/secret material is never stored.
 */
public class CachedPublicKey {

    private String providerId;
    private long providerPriority;
    private String kid;
    private String algorithm;
    private String type;
    private String use;
    private String status;
    private String curve;
    private String publicKeyBase64;
    private String certificateBase64;
    private java.util.List<String> certificateChainBase64;

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public long getProviderPriority() {
        return providerPriority;
    }

    public void setProviderPriority(long providerPriority) {
        this.providerPriority = providerPriority;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUse() {
        return use;
    }

    public void setUse(String use) {
        this.use = use;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurve() {
        return curve;
    }

    public void setCurve(String curve) {
        this.curve = curve;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public void setPublicKeyBase64(String publicKeyBase64) {
        this.publicKeyBase64 = publicKeyBase64;
    }

    public String getCertificateBase64() {
        return certificateBase64;
    }

    public void setCertificateBase64(String certificateBase64) {
        this.certificateBase64 = certificateBase64;
    }

    public java.util.List<String> getCertificateChainBase64() {
        return certificateChainBase64;
    }

    public void setCertificateChainBase64(java.util.List<String> certificateChainBase64) {
        this.certificateChainBase64 = certificateChainBase64;
    }
}
