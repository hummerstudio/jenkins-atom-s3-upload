package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The s3-upload atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: a fake {@code aws} records the argv and the environment, so the assertions
 * are about the exact command line and about credentials travelling in the environment rather than in argv — plus the
 * endpoint pass-through, the workspace guard and a missing CLI.
 */
class S3UploadIT {

    /** Records what it was given in the run's working directory, then says what it would have uploaded. */
    private static final String FAKE_AWS = """
            #!/usr/bin/env bash
            printf '%s\\n' "$*" >> argv.txt
            { echo "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-unset}"
              echo "AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-unset}"; } >> env.txt
            echo "upload: ./dist/app.jar to s3://bucket/prefix/app.jar"
            exit 0
            """;

    private static final String SECRETS =
            "{\"awsAccessKeyId\": \"AKIA-SMOKE\", \"awsSecretAccessKey\": \"smoke-secret\"}";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of().bin("aws", FAKE_AWS).file("dist/app.jar", "jar\n");
    }

    @Test
    @DisplayName("the upload: the exact argv, the uri output, and the key in the environment not in argv")
    void uploadsWithCredentialsInTheEnvironment() throws Exception {
        AtomResult run = atom.file(".secrets.json", SECRETS)
                .input("bucket", "my-bucket")
                .input("source", "dist")
                .input("prefix", "builds/42")
                .input("region", "eu-west-1")
                .run();

        run.assertOk();
        assertEquals("success", run.status(), run.console());
        assertEquals("s3://my-bucket/builds/42", run.field("uri"));

        String argv = run.file("argv.txt");
        assertTrue(argv != null && argv.matches("(?s).*s3 cp .*dist s3://my-bucket/builds/42 --recursive --region "
                + "eu-west-1.*"), "argv: " + argv);
        assertTrue(run.file("env.txt").contains("AKIA-SMOKE"),
                "the key must reach the CLI through the environment: " + run.file("env.txt"));
        assertFalse(argv.contains("AKIA-SMOKE"), "the key must not be in argv: " + argv);
        assertFalse(run.console().contains("smoke-secret"), "the secret must not be in the log:\n" + run.console());
    }

    @Test
    @DisplayName("a custom endpoint is passed through")
    void passesTheEndpointThrough() throws Exception {
        AtomResult run = atom.file("dist/x.bin", "x\n")
                .input("bucket", "b")
                .input("source", "dist")
                .input("endpointUrl", "http://127.0.0.1:9000")
                .run();

        run.assertOk();
        assertTrue(run.file("argv.txt").contains("--endpoint-url http://127.0.0.1:9000"),
                "endpoint not passed: " + run.file("argv.txt"));
    }

    @Test
    @DisplayName("a source outside the workspace is refused")
    void refusesASourceOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.input("bucket", "b").input("source", "../etc").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("inside the workspace"),
                "message not readable:\n" + run.document());
    }

    @Test
    @DisplayName("no aws CLI is an environment error")
    void reportsAMissingCliAsAnEnvironmentError() throws Exception {
        AtomResult run = atom.file("dist/x.bin", "x\n")
                .input("bucket", "b")
                .input("source", "dist")
                .env("PATH", "/nonexistent-atom-path:/usr/bin:/bin")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("\"errorType\":5"),
                "ENVIRONMENT(5) expected:\n" + run.document());
    }
}
