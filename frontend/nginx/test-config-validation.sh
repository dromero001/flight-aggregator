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
# A path in the upstream would replace every request's URI (proxy_pass through a variable).
docker_refused API_UPSTREAM "$good_authority" dashboard 'http://app:8080/'

id=$(docker run -d -e API_UPSTREAM=http://app:8080 -e "OIDC_AUTHORITY=$good_authority" \
  -e OIDC_CLIENT_ID=dashboard "$image")
body=
for _ in 1 2 3 4 5 6 7 8 9 10; do
  body=$(docker exec "$id" wget -qO- http://localhost:8080/config.json 2>/dev/null) && break
  sleep 1
done
if [ "$body" = "{\"oidcAuthority\":\"$good_authority\",\"oidcClientId\":\"dashboard\"}" ]; then
  echo "ok: the compose values serve /config.json"
else
  echo "FAIL: /config.json was: $body"
  fail=1
fi

# The resolver is the container's own (/etc/resolv.conf), not Docker's embedded DNS alone, which
# exists only on Docker's user-defined networks: this container runs on the default bridge.
resolver=$(docker exec "$id" sed -n 's/^resolver \([^ ;]*\).*/\1/p' /etc/nginx/conf.d/default.conf)
nameserver=$(docker exec "$id" sed -n 's/^nameserver \([^ ]*\).*/\1/p' /etc/resolv.conf | head -n 1)
if [ -n "$nameserver" ] && [ "$resolver" = "$nameserver" ]; then
  echo "ok: nginx resolves through the container's nameserver ($nameserver)"
else
  echo "FAIL: resolver '$resolver', nameserver '$nameserver'"
  fail=1
fi
docker rm -f "$id" >/dev/null

# The proxy, against an upstream that echoes what reaches it: the same image running a plain nginx.
net=frontend-proxy-test-$$
docker network create "$net" >/dev/null
echo_conf='pid /tmp/echo.pid; error_log stderr; events {} http { access_log off; server { listen 8080;
  location / { default_type text/plain;
    return 200 "uri=$request_uri proto=$http_x_forwarded_proto host=$http_x_forwarded_host port=$http_x_forwarded_port"; } } }'
docker run -d --network "$net" --name "echo-$$" --network-alias echo -e "ECHO_CONF=$echo_conf" \
  --entrypoint sh "$image" -c 'printf "%s" "$ECHO_CONF" > /tmp/echo.conf && exec nginx -c /tmp/echo.conf -g "daemon off;"' >/dev/null
id=$(docker run -d --network "$net" -e API_UPSTREAM=http://echo:8080 \
  -e "OIDC_AUTHORITY=$good_authority" -e OIDC_CLIENT_ID=dashboard "$image")

# answer <wget arguments...>: the body (or wget's error) of a request made inside the container.
answer() {
  docker exec "$id" wget -qO- "$@" 2>&1
}
for _ in 1 2 3 4 5 6 7 8 9 10; do
  answer http://localhost:8080/api/ready >/dev/null && break
  sleep 1
done

# A client must not choose the scheme, host or port the app sees (Tomcat's RemoteIpValve reads
# them as it reads X-Forwarded-For).
forwarded=$(answer --header 'X-Forwarded-Proto: https' --header 'X-Forwarded-Host: evil.example' \
  --header 'X-Forwarded-Port: 444' 'http://localhost:8080/api/itineraries?origin=MAD')
if [ "$forwarded" = 'uri=/api/itineraries?origin=MAD proto= host= port=' ]; then
  echo "ok: the request reaches the app as sent, without the client's forwarded headers"
else
  echo "FAIL: the app received: $forwarded"
  fail=1
fi

# A bundle file that does not exist (an old index.html after a redeploy) is a 404, not the app's
# index.html served as JavaScript.
missing=$(answer -S http://localhost:8080/assets/missing.js | grep -o 'HTTP/1.1 [0-9]*' | head -n 1)
if [ "$missing" = 'HTTP/1.1 404' ]; then
  echo "ok: a missing asset answers 404"
else
  echo "FAIL: a missing asset answered '$missing'"
  fail=1
fi

# nginx runs unprivileged: a `user` directive only adds a warning to every start.
if docker logs "$id" 2>&1 | grep -q 'warn'; then
  echo "FAIL: nginx warned at start: $(docker logs "$id" 2>&1 | grep 'warn')"
  fail=1
else
  echo "ok: nginx starts without a warning"
fi

docker rm -f "$id" "echo-$$" >/dev/null

# An app that accepts the connection and never answers (a stalled JVM, an exhausted pool): nginx
# gives up within its 15 s read timeout, above the re-price's 10 s budget, instead of its own 60 s.
# (nc with its standard input kept open, -i, reads the request and never writes.)
docker run -d -i --network "$net" --name "hang-$$" --network-alias hang \
  --entrypoint nc "$image" -l -p 8080 >/dev/null
id=$(docker run -d --network "$net" -e API_UPSTREAM=http://hang:8080 \
  -e "OIDC_AUTHORITY=$good_authority" -e OIDC_CLIENT_ID=dashboard "$image")
for _ in 1 2 3 4 5 6 7 8 9 10; do
  answer http://localhost:8080/config.json >/dev/null && break
  sleep 1
done
started=$(date +%s)
hung=$(answer -S -T 40 http://localhost:8080/api/itineraries | grep -o 'HTTP/1.1 [0-9]*' | head -n 1)
waited=$(($(date +%s) - started))
if [ "$hung" = 'HTTP/1.1 504' ] && [ "$waited" -le 20 ]; then
  echo "ok: a hung app answers 504 after ${waited} s"
else
  echo "FAIL: a hung app answered '$hung' after ${waited} s"
  fail=1
fi

docker rm -f "$id" "hang-$$" >/dev/null
docker network rm "$net" >/dev/null

exit "$fail"
