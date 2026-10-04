# Source me. Points Vulkan at the host's NVIDIA driver from inside the distrobox.
# The container only ships Mesa ICDs; the host driver libs (/run/host/usr/lib) work as-is.
# Relinked on every source so host driver updates are picked up (same trick as mcwow's runClient).
_cc_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
_cc_libs="$_cc_root/data/run/nvidia-libs"
if [[ -e /run/host/usr/lib/libGLX_nvidia.so.0 ]]; then
  rm -rf "$_cc_libs" && mkdir -p "$_cc_libs"
  for f in /run/host/usr/lib/libGLX_nvidia.so.0 /run/host/usr/lib/libEGL_nvidia.so.0 \
           /run/host/usr/lib/libnvidia-*.so.[0-9]*; do
    [[ -e "$f" ]] && ln -s "$f" "$_cc_libs/"
  done
  cat >"$_cc_libs/nvidia_icd.json" <<EOF
{ "file_format_version": "1.0.1",
  "ICD": { "library_path": "$_cc_libs/libGLX_nvidia.so.0", "api_version": "1.4.341" } }
EOF
  export VK_ICD_FILENAMES="$_cc_libs/nvidia_icd.json"
  export LD_LIBRARY_PATH="$_cc_libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export __GLX_VENDOR_LIBRARY_NAME=nvidia
else
  echo "nvidia-env: no host NVIDIA driver found; Vulkan will use Mesa" >&2
fi
unset _cc_root _cc_libs
