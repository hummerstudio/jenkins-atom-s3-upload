# jenkins-atom-s3-upload

S3 upload — port of **s3** (约 30,000) as an atom.

The CLI speaks every S3 flavour (MinIO, R2, OSS via endpoint-url). Credentials travel in the environment —
argv and the log see only the bucket.

## Build and test

```bash
mvn verify
```

`mvn verify` packages the jar and then runs the contract tests through failsafe — the packaged jar started the way
the runner starts it (`input.json` + the `atom_*` environment).
