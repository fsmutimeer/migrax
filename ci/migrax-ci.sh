#!/bin/sh
# Migrax in GitLab CI and Bitbucket Pipelines. Checks that entity changes have migrations,
# lints the migrations a merge request adds or changes, writes the HTML report, and comments
# the results on the merge request (GitLab) or pull request (Bitbucket).
#
# Runs in any image with Java 17+, curl, git and the project's build tool (Maven or Gradle).
# Settings, all optional (environment variables):
#   MIGRAX_VERSION                Migrax version to download (default below)
#   MIGRAX_DIR                    Service folder with pom.xml or build.gradle (default: .)
#   MIGRAX_OUT                    Folder for the results and the report (default: migrax-ci)
#   MIGRAX_REPORT                 Write migrax-report.html: true or false (default: true)
#   MIGRAX_FAIL_ON_LINT_WARNINGS  Fail on lint warnings, not only errors (default: false)
#   MIGRAX_COMMENT_TOKEN          Token to comment with. GitLab: a project access token with the
#                                 api scope. Bitbucket: a repository access token with
#                                 pull requests write access. Without it, no comment.
#   MIGRAX_JAR                    A Migrax jar to use instead of downloading one
set -u

MIGRAX_VERSION=${MIGRAX_VERSION:-0.15.0}
OUT=${MIGRAX_OUT:-migrax-ci}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
MARKER='[//]: # (migrax-check)'

if [ -n "${GITLAB_CI:-}" ]; then
  PLATFORM=gitlab
  BASE=${CI_MERGE_REQUEST_TARGET_BRANCH_NAME:-}
elif [ -n "${BITBUCKET_BUILD_NUMBER:-}" ]; then
  PLATFORM=bitbucket
  BASE=${BITBUCKET_PR_DESTINATION_BRANCH:-}
else
  PLATFORM=other
  BASE=${MIGRAX_BASE_BRANCH:-}
fi

JAR=${MIGRAX_JAR:-}
if [ -z "$JAR" ]; then
  JAR="$OUT/migrax-$MIGRAX_VERSION.jar"
  URL="https://github.com/fsmutimeer/migrax/releases/download/v$MIGRAX_VERSION/migrax-$MIGRAX_VERSION.jar"
  if ! curl -fsSL --retry 3 -o "$JAR" "$URL"; then
    echo "Could not download Migrax $MIGRAX_VERSION from $URL" >&2
    exit 1
  fi
fi
migrax() { java -jar "$JAR" "$@" --no-input; }

cd "${MIGRAX_DIR:-.}" || exit 1

migrax check > "$OUT/check.txt" 2>&1
CHECK=$?
cat "$OUT/check.txt"
if [ "$CHECK" = 2 ]; then
  migrax plan > "$OUT/plan.txt" 2>&1
fi

# Lint only the migration files this merge request adds or changes.
FILES=""
if [ -n "$BASE" ]; then
  git config --global --add safe.directory "$(git rev-parse --show-toplevel 2>/dev/null)" \
    2>/dev/null
  git fetch -q --depth=100 origin "+refs/heads/$BASE:refs/remotes/origin/$BASE" 2>/dev/null
  # A shallow clone may not reach the commit the branch started from: then fetch all of it.
  git merge-base "origin/$BASE" HEAD > /dev/null 2>&1 || git fetch -q --unshallow 2>/dev/null
  FILES=$(git diff --relative --name-only --diff-filter=AM "origin/$BASE"...HEAD -- . \
    2>/dev/null | grep -E '\.sql$' | grep -v '/rollback/')
fi
LINT=0
if [ -n "$FILES" ]; then
  # shellcheck disable=SC2086
  migrax lint $FILES > "$OUT/lint.txt" 2>&1
  LINT=$?
  cat "$OUT/lint.txt"
fi
WARNINGS=$(grep -c ': warning MX' "$OUT/lint.txt" 2>/dev/null)
ERRORS=$(grep -c ': error MX' "$OUT/lint.txt" 2>/dev/null)
WARNINGS=${WARNINGS:-0}
ERRORS=${ERRORS:-0}

if [ "${MIGRAX_REPORT:-true}" = true ]; then
  migrax report --html "$OUT/migrax-report.html" 2>&1
fi

