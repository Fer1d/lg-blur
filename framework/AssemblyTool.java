import java.nio.file.*;
import java.io.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.baksmali.*;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.Opcodes;
import brut.androlib.smali.SmaliBuilder;
/** Private DEX-only tool: no JAR or deploy operations. */
public class AssemblyTool {
 public static void main(String[] a)throws Exception {
  if(a[0].equals("decode")){
   DexBackedDexFile d=new DexBackedDexFile(Files.readAllBytes(Path.of(a[1])),0);
   BaksmaliOptions o=new BaksmaliOptions();o.localsDirective=true;o.sequentialLabels=true;o.debugInfo=true;
   for(int i=0;i<d.classCount;i++){
    ClassDef c=(ClassDef)d.classSection.get(i);String n=c.getType();
    Path p=Path.of(a[2],String.format("%05d_",i)+n.substring(1,n.length()-1).replace('/','_')+".smali");Files.createDirectories(p.getParent());
    try(Writer w=Files.newBufferedWriter(p)){new ClassDefinition(o,c).writeTo(new BaksmaliWriter(w));}
   }
   System.out.println("decoded classes="+d.classCount+" refs="+d.methodCount);
  }else if(a[0].equals("build")){
   DexBuilder d=new DexBuilder(new Opcodes(29));SmaliBuilder s=new SmaliBuilder(29);
   try(var paths=Files.walk(Path.of(a[1]))){for(Path p:paths.filter(p->p.toString().endsWith(".smali")).sorted().toList())if(!s.buildFile(p.toFile(),d))throw new Exception("assembly failed "+p);}
   FileDataStore store=new FileDataStore(new File(a[2]));try{d.writeTo(store);}finally{store.raf.close();}
  }else throw new IllegalArgumentException();
 }
}
