import java.nio.file.*;
import java.io.*;
import java.util.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.baksmali.*;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
public class CandidateDexCheck {
 static String decode(ClassDef c)throws Exception{BaksmaliOptions o=new BaksmaliOptions();o.localsDirective=true;o.sequentialLabels=true;o.debugInfo=true;StringWriter w=new StringWriter();new ClassDefinition(o,c).writeTo(new BaksmaliWriter(w));return w.toString().replace("const-string/jumbo ","const-string ");}
 static Map<String,String> bodies(String s){Map<String,String> r=new HashMap<>();var m=java.util.regex.Pattern.compile("(?ms)^\\.method ([^\\n]+)\\n.*?^\\.end method").matcher(s);while(m.find()){String h=m.group(1);String sig=h.substring(h.lastIndexOf(' ')+1);r.put(sig,m.group());}return r;}
 public static void main(String[] a)throws Exception{
  DexBackedDexFile b=new DexBackedDexFile(Files.readAllBytes(Path.of(a[0])),0),n=new DexBackedDexFile(Files.readAllBytes(Path.of(a[1])),0);
  Map<String,ClassDef> next=new HashMap<>();for(int i=0;i<n.classCount;i++){ClassDef c=(ClassDef)n.classSection.get(i);next.put(c.getType(),c);}
  boolean noop=a.length>2 && a[2].equals("noop");
  int identical=0,methods=0,locks=0;
  Set<String> changed=Set.of("Lcom/lge/blurengine2/BlurGLSurfaceView$GLrender;","Lcom/lge/blurengine2/BlurInputMaker;","Lcom/lge/blurengine2/FXBlurRenderer;","Lcom/lge/blurengine2/MultiScaleBlur;","Lcom/lge/blurengine2/util/RenderTarget;");
  for(int i=0;i<b.classCount;i++){
   ClassDef old=(ClassDef)b.classSection.get(i),cur=next.remove(old.getType());if(cur==null)throw new AssertionError("missing class");
   String x=decode(old),y=decode(cur);
   if(noop){if(!x.equals(y))throw new AssertionError("no-op semantic mismatch "+old.getType());identical++;continue;}
   if(!changed.contains(old.getType())){if(!x.equals(y))throw new AssertionError("unexpected change "+old.getType());identical++;continue;}
   Map<String,String> orig=bodies(x),now=bodies(y);
   for(var e:orig.entrySet()){
    String key=e.getKey(),value=e.getValue();
    if(old.getType().endsWith("$GLrender;")&&(key.startsWith("onSurfaceCreated(")||key.startsWith("onSurfaceChanged(")||key.startsWith("onDrawFrame("))){
     String name=key.substring(0,key.indexOf('('));key="landscapeLegacy"+key;
     value=value.replace(".method public whitelist "+name+"(",".method private blacklist landscapeLegacy"+name+"(");
    }
    String actual=now.get(key);
    // A mixed hidden-api section makes implicit allowlist flags explicit on reassembly.
    if(old.getType().equals("Lcom/lge/blurengine2/util/RenderTarget;") && actual!=null)actual=actual.replace(" whitelist "," ");
    if(!value.equals(actual))throw new AssertionError("existing method changed "+old.getType()+" "+key);methods++;
   }
   for(Method m:LockDexCheck.methods(cur)){
    String name=m.getName();
    if(old.getType().endsWith("$GLrender;")&&Set.of("onSurfaceCreated","onSurfaceChanged","onDrawFrame").contains(name)){LockDexCheck.locks(m);locks++;}
    if((old.getType().endsWith("/BlurInputMaker;")&&Set.of("release","setInputTexture").contains(name))||(old.getType().endsWith("/FXBlurRenderer;")&&Set.of("prepareRenderTexture","setResizeTexture","release").contains(name))){LockDexCheck.locks(m);locks++;}
   }
  }
  if(next.size()!=(noop?0:6) || n.methodCount>65536)throw new AssertionError("class/budget mismatch");
  System.out.println("PASS unchanged_classes="+identical+" existing_methods_preserved="+methods+" monitor_CFGs="+locks+" added_helper_classes="+next.size()+" refs="+n.methodCount+" net="+(n.methodCount-b.methodCount)+" remaining="+(65536-n.methodCount));
 }
}
