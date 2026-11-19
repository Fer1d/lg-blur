#!/usr/bin/env python3
"""Private actual lifecycle-hook candidate. No JAR/deployment. Pinned original input.
Requires full-decoded/ from actual DEX (not old candidate symlink trees).
"""
import pathlib,re,hashlib,json,sys
r=pathlib.Path(sys.argv[1]).resolve();out=r/(sys.argv[2] if len(sys.argv)>2 else 'lifecycle-smali')
assert out.parent==r and out.name.startswith('lifecycle-smali')
assert not out.exists();out.mkdir()
assert hashlib.sha256((r/'input/classes3.dex').read_bytes()).hexdigest()=='cba17f289a1934c8bfdc80e455b42c57c46312ffc7eb1139ab21ce707186082d'
for p in (r/'full-decoded').glob('*.smali'):(out/p.name).write_bytes(p.read_bytes())
# Overlay additive target bridges already pinned and generated from actual input.
for p in (r/'bridge-candidate-smali').glob('*.smali'):
 n=p.name.removeprefix('com_lge_blurengine2_')
 q=next(out.glob('*_com_lge_blurengine2_'+n));q.write_bytes(p.read_bytes())
def file(n):return next(out.glob('*_com_lge_blurengine2_'+n+'.smali'))
for n,fields,method in [('BlurInputMaker',['mImageProgram','mVideoProgram'],'landscapePrograms([I)V'),('FXBlurRenderer',['mProgram'],'landscapePrograms([I)V'),('MultiScaleBlur',['mProgram'],'landscapeProgram()I')]:
 p=file(n);s=p.read_text();c='Lcom/lge/blurengine2/'+n+';'
 if n=='MultiScaleBlur':b=f'    iget v0, p0, {c}->mProgram:I\n    return v0\n'
 else:
  b=''
  for i,f in enumerate(fields):
   index=i if n=='BlurInputMaker' else 2
   b+=f'    iget v0, p0, {c}->{f}:I\n    const/4 v1, {index}\n    aput v0, p1, v1\n'
  b+='    return-void\n'
 s+=f'\n.method public blacklist {method}\n    .locals 2\n'+b+'.end method\n';p.write_text(s)
p=file('FXBlurRenderer');s=p.read_text();s+='''
# CPU-state restore only across EGL recreation: never copy old GL names/programs.
.method public blacklist landscapeRestoreCPU(Lcom/lge/blurengine2/FXBlurRenderer;)V
    .locals 3
    if-eqz p1, :restore_done
    iget v0, p1, Lcom/lge/blurengine2/FXBlurRenderer;->mBlurLevel:F
    iput v0, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mBlurLevel:F
    iget v0, p1, Lcom/lge/blurengine2/FXBlurRenderer;->mBlendWeight:F
    iput v0, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mBlendWeight:F
    iget-object v0, p1, Lcom/lge/blurengine2/FXBlurRenderer;->mDimColor:Landroid/graphics/Color;
    iput-object v0, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mDimColor:Landroid/graphics/Color;
    iget v0, p1, Lcom/lge/blurengine2/FXBlurRenderer;->mPartial:I
    iput v0, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mPartial:I
    invoke-virtual {p0}, Lcom/lge/blurengine2/FXBlurRenderer;->getCurrentScale()I
    move-result v0
    iget-object v1, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mMultiLevelTexes:[I
    aget v2, v1, v0
    iput v2, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mLowerBlurTex:I
    add-int/lit8 v0, v0, 0x1
    aget v2, v1, v0
    iput v2, p0, Lcom/lge/blurengine2/FXBlurRenderer;->mUpperBlurTex:I
    :restore_done
    return-void
.end method
''';p.write_text(s)
p=file('BlurGLSurfaceView$GLrender');s=p.read_text();c='Lcom/lge/blurengine2/BlurGLSurfaceView$GLrender;';helper='Lcom/lge/blurengine2/LandscapeLifecycle;';view='Lcom/lge/blurengine2/BlurGLSurfaceView;'
s=s.replace('# direct methods',f'.field private blacklist landscape:{helper}\n\n# direct methods',1)
# Preserve legacy method bodies exactly, including their ART lockfix, only rename entry.
for name in ['onSurfaceCreated','onSurfaceChanged','onDrawFrame']:
 s=s.replace('.method public whitelist '+name+'(','.method private blacklist landscapeLegacy'+name+'(',1)
