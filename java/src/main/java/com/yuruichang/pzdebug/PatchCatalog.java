package com.yuruichang.pzdebug;

import java.lang.classfile.*;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import java.security.MessageDigest;

/** A version-independent declaration ledger from the actual approved loaded JARs. */
final class PatchCatalog {
    private static final String PATCH="Lme/zed_0xff/zombie_buddy/Patch;";
    private static List<Object> catalog=List.of();
    private static String key="";
    private static long refreshed;
    private static final Pattern PREPARED=Pattern.compile("\\[ZB\\] patching ([\\w.$]+)\\.([\\w$*]+) with ([0-9]+) advice");
    private static final Pattern TRANSFORMED=Pattern.compile("\\[ZB\\] Transformed: ([\\w.$]+)");
    static Object parse(AnnotationValue value) {
        if(value instanceof AnnotationValue.OfString v)return v.stringValue();
        if(value instanceof AnnotationValue.OfBoolean v)return v.booleanValue();
        if(value instanceof AnnotationValue.OfConstant v)return v.resolvedValue().toString();
        return null;
    }
    static List<Object> scan(Path jar,String id,String expectedHash) throws Exception {
        if(Files.size(jar)>64*1024*1024)throw new IllegalArgumentException("JAR exceeds 64 MiB catalog limit");
        String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if(!actual.equalsIgnoreCase(expectedHash))throw new IllegalArgumentException("Approved JAR hash changed");
        List<Object> out=new ArrayList<>();
        try(ZipFile zip=new ZipFile(jar.toFile(),StandardCharsets.UTF_8)) {
            var entries=zip.entries();int classes=0;
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();
                if(!entry.getName().endsWith(".class")||entry.isDirectory())continue;
                if(++classes>10000)throw new IllegalArgumentException("Class catalog limit");
                byte[] bytes;
                try(var stream=zip.getInputStream(entry)){bytes=stream.readNBytes(1048577);}
                if(bytes.length>1048576)continue;
                ClassModel model=ClassFile.of().parse(bytes);
                var annotations=model.findAttribute(Attributes.runtimeVisibleAnnotations());
                if(annotations.isEmpty())continue;
                for(var annotation:annotations.get().annotations()) {
                    String annotationName=annotation.className().stringValue();
                    if(!annotationName.equals(PATCH)&&!annotationName.equals("Lme/zed_0xff/zombie_buddy/annotations/Patch;"))continue;
                    Map<String,Object> values=new LinkedHashMap<>();
                    for(var element:annotation.elements())values.put(element.name().stringValue(),parse(element.value()));
                    List<Object> hooks=new ArrayList<>();
                    for(var method:model.methods()) {
                        var attrs=method.findAttribute(Attributes.runtimeVisibleAnnotations());
                        if(attrs.isEmpty())continue;
                        for(var methodAnnotation:attrs.get().annotations()) {
                            String name=methodAnnotation.className().stringValue();
                            if(name.contains("Patch$OnEnter")||name.contains("Patch$OnExit")||name.contains("Advice$OnMethod"))
                                hooks.add(Json.object("method",method.methodName().stringValue(),"descriptor",method.methodType().stringValue(),"annotation",name));
                        }
                    }
                    out.add(Json.object("kind","declared_patch","mod_id",id,"jar_sha256",actual,
                        "patch_class",model.thisClass().asInternalName().replace('/','.'),
                        "class",values.get("className"),"method",values.get("methodName"),
                        "advice",values.getOrDefault("isAdvice",true),"strict_match",values.getOrDefault("strictMatch",false),
                        "warm_up",values.getOrDefault("warmUp",false),"hooks",hooks,
                        "registration_source","loaded_approved_jar_annotation","class_initialized_by_catalog",false));
                }
            }
        }
        return out;
    }
    static synchronized Object list(Object mods,int offset,int limit,String filter,Path console) {
        var metadata=Json.map(mods);
        List<?> modules=(List<?>)metadata.get("items");
        String signature=Json.encode(modules);
        List<Object> failures=new ArrayList<>();
        if(!signature.equals(key)||System.currentTimeMillis()-refreshed>10000) {
            List<Object> next=new ArrayList<>();
            for(Object item:modules) {
                var m=Json.map(item);
                if(!Boolean.TRUE.equals(m.get("decision"))||!"loaded".equals(m.get("reason")))continue;
                try {next.addAll(scan(Path.of((String)m.get("jar_path")),(String)m.get("id"),(String)m.get("sha256")));}
                catch(Exception e){next.add(Json.object("kind","catalog_error","mod_id",m.get("id"),"reason",e.getMessage()));}
            }
            catalog=next;key=signature;refreshed=System.currentTimeMillis();
        }
        Set<String> prepared=new HashSet<>(),transformed=new HashSet<>();
        try(var reader=Files.newBufferedReader(console,StandardCharsets.UTF_8)) {
            String line;long chars=0;
            while((line=reader.readLine())!=null&&chars<16*1024*1024) {
                chars+=line.length();
                Matcher p=PREPARED.matcher(line),t=TRANSFORMED.matcher(line);
                if(p.find())prepared.add(p.group(1)+"."+p.group(2));
                if(t.find())transformed.add(t.group(1));
            }
        } catch(Exception e){failures.add(Json.object("source","console","reason",e.getClass().getSimpleName()));}
        List<Object> out=new ArrayList<>();
        for(Object item:catalog) {
            var c=new LinkedHashMap<>(Json.map(item));
            String cls=String.valueOf(c.getOrDefault("class","")),method=String.valueOf(c.getOrDefault("method",""));
            if(!cls.contains(filter)&&!method.contains(filter)&&!String.valueOf(c.get("mod_id")).contains(filter))continue;
            if(c.get("kind").equals("declared_patch")) {
                c.put("engine_preparation_logged",prepared.contains(cls+"."+method));
                c.put("class_transform_reported",transformed.contains(cls));
                c.put("application_evidence",prepared.contains(cls+"."+method)&&transformed.contains(cls)?"engine_prepared_and_transformation_reported":"declaration_only");
                c.put("final_runtime_bytecode_proven",false);
            }
            out.add(c);
        }
        var result=JavaDiagnostics.page(out,offset,limit);
        result.put("target_registry_available",Boolean.TRUE.equals(metadata.get("available")));
        result.put("registry_source","approved_loaded_jar_annotations");
        result.put("source_errors",failures);
        result.put("final_runtime_bytecode_proven",false);
        return result;
    }
}
