#!/usr/bin/env bash
# Extract VMaNGOS server data (dbc, maps, vmaps, mmaps) from a 1.12.1 client into data/server/.
# Reads the client only. Usage: tools/extract.sh "/path/to/World of Warcraft 1.12.1.5875 enUS"
set -euo pipefail

client="${1:?usage: $0 <wow client dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
ex="$root/build/vmangos-run/bin/Extractors"
out="$root/data/server"
threads="${THREADS:-20}"

mkdir -p "$out" && cd "$out"
t0=$SECONDS; step() { echo "== $1 ($(( SECONDS - t0 ))s elapsed)"; }

step "MapExtractor (dbc, maps)"
"$ex/MapExtractor" -i "$client" -o "$out" --silent
# mangosd looks for DBCs per client build: <DataDir>/5875/dbc
mkdir -p 5875 && rm -rf 5875/dbc && mv dbc 5875/dbc

step "VMapExtractor"
"$ex/VMapExtractor" -l -d "$client/Data" --silent

step "VMapAssembler"
mkdir -p vmaps
"$ex/VMapAssembler" Buildings vmaps

step "MoveMapGenerator (mmaps)"
mkdir -p mmaps
"$ex/MoveMapGenerator" --threads "$threads" --silent \
  --offMeshInput "$ex/offmesh.txt" --configInputPath "$ex/config.json"

step "done"
du -sh 5875/dbc maps vmaps mmaps 2>/dev/null || true
