#!/usr/bin/env python3
"""Static route and known-requester evidence; does not contact a device."""
import argparse
import hashlib
import json
import lzma
from pathlib import Path
import runpy
import struct
import subprocess
p=argparse.ArgumentParser()
p.add_argument('sf',type=Path); p.add_argument('gui',type=Path); p.add_argument('dexdump',type=Path); p.add_argument('output',type=Path)
a=p.parse_args(); a.output.mkdir(exist_ok=False)
ELF=runpy.run_path(str(Path(__file__).with_name('elf_audit.py')))['ELF']
sf=ELF(a.sf.read_bytes()); gui=ELF(a.gui.read_bytes())
# Bind both transaction values, table entries, virtual-load offsets and actual SF pointer values.
assert gui.word(0xa98cc)==0x52800641 # MOV W1,50
assert gui.word(0xa904c)==0x32000fe1 # ORR W1,WZR,15
assert gui.word(0xa5b34)==0xf9405d08 # LDR X8,[X8,0xb8]
assert gui.word(0xa6dec)==0xf9406d08 # LDR X8,[X8,0xd8]
assert gui.word(0xa5150)==0x51000428 # table index transaction-1
assert gui.word(0xa5178)==0x7868792b # LDRH W11,[X9,X8,LSL #1]
case={n:0xa5188+4*struct.unpack_from('<H',gui.data,gui.offset(0x539b4+2*(n-1),2))[0] for n in (15,50)}
assert case=={15:0xa5a94,50:0xa661c}
vptr=0x191638
assert struct.unpack_from('<Q',sf.data,sf.offset(vptr+0xb8,8))[0]==0xfd0a0
assert struct.unpack_from('<Q',sf.data,sf.offset(vptr+0xd8,8))[0]==0xff4e8
assert all(any(r[0]==slot for r in sf.relocations()) for slot in (vptr+0xb8,vptr+0xd8))
# Recover function names directly from this immutable ELF's compressed debug data.
b=lzma.decompress(sf.section('.gnu_debugdata'))
h=struct.unpack_from('<HHIQQQIHHHHHH',b,16)
sh=[struct.unpack_from('<IIQQQQIIQQ',b,h[5]+i*h[10]) for i in range(h[11])]
names={}
for s in sh:
    if s[1]!=2: continue
    st=sh[s[6]]; strings=b[st[4]:st[4]+st[5]]
    for off in range(s[4],s[4]+s[5],s[9]):
        n,info,other,index,value,size=struct.unpack_from('<IBBHQQ',b,off)
        if value in (0xfd0a0,0xff4e8): names[hex(value)]=dict(name=strings[n:].split(b'\0',1)[0].decode(),size=size)
assert 'captureScreenForBlurEngine2' in names['0xff4e8']['name'] and names['0xff4e8']['size']==0x64c
assert 'captureScreen' in names['0xfd0a0']['name'] and 'BlurEngine2' not in names['0xfd0a0']['name']
# Reproducible excerpts from inputs rather than relying only on earlier text reports.
od=__import__('os').environ.get('LLVM_OBJDUMP', 'llvm-objdump')
for name,start,end in [('ordinary-bp',0xa8f58,0xa9178),('private-bp',0xa97c4,0xa99e8),('dispatch',0xa5120,0xa5188),('ordinary-bn',0xa5a94,0xa5b3c),('private-bn-entry',0xa661c,0xa6690),('private-bn-call',0xa6d84,0xa6e20)]:
    text=subprocess.run([od,'-d','--start-address='+hex(start),'--stop-address='+hex(end),str(a.gui)],text=True,capture_output=True,check=True).stdout
    with (a.output/(name+'.asm')).open('x') as f:f.write(text)
lines=a.dexdump.read_text().splitlines()
excerpt='\n'.join(lines[14216:14254])+'\n'
assert 'mVideosRenderW' in excerpt and 'mVideosRenderH' in excerpt and 'access$2100' in excerpt
assert 'mul-float/2addr v6' in excerpt and 'mul-float/2addr v7' in excerpt
assert 'screenshotForBlurEngine2:' in excerpt and ')V' in excerpt and 'updateTexImage:()V' in excerpt
with (a.output/'known-java-requester.txt').open('x') as f:f.write(excerpt)
report=dict(status='PASS_STATIC_SCOPE_EVIDENCE',input_hashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (a.sf,a.gui,a.dexdump)},
    functions=names,ordinary=dict(transaction=15,bp_send_va='0xa904c',bn_case=hex(case[15]),vtable_offset='0xb8',sf_target='0xfd0a0'),
    private_blur=dict(transaction=50,bp_send_va='0xa98cc',bn_case=hex(case[50]),vtable_offset='0xd8',sf_target='0xff4e8'),
    conclusion='Ordinary captureScreen transaction 15 dispatches to a different SF function; the five-word patch is only inside captureScreenForBlurEngine2 transaction 50. Ordinary screenshot routing does not reach this entry.',
    requester='Known Java call recomputes requested width/height from mVideosRenderW/H times access$2100 scale, returns void and then updateTexImage; no output buffer dimensions are fed back at this call site.',
    limits=['Private API routing separation is not a new permission boundary or a guarantee against explicit transaction-50 callers.',
            'No assertion about arbitrary future/native requesters or unrelated Java code.',
            'Delegated JNI compatibility audit is context, not repeated by this scope script.'])
with (a.output/'scope-results.json').open('x') as f:json.dump(report,f,indent=2);f.write('\n')
print(json.dumps(report,indent=2))
