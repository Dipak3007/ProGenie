package com.progenie.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** AWS Signature V4, checked against AWS's documented examples and against botocore (the AWS Python SDK). */
class SigV4Test {

    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void signsAGetRequestLikeTheAwsExample() {
        // "Example: GET Object" in the AWS S3 docs (Signature Calculations for the Authorization Header)
        SigV4 signer = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        Map<String, String> headers = signer.sign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
            Map.of("range", "bytes=0-9"), EMPTY_SHA, Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(headers.get("authorization")).isEqualTo("AWS4-HMAC-SHA256 "
            + "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
            + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
            + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        assertThat(headers).containsEntry("x-amz-date", "20130524T000000Z").doesNotContainKey("host");
    }

    @Test
    void presignsLikeTheAwsExample() {
        // "Example: Presigned URL" in the AWS S3 docs (Query Parameters authentication)
        SigV4 signer = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        URI url = signer.presignGet(URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), Map.of(),
            Duration.ofDays(1), Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(url.toString()).endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")
            .contains("X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request");
    }

    @Test
    void presignsAPathStyleSupabaseUrlLikeBotocore() {
        // botocore 1.x generate_presigned_url('get_object', ...) with path addressing, same inputs and time
        SigV4 signer = new SigV4("AKIDTEST", "secret/KEY+123", "ap-south-1");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response-content-disposition", "attachment; filename=\"aadhaar.pdf\"");
        params.put("response-content-type", "application/pdf");
        URI object = URI.create("https://abc.supabase.co/storage/v1/s3/progenie/" + SigV4.encodePath("kyc/g1/doc 1.pdf"));
        URI url = signer.presignGet(object, params, Duration.ofMinutes(5), Instant.parse("2026-10-25T08:33:29Z"));
        assertThat(url.toString())
            .startsWith("https://abc.supabase.co/storage/v1/s3/progenie/kyc/g1/doc%201.pdf?")
            .contains("response-content-disposition=attachment%3B%20filename%3D%22aadhaar.pdf%22")
            .endsWith("X-Amz-Signature=89829efde548e3c95cbf24657fbdfb2617ffc8bcd4dbc1323497d0150690cfb8");
    }

    @Test
    void encodesLikeAws() {
        assertThat(SigV4.encode("a b/c~d_e.f-g")).isEqualTo("a%20b%2Fc~d_e.f-g");
        assertThat(SigV4.encodePath("kyc/x y/€.pdf")).isEqualTo("kyc/x%20y/%E2%82%AC.pdf");
        assertThat(SigV4.hostHeader(URI.create("http://localhost:8333/b/k"))).isEqualTo("localhost:8333");
        assertThat(SigV4.hostHeader(URI.create("https://abc.supabase.co:443/x"))).isEqualTo("abc.supabase.co");
    }
}
