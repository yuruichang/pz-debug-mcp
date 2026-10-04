package com.yuruichang.pzdebug;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
public final class PatchCheck {
    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args[0]);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var entries=PatchCatalog.scan(jar,"fixture",hash);
        if(System.getProperty("pzdebug.patch_fixture_initialized")!=null)throw new AssertionError("Catalog initialized patch class");
        if(entries.isEmpty())throw new AssertionError("No actual annotation declarations extracted");
        for(Object item:entries) {
            var e=Json.map(item);
            if(e.get("class")==null||e.get("method")==null||Boolean.TRUE.equals(e.get("class_initialized_by_catalog")))
                throw new AssertionError("Patch metadata missing or initialized");
        }
        try{PatchCatalog.scan(jar,"fixture","00");throw new AssertionError("Changed JAR accepted");}
        catch(IllegalArgumentException expected){}
        System.out.println("Patch catalog declarations and approved hash checks passed: "+entries.size());
    }
}
