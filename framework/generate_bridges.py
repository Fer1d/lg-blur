#!/usr/bin/env python3
"""Generate isolated ADDITIVE bridge candidates only. Never assemble or touch inputs.
No lifecycle dispatch is installed: these files are intentionally NOT deployable.
Usage: python3 -I generate_bridges.py OWNED_ROOT
"""
import hashlib, json, pathlib, re, sys
r=pathlib.Path(sys.argv[1]).resolve()
assert r.is_dir(), 'supply an existing owned workspace; exact input hashes still enforced'
assert hashlib.sha256((r/'input/classes3.dex').read_bytes()).hexdigest()=='cba17f289a1934c8bfdc80e455b42c57c46312ffc7eb1139ab21ce707186082d'
out=r/'bridge-candidate-smali'
assert not out.exists(), 'refuse overwrite; choose fresh output directory/version'
out.mkdir()
rt='Lcom/lge/blurengine2/util/RenderTarget;'
expected={
'BlurInputMaker':'c8fd9bccab079bbbb8ea233a69c89ac8e1988a065a0c3aea6521565b48c9bf93',
'FXBlurRenderer':'8344764adf959b2e6eeeaf0973be8c66fc137e5dffad2ea592b929179f33a805',
'MultiScaleBlur':'fe4cf04a2d8482f79e0176cb6ebf1d5b45eaf8c95d2c8b3b5c35ae95930df3c9',
'util_RenderTarget':'221e9330dfa0c109fac119d0cd87a9ea9e1cc92b1f6df39197ddc7e892189486'}
adds={}
for name,field in [('BlurInputMaker','mFbo'),('MultiScaleBlur','fbo')]:
 c='Lcom/lge/blurengine2/'+name+';'
 bump=f'    invoke-direct {{p0}}, {c}->reuseBump()V\n' if name=='BlurInputMaker' else ''
 adds[name]=f'''
# ISOLATED bridge: coordinator holds surface -> renderer, GL thread only.
# Prepared wrapper already validated. Never call for stale epoch.
.method public blacklist landscapeTarget(){rt}
    .locals 1
    iget-object v0, p0, {c}->{field}:{rt}
    return-object v0
.end method

.method public blacklist landscapePublish({rt}II)V
    .locals 0
    iput-object p1, p0, {c}->{field}:{rt}
    iput p2, p0, {c}->mWidth:I
    iput p3, p0, {c}->mHeight:I
{bump}    return-void
.end method
'''
adds['FXBlurRenderer']='''
# Preserves programs, float blur level, blend weight, mask, OES, dim and animation.
# Candidate texture array and lower/upper HANDLES precomputed before any store.
.method public blacklist landscapeChildren()[Lcom/lge/blurengine2/MultiScaleBlur;
    .locals 1
    iget-object v0, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mMultiScaleBlurs:[Lcom/lge/blurengine2/MultiScaleBlur;
    return-object v0
.end method

.method public blacklist landscapePublish([III)V
    .locals 0
    iput-object p1, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mMultiLevelTexes:[I
    iput p2, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mLowerBlurTex:I
    iput p3, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mUpperBlurTex:I
    invoke-direct {p0}, Lcom/lge/blurengine2/FXBlurRenderer;->reuseBump()V
    return-void
.end method
'''
adds['util_RenderTarget']='''
# New overload only. Legacy (II) constructor/create/release remain BYTECODE unchanged.
# Call ONLY with validated names and dimensions; owner ledger owns deletion/epoch.
.method public constructor blacklist <init>(IIIIZ)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput p1, p0, Lcom/lge/blurengine2/util/RenderTarget;->mWidth:I
    iput p2, p0, Lcom/lge/blurengine2/util/RenderTarget;->mHeight:I
    iput p3, p0, Lcom/lge/blurengine2/util/RenderTarget;->mTextureId:I
    iput p4, p0, Lcom/lge/blurengine2/util/RenderTarget;->mFrameBufferId:I
    return-void
.end method
'''
manifest={}
for n,h in expected.items():
 p=r/'baseline-decoded'/('com_lge_blurengine2_'+n+'.smali')
 original=p.read_text();assert hashlib.sha256(p.read_bytes()).hexdigest()==h
 # All originals preserved verbatim: no existing method/monitor/catch altered.
 result=original+adds[n]
 assert result[:len(original)]==original
 (out/p.name).write_text(result)
 manifest[n]={'original_sha256':h,'new_methods':re.findall(r'^\.method (.*)',adds[n],re.M),'existing_method_text_unchanged':True}
(r/'bridge-manifest.json').write_text(json.dumps({'status':'ISOLATED INCOMPLETE: not installed, not assembled, no lifecycle hooks','classes':manifest,'new_method_definitions':sum(len(v['new_methods']) for v in manifest.values()),'D8_union_methodrefs':'NOT RUN'},indent=2)+'\n')
print('PASS additive bridge generation: 4 private classes, 7 new methods, every existing method text unchanged; NO assembly/JAR')
