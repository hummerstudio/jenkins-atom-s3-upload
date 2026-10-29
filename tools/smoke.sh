#!/usr/bin/env bash
# A fake aws CLI records the argv and the environment: credentials must travel in the env, never in argv.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/aws" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
{ echo "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-unset}"; echo "AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-unset}"; } \
  >> "$SMOKE_ENV"
echo "upload: ./dist/app.jar to s3://bucket/prefix/app.jar"
exit 0
SH
chmod +x "$work/bin/aws"
export SMOKE_ARGV="$work/argv.txt" SMOKE_ENV="$work/env.txt"

dir="$work/upload"; mkdir -p "$dir/dist"
printf 'jar\n' >"$dir/dist/app.jar"
printf '{"bucket": "my-bucket", "source": "dist", "prefix": "builds/42", "region": "eu-west-1"}' >"$dir/input.json"
printf '{"awsAccessKeyId": "AKIA-SMOKE", "awsSecretAccessKey": "smoke-secret"}' >"$dir/.secrets.json"
PATH="$work/bin:$PATH" run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" uri)" = "s3://my-bucket/builds/42" ] || fail "uri: $(cat "$dir/output.json")"
grep -q -- "s3 cp .*dist s3://my-bucket/builds/42 --recursive --region eu-west-1" "$work/argv.txt" || fail "argv: $(cat "$work/argv.txt")"
grep -q "AKIA-SMOKE" "$work/env.txt" || fail "the key must reach the CLI through the environment"
grep -q "AKIA-SMOKE" "$work/argv.txt" && fail "the key must not be in argv"
grep -q "smoke-secret" "$dir/log.txt" && fail "the secret must not be in the log"

# a custom endpoint is passed through
dir="$work/minio"; mkdir -p "$dir/dist"; printf 'x\n' >"$dir/dist/x.bin"
printf '{"bucket": "b", "source": "dist", "endpointUrl": "http://127.0.0.1:9000"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >/dev/null 2>&1 || fail "minio run failed"
grep -q -- "--endpoint-url http://127.0.0.1:9000" "$work/argv.txt" || fail "endpoint not passed"

# a source outside the workspace is refused
dir="$work/escape"; mkdir -p "$dir"
printf '{"bucket": "b", "source": "../etc"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >/dev/null 2>&1 && fail "a source outside the workspace must be refused"
grep -q "inside the workspace" "$dir/output.json" || fail "message not readable"

# no aws CLI is an environment error
dir="$work/noaws"; mkdir -p "$dir/dist" && printf 'x\n' >"$dir/dist/x.bin"
mkdir -p "$work/empty"
printf '{"bucket": "b", "source": "dist"}' >"$dir/input.json"
PATH="$work/empty:/usr/bin:/bin" run "$dir" >/dev/null 2>&1 && fail "missing aws must fail"
grep -q '"errorType":5' "$dir/output.json" || fail "ENVIRONMENT(5) expected: $(cat "$dir/output.json")"

echo "smoke ok: argv, credentials via env not argv, endpoint, workspace guard, missing aws"
