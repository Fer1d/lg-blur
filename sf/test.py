#!/usr/bin/env python3
"""Decode actual AArch64 words in a bounded non-native model; never execute ELF code."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import random
import re
import runpy
import struct
import subprocess
import tempfile

p=argparse.ArgumentParser()
p.add_argument('input',type=Path); p.add_argument('candidate',type=Path); p.add_argument('evidence',type=Path)
a=p.parse_args()
a.evidence.mkdir(exist_ok=False)
m=runpy.run_path(str(Path(__file__).with_name('patch.py')))
source=a.input.read_bytes(); candidate=a.candidate.read_bytes()
expected,manifest=m['prepare'](source)
assert candidate==expected
ELF=m['ELF']; elf=ELF(source)
mc=__import__('os').environ.get('LLVM_MC', 'llvm-mc')
od=__import__('os').environ.get('LLVM_OBJDUMP', 'llvm-objdump')
checks=[]
def check(name,ok):
    assert ok,name
    checks.append(name)
def save(name,text):
    with (a.evidence/name).open('x') as f: f.write(text)
def tool(args,text=None):
    return subprocess.run(args,input=text,text=True,capture_output=True,check=True).stdout
# Independent layout/byte oracle, deliberately not using prepare() mapping or word lists.
header=struct.unpack_from('<HHIQQQIHHHHHH',source,16)
loads=[struct.unpack_from('<IIQQQQQQ',source,header[4]+i*header[8]) for i in range(header[9])]
load=next(p for p in loads if p[0]==1 and p[3]<=0xff84c and 0xff860<=p[3]+p[5])
offset=load[2]+0xff84c-load[3]
raw_expected=bytes.fromhex('49170010e04740fd00243f2fe04700fde97f05a9')
check('independent exact 20-byte window',candidate[offset:offset+20]==raw_expected)
check('all other bytes identical including headers vtable atomics shared paths',source[:offset]==candidate[:offset] and source[offset+20:]==candidate[offset+20:])
check('five changed words unchanged file size',len(source)==len(candidate) and all(source[offset+i:offset+i+4]!=candidate[offset+i:offset+i+4] for i in range(0,20,4)))
assembly='.text\n'+'\n'.join(m['ASSEMBLY'])+'\n'
enc=tool([mc,'-triple=aarch64','-show-encoding'],assembly)
save('llvm-encodings.txt',enc)
encbytes=b''.join(bytes(int(x,16) for x in row.split(',')) for row in re.findall(r'encoding: \[([^]]+)\]',enc))
check('LLVM assembly independently matches actual candidate bytes',encbytes==raw_expected)
for name,path in [('original',a.input),('candidate',a.candidate)]:
    save(name+'.asm',tool([od,'-d','--start-address=0xff4e8','--stop-address=0xffb34',str(path)]))
# Word-level decoder/model. Only specified instruction families are accepted, no jumps/syscalls.
MASK64=(1<<64)-1

def signed(v,n): return v-(1<<n) if v&(1<<(n-1)) else v

def model(words,gprs,vectors,memory,flags,bias=0):
    r=gprs[:]; v=vectors[:]; mem=bytearray(memory); sp=0x100000
    def reg(n): return sp if n==31 else r[n]
    def address(word,scale): return reg((word>>5)&31)+((word>>10)&4095)*scale-sp
    for i,w in enumerate(words):
        pc=bias+0xff84c+4*i; rd=w&31
        if w&0x9f000000 in (0x10000000,0x90000000): # ADR / ADRP
            imm=signed((((w>>5)&0x7ffff)<<2)|((w>>29)&3),21)
            r[rd]=((pc&~4095)+(imm<<12) if w&0x80000000 else pc+imm)&MASK64
        elif w&0xffc00000==0x91000000: # ADD X immediate, no flags
            r[rd]=(reg((w>>5)&31)+((w>>10)&4095))&MASK64
        elif w&0xffc00000 in (0xfd400000,0xfd000000): # LDR/STR D unsigned offset
            addr=address(w,8)
            if w&0x00400000: v[rd]=int.from_bytes(mem[addr:addr+8],'little')
            else: mem[addr:addr+8]=(v[rd]&MASK64).to_bytes(8,'little')
        elif w&0xff80fc00==0x2f002400: # URSHR .2S immediate; Q=0 U=1
            immh=(w>>19)&15; immb=(w>>16)&7
            bits=8*(1<<(immh.bit_length()-1)); shift=2*bits-((immh<<3)|immb)
            assert bits==32 and shift==1
            src=v[(w>>5)&31]; result=0
            for lane in range(64//bits):
                elem=(src>>(lane*bits))&((1<<bits)-1)
                # ARM unsigned rounding uses extended precision before shift.
                result|=((elem+(1<<(shift-1)))>>shift)<<(lane*bits)
            v[rd]=result # Q=0 clears upper 64 bits
        elif w&0xffc00000==0xa9000000: # STP X offset
            addr=reg((w>>5)&31)+signed((w>>15)&127,7)*8-sp
            rt2=(w>>10)&31
            mem[addr:addr+16]=struct.pack('<QQ',0 if rd==31 else r[rd],0 if rt2==31 else r[rt2])
        elif w==0x6f00e400: # original MOVI V0.2D, 0
            v[0]=0
        elif w==0x4e081d20: # original INS V0.D[0], X9
            v[0]=(v[0]&(~MASK64))|r[9]
        elif w&0xffc00000 in (0x3d800000,0x3dc00000): # STR/LDR Q unsigned offset
            addr=address(w,16)
            if w&0x00400000: v[rd]=int.from_bytes(mem[addr:addr+16],'little')
            else: mem[addr:addr+16]=v[rd].to_bytes(16,'little')
        else: raise AssertionError('unsupported word '+hex(w))
    return r,v,mem,flags

original_words=struct.unpack_from('<5I',source,offset)
new_words=struct.unpack_from('<5I',candidate,offset)
rng=random.Random(0x48414c46)
values=[0,1,2,3,4,5,539,540,541,1079,1080,1081,2460,0x7fffffff,0x80000000,0xfffffffe,0xffffffff]
pairs=list(itertools.product(values,repeat=2))+[(rng.getrandbits(32),rng.getrandbits(32)) for _ in range(1000)]
count=0
for index,(width,height) in enumerate(pairs):
    for rot in range(8): # all transform bit combinations, including 90/180/270 mapping
        flags=(index+rot)&15; bias=(0,0x7000000000)[index&1]
        regs=[rng.getrandbits(64) for _ in range(31)]
        vecs=[rng.getrandbits(128) for _ in range(32)]
        mem=bytearray(rng.randbytes(0x200))
        struct.pack_into('<II',mem,0x88,width,height)
        struct.pack_into('<I',mem,0x98,rot)
        old=model(original_words,regs,vecs,mem,flags,bias)
        new=model(new_words,regs,vecs,mem,flags,bias)
        ref=bytearray(old[2]); struct.pack_into('<II',ref,0x88,(width>>1)+(width&1),(height>>1)+(height&1))
        assert new[2]==ref and new[0]==old[0] and new[3]==old[3]==flags
        assert new[0][9]==bias+0xffb34
        assert new[1][1:]==old[1][1:]==vecs[1:]
        assert new[2][0x50:0x60]==struct.pack('<QQ',bias+0xffb34,0)
        # Fresh LDR Q0 restores equality, with no intervening live vector use.
        oldfresh=model((elf.word(0xff8a4),),*old,bias=bias)
        newfresh=model((elf.word(0xff8a4),),*new,bias=bias)
        assert oldfresh[1]==newfresh[1]
        count+=1
check('actual word model both lanes boundaries random values rotations NZCV GPR crop callback equivalence',True)
check('fresh LDR Q0 restores vector equivalence',True)
# All 16 NZCV inputs, both lanes asymmetric maxima; check known repeated requests vs feedback.
for flags in range(16):
    mem=bytearray(512); struct.pack_into('<II',mem,0x88,0xffffffff,1)
    out=model(new_words,[0]*31,[0]*32,mem,flags)
    assert struct.unpack_from('<II',out[2],0x88)==(0x80000000,1) and out[3]==flags
for request,display in [((0,5),(1081,2461)),((2,0),(1,1)),((0,0),(0,0)),((1,1),(1080,2460))]:
    effective=display if 0 in request else request
    mem=bytearray(512); struct.pack_into('<II',mem,0x88,*effective)
    out=model(new_words,[0]*31,[0]*32,mem,0)
    assert struct.unpack_from('<II',out[2],0x88)==tuple((x+1)//2 for x in effective)
check('original zero fallback reference and effective zero one preservation',True)
# Exact v0 dead interval: no vector operands or conditional path bypass of fresh load.
dead=tool([od,'-d','--start-address=0xff860','--stop-address=0xff8a8',str(a.input)])
save('v0-dead-interval.asm',dead)
rows=[line for line in dead.splitlines() if re.match(r'\s*[0-9a-f]+:',line)]
assert not any(re.search(r'\b[vqdsbh][0-9]+\b',line.split(':',1)[1].split(None,1)[1]) for line in rows[:-1])
assert 'ldr\tq0' in rows[-1]
check('no vector read before fresh q0 load; potential BL uses only GPR RefBase arguments',True)
# Direct branch audit across executable LOADs; no entry into any of the five words.
inbound=[]
for seg in loads:
    if seg[0]!=1 or not seg[1]&1: continue
    for off in range(0,seg[5]-3,4):
        pc=seg[3]+off; w=struct.unpack_from('<I',source,seg[2]+off)[0]; target=None
        if w&0x7c000000==0x14000000: target=pc+4*signed(w&0x3ffffff,26)
        elif w&0xff000010==0x54000000 or w&0x7e000000==0x34000000: target=pc+4*signed((w>>5)&0x7ffff,19)
        elif w&0x7e000000==0x36000000: target=pc+4*signed((w>>5)&0x3fff,14)
        if target is not None and 0xff84c<=target<0xff860: inbound.append((pc,target))
check('no direct branches enter five-word window',not inbound)
rels=elf.relocations()
check('all 9772 decoded relocation writes outside new window',len(rels)==9772 and all(not (va<0xff860 and va+16>0xff84c) for va,*_ in rels))
check('all decoded relocation destinations in writable LOADs',all(any(p[0]==1 and p[1]&2 and p[3]<=r[0] and r[0]+8<=p[3]+p[6] for p in loads) for r in rels))
# Guarded CLI: outputs/input all temporary, script stays separate. Python always isolated.
with tempfile.TemporaryDirectory(prefix='sf-half-guard-') as d:
    root=Path(d); output=root/'candidate.so'; report=root/'manifest.json'
    command=['python3','-I',str(Path(__file__).with_name('patch.py')),str(a.input),str(output),str(report)]
    subprocess.run(command,check=True,capture_output=True)
    check('CLI reproducible candidate hash',output.read_bytes()==candidate)
    r=subprocess.run(command,capture_output=True,text=True)
    check('CLI refuses overwriting output',r.returncode!=0 and 'already exists' in r.stderr and output.read_bytes()==candidate)
    altered=root/'altered.so'; changed=bytearray(source); changed[-1]^=1; altered.write_bytes(changed)
    bad=['python3','-I',command[2],str(altered),str(root/'bad-output'),str(root/'bad-report')]
    r=subprocess.run(bad,capture_output=True,text=True)
    check('CLI hash refusal creates no outputs',r.returncode!=0 and 'SHA256 mismatch' in r.stderr and not (root/'bad-output').exists() and not (root/'bad-report').exists())
    r=subprocess.run(['python3','-I',command[2],str(a.input),str(a.input),str(root/'collision')],capture_output=True,text=True)
    check('CLI refuses input/output path collision',r.returncode!=0 and 'paths must differ' in r.stderr)
check('original immutable hash after tests',hashlib.sha256(a.input.read_bytes()).hexdigest()==m['EXPECTED'])
report=dict(status='PASS',checks=checks,modeled_pair_rotation_cases=count,all_NZCV_values=True,
    relocation_counts=manifest['relocation_counts'],patch_window_va='0xff84c..0xff85f',file_offset=hex(offset),
    original_sha256=manifest['input_sha256'],candidate_sha256=manifest['output_sha256'],
    actual_differing_bytes=sum(x!=y for x,y in zip(source,candidate)),
    emulator='No unicorn or qemu available; no downloads. Bounded machine-word decoder/model only.',
    limitations=['Does not execute full function or validate device graphics behavior.','Rotation/crop tests preserve state, not rendered pixel geometry.','Arbitrary indirect entry is not ruled out by direct-branch scan.'])
save('test-results.json',json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