# The comment, in Markdown that GitLab and Bitbucket both show.
COMMENT="$OUT/comment.md"
{
  echo "$MARKER"
  echo "### Migrax"
  echo
  case $CHECK in
    0) echo "Entities match the migrations." ;;
    2) echo "**Entity changes without a migration.** Run \`migrax generate\` and commit the result." ;;
    *) echo "**Migrax could not check the entities.** See the job log." ;;
  esac
  echo
  echo '```'
  grep -v "^migrax: " "$OUT/check.txt"
  echo '```'
  if [ -s "$OUT/plan.txt" ]; then
    echo
    echo "Planned SQL:"
    echo
    echo '```sql'
    grep -v "^migrax: " "$OUT/plan.txt"
    echo '```'
  fi
  echo
  if [ -z "$FILES" ]; then
    echo "Lint: no new or changed migration files."
  elif [ "$WARNINGS" = 0 ] && [ "$ERRORS" = 0 ]; then
    echo "Lint: no findings in the new or changed migration files."
  else
    echo "**Lint: $ERRORS error(s), $WARNINGS warning(s) in the new or changed migration files.**"
    echo
    echo '```'
    grep -v "^migrax: " "$OUT/lint.txt"
    echo '```'
  fi
  if [ -s "$OUT/migrax-report.html" ]; then
    echo
    case $PLATFORM in
      gitlab) echo "[HTML report]($CI_JOB_URL/artifacts/file/${OUT#"$CI_PROJECT_DIR"/}/migrax-report.html): schema, migrations, lint findings and drift." ;;
      bitbucket) echo "HTML report (schema, migrations, lint findings, drift): in the [pipeline](https://bitbucket.org/$BITBUCKET_REPO_FULL_NAME/pipelines/results/$BITBUCKET_BUILD_NUMBER)'s Artifacts tab." ;;
    esac
  fi
} > "$COMMENT"

# Posts the comment, or updates the one an earlier run wrote.
post_comment() {
  TOKEN=${MIGRAX_COMMENT_TOKEN:-}
  if [ -z "$TOKEN" ]; then
    echo "No MIGRAX_COMMENT_TOKEN: the results are not commented (they are in $COMMENT)."
    return
  fi
  if [ "$PLATFORM" = gitlab ] && [ -n "${CI_MERGE_REQUEST_IID:-}" ]; then
    API="$CI_API_V4_URL/projects/$CI_PROJECT_ID/merge_requests/$CI_MERGE_REQUEST_IID/notes"
    ID=$(curl -fsS -H "PRIVATE-TOKEN: $TOKEN" "$API?per_page=100&sort=desc" \
      | tr -d '\n' | grep -oE '"id": ?[0-9]+[^{}]*"body": ?"\[//\]: # \(migrax-check\)' \
      | head -n 1 | grep -oE '[0-9]+' | head -n 1)
    if [ -n "$ID" ]; then
      curl -fsS -o /dev/null -X PUT -H "PRIVATE-TOKEN: $TOKEN" \
        --data-urlencode "body@$COMMENT" "$API/$ID" && echo "Updated the merge request comment."
    else
      curl -fsS -o /dev/null -X POST -H "PRIVATE-TOKEN: $TOKEN" \
        --data-urlencode "body@$COMMENT" "$API" && echo "Commented on the merge request."
    fi
  elif [ "$PLATFORM" = bitbucket ] && [ -n "${BITBUCKET_PR_ID:-}" ]; then
    API="https://api.bitbucket.org/2.0/repositories/$BITBUCKET_REPO_FULL_NAME/pullrequests/$BITBUCKET_PR_ID/comments"
    ID=$(curl -fsS -G -H "Authorization: Bearer $TOKEN" "$API" \
      --data-urlencode 'q=deleted = false AND content.raw ~ "(migrax-check)"' \
      --data-urlencode 'fields=values.id' | grep -oE '[0-9]+' | head -n 1)
    awk 'BEGIN { printf "{\"content\":{\"raw\":\"" }
      { gsub(/\\/, "\\\\"); gsub(/"/, "\\\""); gsub(/\t/, "\\t"); gsub(/\r/, "");
        printf "%s\\n", $0 }
      END { printf "\"}}" }' "$COMMENT" > "$OUT/comment.json"
    if [ -n "$ID" ]; then
      curl -fsS -o /dev/null -X PUT -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" --data-binary "@$OUT/comment.json" "$API/$ID" \
        && echo "Updated the pull request comment."
    else
      curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" --data-binary "@$OUT/comment.json" "$API" \
        && echo "Commented on the pull request."
    fi
  fi
}
post_comment || echo "Could not comment on the merge request; the results are in $COMMENT." >&2

STATUS=0
if [ "$CHECK" != 0 ]; then
  echo "Migrax: entities changed without a migration, or migrations share numbers. Run 'migrax generate' or 'migrax merge'." >&2
  STATUS=1
fi
if [ "$LINT" != 0 ] || [ "$ERRORS" != 0 ]; then
  echo "Migrax: lint found errors in new or changed migrations." >&2
  STATUS=1
fi
if [ "${MIGRAX_FAIL_ON_LINT_WARNINGS:-false}" = true ] && [ "$WARNINGS" != 0 ]; then
  echo "Migrax: lint found warnings in new or changed migrations." >&2
  STATUS=1
fi
exit $STATUS
