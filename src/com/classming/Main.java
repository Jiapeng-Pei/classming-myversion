package com.classming;


import soot.*;
import soot.jimple.*;
import soot.options.Options;
import soot.util.JasminOutputStream;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static boolean initial = false;
    private static String root = "./out/production/classming/";
    private static String generated = "./sootOutput/";
    private static String target = "./target/";
    private static final String LOG_PREVIOUS = " **** Executed Line: **** ";
    public static final String MAIN_SIGN = "void main(java.lang.String[])";


    public static String generateClassPath(List<String> newPathes) {
        String pathSep = File.pathSeparator;
        String path = System.getProperty("java.class.path");
        for (String classPath :  newPathes) {
            path = classPath + pathSep + path;
        }
        return path;
    }

    public static String temporaryOutput(SootClass sClass, String tmpRoot, String tmpName) throws IOException {
        String fileName = tmpRoot + "/" + tmpName + sClass.getName();
        OutputStream streamOut = new JasminOutputStream(new FileOutputStream(fileName));
        PrintWriter writerOut = new PrintWriter(new OutputStreamWriter(streamOut));
        JasminClass jasminClass = new soot.jimple.JasminClass(sClass);
        jasminClass.print(writerOut);
        writerOut.flush();
        streamOut.close();
        return fileName;
    }


    public static void outputClassFile(SootClass sClass) throws IOException {
        String fileName = SourceLocator.v().getFileNameFor(sClass, Options.output_format_class);
        File file = new File(fileName);
        String path = file.getParent();
        File folder = new File(path);
        if (!folder.exists()) {
            createNestedFolder(folder);
        }
        OutputStream streamOut = new JasminOutputStream(new FileOutputStream(fileName));
        PrintWriter writerOut = new PrintWriter(new OutputStreamWriter(streamOut));

        JasminClass jasminClass = new soot.jimple.JasminClass(sClass);
        jasminClass.print(writerOut);
        writerOut.flush();
        streamOut.close();
    }

    private static void createNestedFolder(File folder) {
        folder.mkdirs();
    }


    public static Set<String> getAllStatementsSet(SootMethod method) {
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        Iterator iter = units.snapshotIterator();
        Set<String> result = new HashSet<>();
        while (iter.hasNext()) {
            String stmt = iter.next().toString();
            result.add(stmt.contains(LOG_PREVIOUS) ? null : stmt);
        }
        return result;
    }


    public static List<Stmt> getAllStatementsList(SootMethod method) {
        Body body = method.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        Iterator iter = units.snapshotIterator();
        List<Stmt> result = new ArrayList<>();
        while (iter.hasNext()) {
            Stmt stmt = (Stmt)iter.next();
            if (!stmt.toString().contains(LOG_PREVIOUS)) {
                result.add(stmt);
            }
//            result.add(stmt.toString().contains(LOG_PREVIOUS) ? null : stmt);
        }
        return result;
    }

    public static void injectPathCount(UnitPatchingChain units, String signature) {
        List<Stmt> targetStatements = new ArrayList<>();
        Iterator<Unit> iterator = units.snapshotIterator();
        while (iterator.hasNext()) {
            Stmt stmt = (Stmt)iterator.next();
            targetStatements.add(stmt);
        }
        int currentLine = 0;
        for (int i = 0; i < targetStatements.size(); i ++) {
            if (i + 1 < targetStatements.size()) {
                Stmt next = targetStatements.get(i + 1);
                Stmt current = targetStatements.get(i);
                if (!next.toString().contains(LOG_PREVIOUS) && !current.toString().contains(LOG_PREVIOUS)) {
                    SootMethod log = Scene.v().getMethod("<Print: void logPrint(java.lang.String)>");
                    StringConstant newSourceValue = StringConstant.v(signature + LOG_PREVIOUS + currentLine + " **** " + current.toString());
                    StaticInvokeExpr expr = Jimple.v().newStaticInvokeExpr(log.makeRef(), newSourceValue);
//                    expr.setArg(1, newSourceValue);
                    units.insertAfter(Jimple.v().newInvokeStmt(expr), current);
                }
                if (!current.toString().contains(LOG_PREVIOUS)) {
                    currentLine ++;
                }
            }
        }
    }

    public static void initial(String[] args) {
        List<String> pathes = new ArrayList<>();
//        pathes.add(root);
        pathes.add(generated);
//        pathes.add(target);
        Options.v().parse(args);
        Options.v().set_soot_classpath(generateClassPath(pathes));
        Scene.v().loadNecessaryClasses();

        Options.v().set_keep_line_number(true);
        SootClass c = Scene.v().forceResolve("Print", SootClass.BODIES);
        List<SootMethod> d = c.getMethods();
        for (SootMethod method : d) {
            method.retrieveActiveBody();
        }
    }

    public static Set<String> getExecutedLiveInstructions(String className, String signature, String[] args) throws IOException {
        Set<String> usedStmt = new HashSet<>();
        String cmd = "java -classpath \"" + generated + "\" " + className;
        if (args != null && args.length != 0) {
            for (String arg: args) {
                cmd += " " + arg + " ";
            }
        }
        Process p = Runtime.getRuntime().exec(cmd);
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = null;
        while ((line = br.readLine()) != null) {
            if (line.contains(LOG_PREVIOUS) && line.contains(signature)) {
                String[] elements = line.split("[*]+");
                String currentStmt = elements[3].trim();
                if (!usedStmt.contains(currentStmt)) {
                    usedStmt.add(currentStmt);
                }
            }
        }
        return usedStmt;
    }

    public static List<SootMethod> getLiveMethod(Set<String> usedStmt, List<SootMethod> methods) {
        List<SootMethod> signatures = new ArrayList<>();
        Set<String> involvedMethod = new HashSet<>();
        Pattern invokePattern = Pattern.compile("[<][^:]+[:]\\s+[^>]+[>]");
        for (String stmt : usedStmt) {
            if (!stmt.contains(LOG_PREVIOUS) && stmt.contains("invoke")) {
                Matcher matcher = invokePattern.matcher(stmt);
                if (matcher.find()) {
                    String methodName = matcher.group();
                    involvedMethod.add(methodName);
                }
            }
        }
        for (SootMethod method : methods) {
            if (involvedMethod.contains(method.getSignature()) || method.getSignature().contains(MAIN_SIGN)) {
                signatures.add(method);
            }
        }
        return signatures;
    }


    public static List<Stmt> getActiveInstructions(Set<String> usedStmt, String className, String signature, String[] args) throws IOException {
        List<Stmt> activeJimpleInstructions = new ArrayList<>();
        SootClass c = Scene.v().forceResolve(className, SootClass.BODIES);
        List<SootMethod> d = c.getMethods();
        SootMethod mainMethod = null;
        for (SootMethod method : d) {
            method.retrieveActiveBody();
            String currentSignature = method.getSignature();
            if (currentSignature.contains(signature)) {
                mainMethod = method;
                break;
            }
        }
        if (mainMethod == null) {
            return null;
        }
        Body body = mainMethod.retrieveActiveBody();
        UnitPatchingChain units = body.getUnits();
        Iterator<Unit> iter = units.snapshotIterator();
        while (iter.hasNext()) {
            Stmt current = (Stmt)iter.next();
            if (usedStmt.contains(current.toString())) {
                activeJimpleInstructions.add(current);
            }
        }
        return activeJimpleInstructions;
    }

    public static SootClass loadTargetClass(String className) {
        SootClass c = Scene.v().forceResolve(className, SootClass.BODIES);
//        c.setResolvingLevel(0);
        List<SootMethod> d = c.getMethods();
        for (SootMethod method : d) {
            Body body = method.retrieveActiveBody();
            UnitPatchingChain units = body.getUnits();
            if (!initial) {
                injectPathCount(units, method.getSignature());
            }
        }
        initial = true;
        return c;
    }

    public static void main(String[] args) throws IOException {
	// write your code here
        initial(args);
//        SootClass c = loadTargetClass("com.classming.Hello");
        SootClass c = Scene.v().forceResolve("com.classming.Hello", SootClass.BODIES);
        List<SootMethod> d = c.getMethods();
        for (SootMethod method: d) {
            method.retrieveActiveBody();
        }
        SootMethod test = d.get(1);
        Body body = test.getActiveBody();
        UnitPatchingChain units = body.getUnits();

        Local newVar = Jimple.v().newLocal("_M", IntType.v());
        Value rightValue = IntConstant.v(100);
        AssignStmt assign = Jimple.v().newAssignStmt(newVar, rightValue);
        SubExpr sub = Jimple.v().newSubExpr(newVar, IntConstant.v(1));
        ConditionExpr cond = Jimple.v().newGeExpr(newVar, IntConstant.v(0));
        AssignStmt substmt = Jimple.v().newAssignStmt(newVar, sub);
        IfStmt ifGoto = Jimple.v().newIfStmt(cond, substmt);
        Iterator<Unit> iter = units.snapshotIterator();

        List<Stmt> allStmt = new ArrayList<>();
        while (iter.hasNext()) {
            allStmt.add((Stmt)iter.next());
            System.out.println(allStmt.get(allStmt.size() - 1));
        }
        System.out.println("===================================");
        body.getLocals().add(newVar);
        units.insertBefore(assign, allStmt.get(1));
        units.insertBefore(substmt, allStmt.get(1));
        units.insertBefore(ifGoto, allStmt.get(1));
        iter = units.snapshotIterator();
        while (iter.hasNext()) {
            System.out.println(iter.next().toString());
        }
//        System.out.println("===================================");
//        units.remove(assign);
//        units.remove(substmt);
//        units.remove(ifGoto);
//        iter = units.snapshotIterator();
//        while (iter.hasNext()) {
//            System.out.println(iter.next().toString());
//        }
//        System.out.println("===================================");
//        units.insertBefore(assign, allStmt.get(1));
//        units.insertBefore(substmt, allStmt.get(1));
//        units.insertBefore(ifGoto, allStmt.get(1));
//        iter = units.snapshotIterator();
//        while (iter.hasNext()) {
//            System.out.println(iter.next().toString());
//        }


//        injectPathCount(units, "void main(java.lang.String[])");
//        int gotoLine = 0, labelLine = 0, line = 0;
//        while (iter.hasNext()) {
//            Stmt stmt = (Stmt)iter.next();
//            if (stmt.toString().equals("return")) {
//                labelLine = line;
//            }
//            if (stmt.toString().equals("$r2 = <java.lang.System: java.io.PrintStream out>")) {
//                gotoLine = line;
//            }
//            System.out.println(stmt);
//            line ++;
//        }
//        iter = units.snapshotIterator();
//        line = 0;
//        Stmt nop = Jimple.v().newNopStmt();
//
//        while (iter.hasNext()) {
//            Stmt stmt = (Stmt)iter.next();
//            if (line == gotoLine) {
//                GotoStmt gotoNop = Jimple.v().newGotoStmt(nop);
//
//                units.insertBefore(gotoNop, stmt);
//            }
//            if (line == labelLine) {
//                units.insertBefore(nop, stmt);
//            }
//            line ++;
//        }
        outputClassFile(c);
//        temporaryOutput(c, "./tmp", "aaa");
//        Set<String> usedStmt1 = getExecutedLiveInstructions("com.classming.Hello", "void main(java.lang.String[])", args);
//        List<Stmt> result1 = getActiveInstructions(usedStmt1, "com.classming.Hello", "void main(java.lang.String[])", args);
//
//        Set<String> usedStmt2 = getExecutedLiveInstructions("com.classming.Hello", "void test", args);
//        List<Stmt> result2 = getActiveInstructions(usedStmt2, "com.classming.Hello", "void test", args);
        System.out.println("hello");

    }

}