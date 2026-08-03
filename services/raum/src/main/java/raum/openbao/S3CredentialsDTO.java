package raum.openbao;

public record S3CredentialsDTO(String endpoint, String bucket, String accessKey, String secretKey) {
}
