package com.jenkinschina.atom.s3upload;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * s3 as an atom: upload a directory to a bucket with the AWS CLI.
 *
 * <p>The CLI is the tool that is actually installed on build agents, and it already speaks every S3 flavour (MinIO,
 * R2, OSS via endpoint-url). Credentials travel in the environment — argv and the log see only the bucket.
 */
public class S3UploadAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String bucket = context.getParam("bucket", "");
        String source = context.getParam("source", "");
        String prefix = context.getParam("prefix", "");
        String region = context.getParam("region", "");
        String endpointUrl = context.getParam("endpointUrl", "");
        String profile = context.getParam("profile", "");
        boolean recursive = context.getBooleanParam("recursive", true);
        boolean delete = context.getBooleanParam("delete", false);

        if (bucket.isBlank() || source.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "bucket and source are required");
        }
        Path sourcePath = workspace.resolve(source).normalize();
        if (!sourcePath.startsWith(workspace) || !Files.exists(sourcePath)) {
            return AtomResult.error(ErrorType.USER, 40002, "source must exist inside the workspace: " + source);
        }

        String aws = resolve("aws");
        if (aws == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40003,
                    "the aws CLI is not on the PATH of this agent; install awscli or use another uploader");
        }

        String key = prefix.isBlank()
                ? bucket
                : bucket + "/" + prefix.replaceAll("^/+|/+$", "");
        List<String> command = new ArrayList<>(List.of(aws, "s3", "cp", sourcePath.toString(), "s3://" + key));
        if (recursive) {
            command.add("--recursive");
        }
        if (delete) {
            command.add("--delete");
        }
        if (!region.isBlank()) {
            command.add("--region");
            command.add(region);
        }
        if (!endpointUrl.isBlank()) {
            command.add("--endpoint-url");
            command.add(endpointUrl);
        }
        if (!profile.isBlank()) {
            command.add("--profile");
            command.add(profile);
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        // the credential is never an argument: `ps` shows arguments to every user on the box
        putIfPresent(builder, "AWS_ACCESS_KEY_ID", context.getSecret("awsAccessKeyId"));
        putIfPresent(builder, "AWS_SECRET_ACCESS_KEY", context.getSecret("awsSecretAccessKey"));
        putIfPresent(builder, "AWS_SESSION_TOKEN", context.getSecret("awsSessionToken"));

        context.log("[s3] " + String.join(" ", command));
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        int uploaded = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.log("[s3] " + line);
                output.append(line).append('\n');
                if (line.startsWith("completed ") || line.startsWith("upload:")) {
                    uploaded++;
                }
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004,
                    "aws s3 cp exited " + code + ": " + tail(output.toString()));
        }

        String uri = "s3://" + key;
        return AtomResult.success()
                .data("uri", uri)
                .data("bucket", bucket)
                .data("prefix", prefix)
                .data("uploadedLines", String.valueOf(uploaded))
                .reportUrl("location", "Uploaded to " + bucket, consoleUrl(bucket, prefix, region));
    }

    private static void putIfPresent(ProcessBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.environment().put(name, value);
        }
    }

    /** The console URL is a convenience; a custom endpoint (MinIO, R2) has no console, so it is not linked. */
    private static String consoleUrl(String bucket, String prefix, String region) {
        String zone = region.isBlank() || "us-east-1".equals(region) ? "" : "region=" + region;
        String base = "https://s3.console.aws.amazon.com/s3/buckets/" + bucket;
        return zone.isBlank() ? base : base + "?" + zone;
    }

    private static String resolve(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String tail(String text) {
        String[] lines = text.strip().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString().strip();
    }
}