args={'onSurfaceCreated':'Ljavax/microedition/khronos/opengles/GL10;Ljavax/microedition/khronos/egl/EGLConfig;','onSurfaceChanged':'Ljavax/microedition/khronos/opengles/GL10;II','onDrawFrame':'Ljavax/microedition/khronos/opengles/GL10;'}
for name,sig in args.items():
 # v0 permanently holds outer monitor, p0 renderer; exception register v4 distinct.
 body=f'''    iget-object v0, p0, {c}->this$0:{view}
    invoke-virtual {{v0}}, {view}->pacerLock()Ljava/lang/Object;
    move-result-object v0
    monitor-enter v0
    :outer_start
    monitor-enter p0
    :inner_start
'''
 if name=='onSurfaceCreated':
  body+=f'''    iget-object v1, p0, {c}->landscape:{helper}
    if-nez v1, :have_controller
    new-instance v1, {helper}
    invoke-direct {{v1}}, {helper}-><init>()V
    iput-object v1, p0, {c}->landscape:{helper}
    :have_controller
    invoke-virtual {{v1}}, {helper}->created()V
    # Drop stale context names before ANY glGen; never call old graph release here.
    iget-object v3, p0, {c}->this$0:{view}
    invoke-static {{v3}}, {view}->access$400({view})Lcom/lge/blurengine2/FXBlurRenderer;
    move-result-object v5
    const/4 v2, 0x0
    iput-object v2, p0, {c}->mImageTex:Lcom/lge/blurengine2/util/Texture;
    iput-object v2, p0, {c}->mBlurInputMaker:Lcom/lge/blurengine2/BlurInputMaker;
    iput v2, p0, {c}->mVideosRenderW:I
    iput v2, p0, {c}->mVideosRenderH:I
    iget-object v3, p0, {c}->this$0:{view}
    invoke-static {{v3, v2}}, {view}->access$402({view}Lcom/lge/blurengine2/FXBlurRenderer;)Lcom/lge/blurengine2/FXBlurRenderer;
    invoke-direct {{p0, p1, p2}}, {c}->landscapeLegacyonSurfaceCreated({sig})V
    iget-object v3, p0, {c}->this$0:{view}
    invoke-static {{v3}}, {view}->access$400({view})Lcom/lge/blurengine2/FXBlurRenderer;
    move-result-object v3
    invoke-virtual {{v3, v5}}, Lcom/lge/blurengine2/FXBlurRenderer;->landscapeRestoreCPU(Lcom/lge/blurengine2/FXBlurRenderer;)V
    iget-object v1, p0, {c}->landscape:{helper}
    invoke-virtual {{v1}}, {helper}->initialized()V
'''
 elif name=='onSurfaceChanged':
  body+=f'''    iget-object v1, p0, {c}->landscape:{helper}
    if-eqz v1, :changed_return
    invoke-virtual {{v1}}, {helper}->changed()V
    invoke-direct {{p0, p1, p2, p3}}, {c}->landscapeLegacyonSurfaceChanged({sig})V
    iget-object v2, p0, {c}->this$0:{view}
    invoke-virtual {{v1, v2, p0}}, {helper}->ensure({view}{c})Z
    :changed_return
'''
 else:
  body+=f'''    iget-object v1, p0, {c}->landscape:{helper}
    if-eqz v1, :not_ready
    iget-object v2, p0, {c}->this$0:{view}
    invoke-virtual {{v1, v2, p0}}, {helper}->ensure({view}{c})Z
    move-result v1
    if-eqz v1, :not_ready
    invoke-direct {{p0, p1}}, {c}->landscapeLegacyonDrawFrame({sig})V
    goto :draw_done
    :not_ready
    # Never sample uninitialized or wrong-geometry graph. No retry for same key.
    const/4 v1, 0x0
    invoke-static {{v1, v1, v1, v1}}, Landroid/opengl/GLES20;->glClearColor(FFFF)V
    const/16 v1, 0x4000
    invoke-static {{v1}}, Landroid/opengl/GLES20;->glClear(I)V
    invoke-direct {{p0}}, {c}->changeStateAndRenderRequest()V
    :draw_done
'''
 body+='''    :inner_end
    monitor-exit p0
    :outer_end
    monitor-exit v0
    return-void
    .catchall {:inner_start .. :inner_end} :inner_fail
    .catchall {:outer_start .. :inner_start} :outer_fail
    .catchall {:inner_end .. :outer_end} :outer_fail
    :inner_fail
    move-exception v4
    monitor-exit p0
    throw v4
    :outer_fail
    move-exception v4
    monitor-exit v0
    throw v4
'''
 # Inner handler throw needs an outer catch range, avoiding leaked outer monitor.
 body=body.replace('    :inner_fail\n','    .catchall {:inner_fail .. :inner_fail_end} :outer_fail\n    :inner_fail\n').replace('    throw v4\n    :outer_fail','    throw v4\n    :inner_fail_end\n    :outer_fail',1)
 s+=f'\n.method public whitelist {name}({sig})V\n    .locals 6\n'+body+'.end method\n'
s+=f'''
.method public blacklist landscapeInvalidate()V
    .locals 1
    iget-object v0, p0, {c}->pacerIntegration:Lcom/lge/blurengine2/OwnedAcquireController;
    if-eqz v0, :done
    invoke-virtual {{v0}}, Lcom/lge/blurengine2/OwnedAcquireController;->invalidate()V
    :done
    return-void
.end method
'''
p.write_text(s)
(r/'actual-hooks.json').write_text(json.dumps({'hooks':['GLrender.onSurfaceCreated: context epoch drop/reset + legacy init + initialized','GLrender.onSurfaceChanged: invalidate key + unchanged legacy surface producer sizing + ensure','GLrender.onDrawFrame: ensure under surface->renderer before any capture/preprocess; transparent gate if not ready','source/mode/scale changes detected by primitive keys in ensure, no same-key allocations; portrait/IMAGE restore after adapted'],'shader_changes':False,'jar_created':False,'status':'ISOLATED CANDIDATE; device geometry/review gates remain'},indent=2)+'\n')
print('Generated actual lifecycle wrappers, four target bridge classes and immutable legacy method bodies')
