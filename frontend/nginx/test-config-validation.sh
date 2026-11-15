#!/bin/sh
# Proves the image refuses an unsafe configuration and serves a good one.
# Usage: frontend/nginx/test-config-validation.sh <image>   (docker build -t <image> frontend first)
set -u
image=$1
fail=0

good_authority='http://localhost:8180/realms/flight-aggregator'

nl=$(printf '\nx')
nl=${nl%x}

# docker_refused <variable that must be named> <OIDC_AUTHORITY> <OIDC_CLIENT_ID> [API_UPSTREAM]
docker_refused() {
  named=$1
  authority=$2
  client=$3
  api=${4:-http://app:8080}
  id=$(docker run -d -e "API_UPSTREAM=$api" -e "OIDC_AUTHORITY=$authority" \
    -e "OIDC_CLIENT_ID=$client" "$image")
  # A refused configuration stops the container; a served one keeps running.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ "$(docker inspect -f '{{.State.Running}}' "$id")" = false ] && break
    sleep 1
  done
  status=$(docker inspect -f '{{.State.ExitCode}}' "$id")
  log=$(docker logs "$id" 2>&1)
  docker rm -f "$id" >/dev/null
  # The log line must come from the validation itself, not from nginx dying later.
  if [ "$status" -ne 0 ] && printf '%s' "$log" | grep -q "10-validate-config.sh: $named "; then
    echo "ok: refused, naming $named"
  else
    echo "FAIL: expected a refusal naming $named (exit $status)"
    fail=1
  fi
}

docker_refused OIDC_AUTHORITY 'http://x/"y' dashboard
docker_refused OIDC_CLIENT_ID "$good_authority" 'a$uri'
docker_refused OIDC_AUTHORITY "http://x${nl}\"y" dashboard
docker_refused OIDC_CLIENT_ID "$good_authority" "dash${nl}board"
docker_refused API_UPSTREAM "$good_authority" dashboard 'http://app:8080#x'

id=$(docker run -d -e API_UPSTREAM=http://app:8080 -e "OIDC_AUTHORITY=$good_authority" \
  -e OIDC_CLIENT_ID=dashboard "$image")
body=
for _ in 1 2 3 4 5 6 7 8 9 10; do
  body=$(docker exec "$id" wget -qO- http://localhost:8080/config.json 2>/dev/null) && break
  sleep 1
done
docker rm -f "$id" >/dev/null
if [ "$body" = "{\"oidcAuthority\":\"$good_authority\",\"oidcClientId\":\"dashboard\"}" ]; then
  echo "ok: the compose values serve /config.json"
else
  echo "FAIL: /config.json was: $body"
  fail=1
fi

exit "$fail"
