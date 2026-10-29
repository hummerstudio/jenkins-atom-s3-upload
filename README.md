# jenkins-atom-s3-upload

S3 upload — port of **s3** (约 30,000) as an atom.

The CLI speaks every S3 flavour (MinIO, R2, OSS via endpoint-url). Credentials travel in the environment —
argv and the log see only the bucket.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
