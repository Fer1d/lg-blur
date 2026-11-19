# LG blur source-only research subset

This is an inherited, modified baseline, **not stock OEM source**. It is not a
complete installer, a deployment workspace, or a reproducible Android product.
The historical performance gate failed; a user trial is not a performance pass.
No current device or performance result is claimed by these host tests.

## Included and provenance

`source-manifest.json` is the exact copy allowlist, original relative paths and
SHA-256 digests, plus published digests and adaptation flags. The input archive
was `lg-blur-implementation-j2sw4y91`. `framework/` preserves the production
landscape helpers, additive bridge/lifecycle generators, assembly/check tools,
and standalone host model/allocator tests from its landscape-framework branch.
`capture/` preserves capture/pacing helpers and the pure-Java pacer test from its
blur-reuse branch. `sf/` preserves the exact-build ceil-half offline patch,
ELF/scope audits, modeled test and archival reproduction script. These branches
are not represented as one assembled final candidate; later independent review
variants are not silently substituted for the production workspace.

## Archival dependencies and safety

All framework build/generator scripts and `sf/reproduce.sh` are **archival**.
Framework scripts expect an owned working directory with external `input/`,
`full-decoded/`, generated bridges, stub classes and tool classes. These are not
included. Work in a separate scratch copy; the scripts create outputs beside
inputs and refuse selected existing outputs. Generator hashes remain pinned to
the inherited baseline. Only the incidental workspace-name prefix guard was
relaxed; exact source hashes were not relaxed. Java assembly/check tools depend
on the external apktool/dexlib classpath. Set `ANDROID_JAR`, `APKTOOL_JAR` and
`ANDROID_BUILD_TOOLS` for the archival build script. Compile helper/tool/stub
classes separately before attempting it; this import is not a turnkey build.

SF tools read external ELF/dexdump inputs; never execute an input ELF. `patch.py`
requires its exact original SHA-256, writes a separate candidate and manifest,
and is not a generic binary patcher. `test.py INPUT CANDIDATE EVIDENCE_DIR` uses
`LLVM_MC` / `LLVM_OBJDUMP` (default PATH commands). `scope_audit.py SF GUI
DEXDUMP OUTPUT_DIR` needs additional external read-only inputs. Use `python3 -I`
for Python scripts. The archival reproduction wrapper may require an original
workspace layout; prefer explicit positional arguments to individual tools.
No scripts were run against a device and no deployment is provided.

## Exclusions and rights review

No OEM smali dumps, reconstructed full OEM method bodies, DEX/JAR/APK/ELF
artifacts, binary fixtures, logs, device identifiers/inventories, deployment or
recovery workspace were copied. Generators contain additive patch snippets and
references to OEM class/member names; SF scripts retain minimal expected
instruction words/offsets and hash checks needed to bind the transformation,
not a full OEM function. This is a source inspection, not legal clearance.
No new license grant or SPDX label has been invented. Existing notices, if any,
are preserved; absence of notices does not establish redistribution permission.
Review ownership/redistribution rights before public distribution.

Excluded for bounded scope: GPU experiments, Android/proprietary-dependent
integration test fixtures and stubs, later independent review variants (including
ActualLegacyTail), analysis/device collection and deployment tooling, and all
historical reports. These exclusions mean no end-to-end integration or final
candidate parity claim. Host model tests are not Android/OEM behavior validation.

## Local validation

Python syntax compilation, shell syntax, and standalone Java host tests are
recorded in `validation.txt`. Generated validation files stay under this tree;
no source inputs outside it are modified. Full Android build/generator replay
and on-device/performance validation are not performed.
