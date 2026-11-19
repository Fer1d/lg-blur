#!/usr/bin/env python3
"""Host-only exact-build symmetric ceil-half candidate; no deployment or execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import struct

ELF = runpy.run_path(str(Path(__file__).with_name('elf_audit.py')))['ELF']
EXPECTED = '2bc980c2fdb50540813a0746e6d46b4f3cd7d265f5f4d2769f5b5f220736d040'
START = 0xff84c
ORIGINAL = (0x90000009, 0x912cd129, 0x6f00e400, 0x4e081d20, 0x3d8017e0)
REPLACEMENT = (0x10001749, 0xfd4047e0, 0x2f3f2400, 0xfd0047e0, 0xa9057fe9)
ASSEMBLY = ('adr x9, #744', 'ldr d0, [sp, #136]', 'urshr v0.2s, v0.2s, #1',
            'str d0, [sp, #136]', 'stp x9, xzr, [sp, #80]')

def require(ok, message):
    if not ok: raise ValueError('REFUSED: '+message)

def prepare(data):
    require(hashlib.sha256(data).hexdigest() == EXPECTED, 'exact input SHA256 mismatch')
    elf = ELF(data)
    offset = elf.offset(START,20,executable=True)
    require(struct.unpack_from('<5I',data,offset) == ORIGINAL, 'original words differ')
    rels = elf.relocations()
    require(all(not (va < START+20 and va+16 > START) for va,*_ in rels), 'relocation overlaps patch window')
    require(elf.word(0xff804) == 0x291153f9, 'original dimension store differs')
    require(elf.word(0xff8a4) == 0x3dc017e0, 'fresh vector load differs')
    output = data[:offset]+struct.pack('<5I',*REPLACEMENT)+data[offset+20:]
    manifest = dict(status='HOST_ONLY_REVIEW_CANDIDATE_NOT_APPROVED_FOR_DEPLOYMENT',
        input_sha256=EXPECTED, output_sha256=hashlib.sha256(output).hexdigest(), size=len(output),
        policy='Each effective unsigned 32-bit dimension becomes ceil(n/2), independently, once per invocation.',
        zero_fallback='Original behavior retained: if either requested dimension is zero, BOTH are replaced by display dimensions before this window. Any effective zero remains zero.',
        boundaries='0->0; 1->1; 2->1; 3->2; UINT32_MAX->0x80000000. Odd sizes round upward.',
        small_dimensions='No threshold. Small dimensions halve once per call; this is not idempotent under output-to-input feedback.',
        known_requester='Known Java captureBGnMask computes requests from mVideosRenderW/H times configured scale on each call; screenshotForBlurEngine2 returns void. No buffer-size feedback at that call site. Not a guarantee for arbitrary private-API callers.',
        savings='Delegated JNI audit reports direct EGL binding and existing resampling to unchanged client FBO. Expected savings are SurfaceFlinger capture/render work, not reduced client blur FBO work; no performance measurement here.',
        changes=[dict(va=hex(START+4*i),file_offset=hex(offset+4*i),original_le=struct.pack('<I',old).hex(),replacement_le=struct.pack('<I',new).hex(),assembly=asm) for i,(old,new,asm) in enumerate(zip(ORIGINAL,REPLACEMENT,ASSEMBLY))],
        relocation_counts={k:sum(r[3]==k for r in rels) for k in ('APS2','RELR','RELA_PLT')},
        load_segments=[dict(va=hex(p[3]),offset=hex(p[2]),filesz=hex(p[5]),flags=p[1]) for p in elf.ph if p[0]==1],
        limitations=['No device actions, arbitrary instruction execution, integration, visual or performance validation.',
            'Independent review required. Odd-dimension ceil rounding causes unavoidable subpixel/aspect-ratio differences.',
            'Does not validate arbitrary dimensions or change existing allocation errors.',
            'Private route separation is not a claim that the Binder transaction is inaccessible to deliberately crafted requests.',
            'SIMD model is a bounded instruction model, not an independent hardware emulator.'])
    return output,manifest

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',type=Path); p.add_argument('output',type=Path); p.add_argument('manifest',type=Path)
    a=p.parse_args()
    require(len({x.resolve() for x in (a.input,a.output,a.manifest)})==3,'paths must differ')
    require(not os.path.lexists(a.output) and not os.path.lexists(a.manifest),'output or manifest already exists')
    output,manifest=prepare(a.input.read_bytes())
    # Reserve BOTH destinations exclusively before writing; never truncate existing files.
    with a.output.open('xb') as binary:
        with a.manifest.open('x') as report:
            binary.write(output)
            json.dump(manifest,report,indent=2); report.write('\n')
    print(json.dumps(dict(output=str(a.output),sha256=manifest['output_sha256'],status=manifest['status'])))

if __name__=='__main__': main()
