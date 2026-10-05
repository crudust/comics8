#!/usr/bin/env bash
set -euo pipefail

# ==========================================================
# Comics8 Public Repository Sync Script
# Synchronizes open-source modules (core, desktop, docs, examples)
# to a separate clean public repository.
# ==========================================================

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_REPO="${1:-${COMICS8_PUBLIC_REPO_URL:-git@github.com:crudust/comics8.git}}"
SYNC_BRANCH="${2:-main}"
CUSTOM_COMMIT_MSG="${3:-}"
WORK_DIR="/tmp/comics8-public-sync-$$"

echo "=================================================="
echo "🚀 Syncing Comics8 Open-Source Modules"
echo "Target: ${TARGET_REPO} (${SYNC_BRANCH})"
echo "=================================================="

# 1. Clean workspace
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

# 2. Clone or initialize target repository
echo "📦 Cloning target repository..."
if git clone --depth 1 -b "${SYNC_BRANCH}" "${TARGET_REPO}" "${WORK_DIR}" 2>/dev/null; then
    echo "  ✅ Cloned existing branch: ${SYNC_BRANCH}"
else
    echo "  🌱 Initializing fresh repository..."
    git init -b "${SYNC_BRANCH}" "${WORK_DIR}"
    (cd "${WORK_DIR}" && git remote add origin "${TARGET_REPO}")
fi

# 3. Clean working tree (preserve .git)
(cd "${WORK_DIR}" && find . -maxdepth 1 ! -name "." ! -name ".." ! -name ".git" -exec rm -rf {} +)

# 4. Copy open-source modules
echo "📁 Copying open-source files..."
rsync -a --exclude="build" --exclude=".gradle" .github "${WORK_DIR}/"
rsync -a --exclude="build" --exclude=".gradle" core "${WORK_DIR}/"
rsync -a --exclude="build" --exclude=".gradle" desktop "${WORK_DIR}/"
rsync -a --exclude="build" --exclude=".gradle" examples "${WORK_DIR}/"
rsync -a --exclude="build" --exclude=".gradle" docs "${WORK_DIR}/"
rsync -a --exclude="build" --exclude=".gradle" gradle "${WORK_DIR}/"
cp gradlew gradlew.bat build.gradle.kts LICENSE README.md PRIVACY_POLICY.md server.json "${WORK_DIR}/"

# Clean build artifacts if any
find "${WORK_DIR}" -name "build" -type d -exec rm -rf {} + 2>/dev/null || true
find "${WORK_DIR}" -name ".gradle" -type d -exec rm -rf {} + 2>/dev/null || true

# Filter examples/sources to only keep official clean open-source plugins (peppercarrot.js)
find "${WORK_DIR}/examples/sources" -type f ! -name "peppercarrot.js" -delete 2>/dev/null || true

# Remove non-public test fixtures and internal knowledge
rm -rf "${WORK_DIR}/knowledge" "${WORK_DIR}/core/src/test/resources/hitomi" 2>/dev/null || true
rm -f "${WORK_DIR}/core/src/test/kotlin/source/js/ElevenJsSourceTest.kt" \
      "${WORK_DIR}/core/src/test/kotlin/source/js/HitomiJsSourceTest.kt" \
      "${WORK_DIR}/core/src/test/kotlin/source/js/RawkumaJsSourceTest.kt" \
      "${WORK_DIR}/core/src/test/kotlin/source/js/RawkumaDebugLiveTest.kt" 2>/dev/null || true

# Generate desktop-only settings.gradle.kts
cat << 'SETTINGS_EOF' > "${WORK_DIR}/settings.gradle.kts"
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "Comics8"
include(":core")
include(":desktop")
SETTINGS_EOF

# Copy .gitignore
cat << 'GITIGNORE_EOF' > "${WORK_DIR}/.gitignore"
.gradle
build/
local.properties
.idea/
*.iml
.DS_Store
*.hprof
*.log
GITIGNORE_EOF

# 5. Commit & Push
echo "📝 Committing changes..."
(
    cd "${WORK_DIR}"
    git config user.name "crudust"
    git config user.email "crudust@users.noreply.github.com"
    git add -A
    
    if git diff --staged --quiet; then
        echo "⚡ No changes detected. Public repository is already up-to-date."
    else
        if [[ -n "${CUSTOM_COMMIT_MSG}" ]]; then
            COMMIT_MSG="${CUSTOM_COMMIT_MSG}"
        else
            DEV_MSG=$(git -C "${SOURCE_DIR}" log -1 --grep="^chore: Bump version" --invert-grep --format="%s" 2>/dev/null || echo "")
            if [[ -z "${DEV_MSG}" ]]; then
                DEV_MSG=$(git -C "${SOURCE_DIR}" log -1 --format="%s" 2>/dev/null || echo "")
            fi

            if [[ -n "${DEV_MSG}" ]]; then
                COMMIT_MSG="${DEV_MSG}"
            else
                COMMIT_MSG="sync: Update Comics8 $(date '+%Y-%m-%d %H:%M:%S')"
            fi
        fi
        git commit -m "${COMMIT_MSG}"
        echo "🚀 Pushing to ${TARGET_REPO}..."
        git push -u origin "${SYNC_BRANCH}"
        echo "🎉 Successfully synced to ${TARGET_REPO}!"
    fi
)

rm -rf "${WORK_DIR}"
