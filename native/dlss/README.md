# DLSS Super Resolution

Windows x64, Vulkan path tracing only. Select **Upscaler: DLSS** in Rayvox settings.
Quality/Balanced/Performance use the existing internal scales; Native uses NGX's
DLAA mode. Adaptive recreates the feature after a scale change and resets history.
DLSS supplies AA while active. Missing native resources, unsupported hardware,
or NGX initialization/create/evaluate errors retain reconstruction with FXAA (or
selected TAA); Diagnostics shows the reason. A renderer restart or switching the
upscaler away and back retries NGX.

## Build

Install CMake, MSVC x64 build tools, and Java 21. Obtain the official
[NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS) and
[Vulkan headers](https://github.com/KhronosGroup/Vulkan-Headers). This integration
was built against DLSS commit `9981c6350dc58c0ce21f024fd65e31e851c31689` and
Vulkan-Headers `v1.3.290`.

```powershell
./gradlew.bat jar -PrayvoxDlssSdk=C:/sdk/DLSS -PrayvoxVulkanHeaders=C:/sdk/Vulkan-Headers
```

Set `-PrayvoxCmake=C:/path/to/cmake.exe` if CMake is not on PATH. With the Vulkan
SDK installed, `VULKAN_SDK` can provide its headers instead. The opt-in build
packages `rayvox_dlss.dll`, the **release** `nvngx_dlss.dll`, and the NVIDIA license
under `natives/windows-x86_64`. SDK headers/libraries are external build inputs;
ordinary builds keep the fallback and do not require the SDK or a C++ compiler.
The resulting artifact is `build/libs/rayvox-0.1.0-dlss.jar`.
Distribute an NGX-enabled build under the included NVIDIA SDK license terms.

For development, `-Drayvox.dlss.library=C:/path/to/rayvox_dlss.dll` loads an explicit
bridge path; put `nvngx_dlss.dll` beside it. NGX logs go to `logs/rayvox-dlss`.
Rayvox uses a stable custom-engine project GUID with NGX's ProjectID API.

## Verification

```powershell
./gradlew.bat vulkanTest -PrayvoxDlssSdk=C:/sdk/DLSS -PrayvoxVulkanHeaders=C:/sdk/Vulkan-Headers -PrayvoxDlssGpuTest --tests '*Dlss*'
```

The GPU smoke test requires a supported NVIDIA GPU and driver; it exercises the
real NGX create/evaluate/release path. In-game acceptance: check every scale,
resize/fullscreen, AA/upscaler switches, teleport/dimension changes, thin edges,
moving entities, sky, and disocclusion. Confirm Diagnostics says **DLSS active**,
and the hand/HUD stay sharp. Test an ordinary build to verify fallback.

## Current limits

The input is denoised linear HDR color, with coherent Halton jitter, non-inverted
normalized depth, unjittered camera motion in render-pixel units, and a current-color
bias mask on disocclusions and dynamic geometry. Dynamic meshes do not yet retain
previous object transforms: they use camera motion and bias toward current color.
Inspect moving entities for ghosting before treating this as accepted gameplay.
NGX's reported allocations count toward Rayvox's memory budget; a failed memory
query or exceeded budget disables DLSS and releases its feature after queued work completes.
This adds Super Resolution/DLAA, not frame generation or ray reconstruction.
