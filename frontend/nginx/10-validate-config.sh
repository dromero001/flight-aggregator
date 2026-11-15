#!/bin/sh
# Runs before the image's 20-envsubst-on-templates.sh (the entrypoint runs /docker-entrypoint.d/ in
# name order). The template pastes these values raw into an nginx string holding JSON and into a
# `set` directive: a quote, a backslash or a `$` would break the JSON or stop nginx with a cryptic
# error, so a bad value stops the container here, naming the variable.
set -eu

# An http(s) URL made only of characters that are safe where the value lands. API_UPSTREAM goes
# unquoted into a `set` directive, where `#` starts a comment; OIDC_AUTHORITY only goes inside a
# quoted string of the JSON, where `#` is plain text, so only it may keep one.
upstream_url='^https?://[A-Za-z0-9._~:/?@!&=+,%-]+$'
json_url='^https?://[A-Za-z0-9._~:/?#@!&=+,%-]+$'

nl=$(printf '\nx')
nl=${nl%x}
cr=$(printf '\rx')
cr=${cr%x}

check() {
  name=$1
  pattern=$2
  eval "value=\${$name:-}"
  # grep matches line by line, so a value with a line break must not reach the pattern at all.
  case $value in
    *"$nl"* | *"$cr"*)
      echo "10-validate-config.sh: $name is missing or not valid (it contains a line break)" >&2
      exit 1
      ;;
  esac
  if ! printf '%s' "$value" | grep -Eq "$pattern"; then
    echo "10-validate-config.sh: $name is missing or not valid (expected $3)" >&2
    exit 1
  fi
}

check API_UPSTREAM "$upstream_url" 'an http:// or https:// URL without quotes, backslashes, $, spaces, semicolons or #'
check OIDC_AUTHORITY "$json_url" 'an http:// or https:// URL without quotes, backslashes, $, spaces or semicolons'
check OIDC_CLIENT_ID '^[A-Za-z0-9._-]+$' 'letters, digits, dots, underscores and hyphens'
