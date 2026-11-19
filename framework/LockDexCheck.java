import java.nio.file.*;
import java.io.*;
import java.util.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedTryBlock;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
public class LockDexCheck {
 static final Map<String,Set<String>> changed=Map.of(
  "Lcom/lge/blurengine2/BlurInputMaker;",Set.of("release()V","setInputTexture(I)V"),
  "Lcom/lge/blurengine2/FXBlurRenderer;",Set.of("prepareRenderTexture(IIII)V","setResizeTexture(II)V","release()V"));
 static List<Method> methods(ClassDef c){List<Method> result=new ArrayList<>();for(Object m:c.getDirectMethods())result.add((Method)m);for(Object m:c.getVirtualMethods())result.add((Method)m);return result;}
 static String name(Method m) { return m.getName()+"("+String.join("",m.getParameterTypes())+")"+m.getReturnType(); }
 static String decode(ClassDef c) throws Exception {
  BaksmaliOptions o=new BaksmaliOptions();o.localsDirective=true;o.sequentialLabels=true;o.debugInfo=true;
  StringWriter w=new StringWriter();new ClassDefinition(o,c).writeTo(new BaksmaliWriter(w));return w.toString();
 }
 static int locks(Method m) {
  MethodImplementation impl=m.getImplementation();
  TreeMap<Integer,Instruction> ins=new TreeMap<>();int pc=0;
  for(Object raw:impl.getInstructions()){Instruction i=(Instruction)raw;ins.put(pc,i);pc+=i.getCodeUnits();}
  Map<Integer,List<Integer>> states=new HashMap<>();ArrayDeque<Integer> work=new ArrayDeque<>();
  states.put(0,List.of());work.add(0);int visited=0;
  while(!work.isEmpty()){
   int at=work.remove();Instruction i=ins.get(at);if(i==null)throw new AssertionError("Invalid target "+at);
   var op=i.getOpcode();List<Integer> pre=states.get(at);ArrayList<Integer> post=new ArrayList<>(pre);visited++;
   if(op.name.equals("monitor-enter")) post.add(((OneRegisterInstruction)i).getRegisterA());
   if(op.name.equals("monitor-exit")) {
    int reg=((OneRegisterInstruction)i).getRegisterA();
    if(post.isEmpty() || post.remove(post.size()-1)!=reg)throw new AssertionError(name(m)+" monitor underflow/alias at "+at);
   }
   if((op.flags & 1)!=0 && !op.name.equals("monitor-exit")) {
    boolean all=false;
    for(Object raw:impl.getTryBlocks()){
     DexBackedTryBlock t=(DexBackedTryBlock)raw;
     if(at>=t.getStartCodeAddress()&&at<t.getStartCodeAddress()+t.getCodeUnitCount()){
      for(Object rh:t.getExceptionHandlers()){
       ExceptionHandler h=(ExceptionHandler)rh;
       enqueue(states,work,h.getHandlerCodeAddress(),pre,m);
       if(h.getExceptionType()==null)all=true;
      }
     }
    }
    if(!all && !pre.isEmpty())throw new AssertionError(name(m)+" uncaught exception with monitor at "+at);
   }
   if(op.name.startsWith("return") && !post.isEmpty())throw new AssertionError(name(m)+" return with monitor at "+at);
   if(i instanceof OffsetInstruction)enqueue(states,work,at+((OffsetInstruction)i).getCodeOffset(),post,m);
   if((op.flags & 4)!=0)enqueue(states,work,at+i.getCodeUnits(),post,m);
  }
  if(visited!=ins.size())throw new AssertionError(name(m)+" unexpected unreachable instructions "+(ins.size()-visited));
  System.out.println("PASS bytecode monitor CFG "+m.getDefiningClass()+"->"+name(m)+" instructions="+ins.size()+" code_units="+pc+" try_blocks="+impl.getTryBlocks().size());
  return visited;
 }
 static void enqueue(Map<Integer,List<Integer>> states,ArrayDeque<Integer> work,int pc,List<Integer> state,Method m){
  List<Integer> old=states.get(pc);
  if(old==null){states.put(pc,List.copyOf(state));work.add(pc);}
  else if(!old.equals(state))throw new AssertionError(name(m)+" mismatched monitor stack at "+pc+" old="+old+" incoming="+state);
 }
 public static void main(String[] args) throws Exception {
  DexBackedDexFile before=new DexBackedDexFile(Files.readAllBytes(Path.of(args[0])),0);
  DexBackedDexFile after=new DexBackedDexFile(Files.readAllBytes(Path.of(args[1])),0);
  Map<String,ClassDef> next=new HashMap<>();for(int i=0;i<after.classCount;i++){ClassDef c=(ClassDef)after.classSection.get(i);next.put(c.getType(),c);}
  int unchanged=0,checked=0,oldRejected=0;
  for(int i=0;i<before.classCount;i++){
   ClassDef b=(ClassDef)before.classSection.get(i),a=next.remove(b.getType());
   if(a==null)throw new AssertionError("Missing "+b.getType());
   if(!changed.containsKey(b.getType())){
    if(!decode(b).equals(decode(a)))throw new AssertionError("Unexpected class change "+b.getType());
    unchanged++;continue;
   }
   for(Method m:methods(a))if(changed.get(a.getType()).contains(name(m))){locks(m);checked++;}
   for(Method m:methods(b))if(changed.get(b.getType()).contains(name(m))){
    try{locks(m);}catch(AssertionError e){
     if(!e.getMessage().contains("mismatched monitor stack"))throw e;
     System.out.println("EXPECTED frozen defect: "+e.getMessage());oldRejected++;continue;
    }
    throw new AssertionError("Frozen method unexpectedly passed "+name(m));
   }
  }
  if(!next.isEmpty()||checked!=5||oldRejected!=5)throw new AssertionError("Class/method count mismatch");
  if(before.methodCount!=after.methodCount)throw new AssertionError("Method IDs changed");
  System.out.println("PASS "+unchanged+" complete classes identical; five repaired methods checked; five frozen negative controls rejected; method_ids="+after.methodCount);
 }
}
