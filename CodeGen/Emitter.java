/*
 * Emitter.java    15 -|- MAY -|- 2016
 * Jingling Xue, School of Computer Science, UNSW, Australia
 */

// A new frame object is created for every function just before the
// function is being translated in visitFuncDecl.
//
// All the information about the translation of a function should be
// placed in this Frame object and passed across the AST nodes as the
// 2nd argument of every visitor method in Emitter.java.

package VC.CodeGen;

import java.util.LinkedList;
import java.util.Enumeration;
import java.util.ListIterator;

import VC.ASTs.*;
import VC.ErrorReporter;
import VC.StdEnvironment;

public final class Emitter implements Visitor {

    private ErrorReporter errorReporter;
    private String inputFilename;
    private String classname;
    private String outputFilename;

    public Emitter(String inputFilename, ErrorReporter reporter) {
        this.inputFilename = inputFilename;
        errorReporter = reporter;

        int i = inputFilename.lastIndexOf('.');
        if (i > 0)
            classname = inputFilename.substring(0, i);
        else
            classname = inputFilename;

    }

    // PRE: ast must be a Program node

    public final void gen(AST ast) {
        ast.visit(this, null);
        JVM.dump(classname + ".j");
    }

    // Programs
    public Object visitProgram(Program ast, Object o) {
        /** This method works for scalar variables only. You need to modify
         it to handle all array-related declarations and initialisations.
         **/

        // Generates the default constructor initialiser
        emit(JVM.CLASS, "public", classname);
        emit(JVM.SUPER, "java/lang/Object");

        emit("");

        // Three subpasses:

        // (1) Generate .field definition statements since
        //     these are required to appear before method definitions
        List list = ast.FL;
        while (!list.isEmpty()) {
            DeclList dlAST = (DeclList) list;
            if (dlAST.D instanceof GlobalVarDecl) {
                GlobalVarDecl vAST = (GlobalVarDecl) dlAST.D;
                if (vAST.T.isArrayType()) {
                    emit(JVM.STATIC_FIELD, vAST.I.spelling, vAST.T.toString());
                } else {
                    emit(JVM.STATIC_FIELD, vAST.I.spelling, VCtoJavaType(vAST.T));
                }
            }
            list = dlAST.DL;
        }

        emit("");

        // (2) Generate <clinit> for global variables (assumed to be static)

        emit("; standard class static initializer ");
        emit(JVM.METHOD_START, "static <clinit>()V");
        emit("");

        // create a Frame for <clinit>

        Frame frame = new Frame(false);

        list = ast.FL;
        while (!list.isEmpty()) {
            DeclList dlAST = (DeclList) list;
            if (dlAST.D instanceof GlobalVarDecl) {
                GlobalVarDecl vAST = (GlobalVarDecl) dlAST.D;
                // array
                if (vAST.T.isArrayType()) {
                    // init a array first
                    ArrayType tAST = (ArrayType) vAST.T;
                    int size = Integer.parseInt(((IntExpr) tAST.E).IL.spelling);
                    emitICONST(size);
                    frame.push();
                    emit(JVM.NEWARRAY, tAST.T.toString());
                    if (!vAST.E.isEmptyExpr()) {
                        vAST.E.visit(this, frame);
                    }
                    emitPUTSTATIC(tAST.toString(), vAST.I.spelling);
                    frame.pop();
                }
                // scalar
                else {
                    if (!vAST.E.isEmptyExpr()) {
                        vAST.E.visit(this, frame);
                    } else {
                        if (vAST.T.equals(StdEnvironment.floatType))
                            emit(JVM.FCONST_0);
                        else
                            emit(JVM.ICONST_0);
                        frame.push();
                    }
                    emitPUTSTATIC(VCtoJavaType(vAST.T), vAST.I.spelling);
                    frame.pop();
                }
            }
            list = dlAST.DL;
        }

        emit("");
        emit("; set limits used by this method");
        emit(JVM.LIMIT, "locals", frame.getNewIndex());

        emit(JVM.LIMIT, "stack", frame.getMaximumStackSize());
        emit(JVM.RETURN);
        emit(JVM.METHOD_END, "method");

        emit("");

        // (3) Generate Java bytecode for the VC program

        emit("; standard constructor initializer ");
        emit(JVM.METHOD_START, "public <init>()V");
        emit(JVM.LIMIT, "stack 1");
        emit(JVM.LIMIT, "locals 1");
        emit(JVM.ALOAD_0);
        emit(JVM.INVOKESPECIAL, "java/lang/Object/<init>()V");
        emit(JVM.RETURN);
        emit(JVM.METHOD_END, "method");

        return ast.FL.visit(this, o);
    }

    // Statements

    public Object visitStmtList(StmtList ast, Object o) {
        ast.S.visit(this, o);
        ast.SL.visit(this, o);
        return null;
    }

    public Object visitCompoundStmt(CompoundStmt ast, Object o) {
        Frame frame = (Frame) o;

        String scopeStart = frame.getNewLabel();
        String scopeEnd = frame.getNewLabel();
        frame.scopeStart.push(scopeStart);
        frame.scopeEnd.push(scopeEnd);

        emit(scopeStart + ":");
        if (ast.parent instanceof FuncDecl) {
            if (((FuncDecl) ast.parent).I.spelling.equals("main")) {
                emit(JVM.VAR, "0 is argv [Ljava/lang/String; from " + (String) frame.scopeStart.peek() + " to " + (String) frame.scopeEnd.peek());
                emit(JVM.VAR, "1 is vc$ L" + classname + "; from " + (String) frame.scopeStart.peek() + " to " + (String) frame.scopeEnd.peek());
                // Generate code for the initialiser vc$ = new classname();
                emit(JVM.NEW, classname);
                emit(JVM.DUP);
                frame.push(2);
                emit("invokenonvirtual", classname + "/<init>()V");
                frame.pop();
                emit(JVM.ASTORE_1);
                frame.pop();
            } else {
                emit(JVM.VAR, "0 is this L" + classname + "; from " + (String) frame.scopeStart.peek() + " to " + (String) frame.scopeEnd.peek());
                ((FuncDecl) ast.parent).PL.visit(this, o);
            }
        }
        ast.DL.visit(this, o);
        ast.SL.visit(this, o);
        emit(scopeEnd + ":");

        frame.scopeStart.pop();
        frame.scopeEnd.pop();
        return null;
    }

    public Object visitIfStmt(IfStmt ast, Object o) {
        Frame frame = (Frame) o;
        String elseLable = frame.getNewLabel();
        String nextLable = frame.getNewLabel();

        ast.E.visit(this, frame);
        emit(JVM.IFEQ, elseLable);
        frame.pop();
        ast.S1.visit(this, frame);
        emit(JVM.GOTO, nextLable);
        emit(elseLable + ":");
        ast.S2.visit(this, frame);
        emit(nextLable + ":");
        return null;
    }

    public Object visitWhileStmt(WhileStmt ast, Object o) {
        Frame frame = (Frame) o;
        String continueLable = frame.getNewLabel();
        String brkLable= frame.getNewLabel();
        frame.brkStack.push(brkLable);
        frame.conStack.push(continueLable);
        emit(continueLable + ":");
        ast.E.visit(this, frame);
        emit(JVM.IFEQ, brkLable);
        frame.pop();
        ast.S.visit(this, frame);
        emit(JVM.GOTO, continueLable);
        emit(brkLable + ":");
        frame.brkStack.pop();
        frame.conStack.pop();

        return null;
    }

    public Object visitForStmt(ForStmt ast, Object o) {
        /* for is much more complicated then while
         * since we need to keep the stk clean
         */
        Frame frame = (Frame) o;
        String startLable = frame.getNewLabel();
        String continueLable = frame.getNewLabel();
        String brkLable= frame.getNewLabel();
        frame.brkStack.push(brkLable);
        frame.conStack.push(continueLable);
        
        ast.E1.visit(this, frame);
        if(frame.getCurStackSize() == 1){
            emit(JVM.POP);
            frame.pop();
        }
        // jmp to start of the loop
        emit(JVM.GOTO, startLable);

        // set continue point, so we can do E3 every time
        emit(continueLable + ":");
        ast.E3.visit(this, frame);
        if(frame.getCurStackSize() == 1){
            emit(JVM.POP);
            frame.pop();
        }

        // start point
        emit(startLable + ":");
        if(ast.E2.isEmptyExpr()){
            // always true
            emitICONST(1);
            frame.push();
        }
        else{
            ast.E2.visit(this, frame);
        }
        // there should be at least one oprand onthe stk
        emit(JVM.IFEQ, brkLable);
        frame.pop();

        ast.S.visit(this, frame);
        emit(JVM.GOTO, continueLable);
        emit(brkLable + ":");
        frame.brkStack.pop();
        frame.conStack.pop();

        return null;
    }

    public Object visitBreakStmt(BreakStmt ast, Object o) {
        Frame frame = (Frame) o;
        emit(JVM.GOTO, frame.brkStack.peek());
        return null;
    }

    public Object visitContinueStmt(ContinueStmt ast, Object o) {
        Frame frame = (Frame) o;
        emit(JVM.GOTO, frame.conStack.peek());
        return null;
    }

    public Object visitReturnStmt(ReturnStmt ast, Object o) {
        Frame frame = (Frame) o;

/*
  int main() { return 0; } must be interpretted as 
  public static void main(String[] args) { return ; }
  Therefore, "return expr", if present in the main of a VC program
  must be translated into a RETURN rather than IRETURN instruction.
*/

        if (frame.isMain()) {
            emit(JVM.RETURN);
            return null;
        }

// Your other code goes here
        ast.E.visit(this, frame);
        if(ast.E.type.isIntType()){
            emit(JVM.IRETURN);
            frame.pop();
        }
        else if(ast.E.type.isFloatType()){
            emit(JVM.FRETURN);
            frame.pop();
        }
        else if(ast.E.type.isBooleanType()){
            emit(JVM.IRETURN);
            frame.pop();
        }

        return null;
    }


    public Object visitExprStmt(ExprStmt ast, Object o) {
        Frame frame = (Frame) o;
        ast.E.visit(this, frame);
        int stksize = frame.getCurStackSize();
        if(stksize > 1){
            System.out.println("Something wrong, stmt stk should be at most 1");
        }
        else if (stksize == 1){
            emit(JVM.POP);
            frame.pop();
        }
        else{
            // do nothing
        }
        return null;
    }

    public Object visitEmptyStmtList(EmptyStmtList ast, Object o) {
        return null;
    }

    public Object visitEmptyCompStmt(EmptyCompStmt ast, Object o) {
        return null;
    }

    public Object visitEmptyStmt(EmptyStmt ast, Object o) {
        return null;
    }

    // Expressions

    public Object visitCallExpr(CallExpr ast, Object o) {
        Frame frame = (Frame) o;
        String fname = ast.I.spelling;

        if (fname.equals("getInt")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System.getInt()I");
            frame.push();
        } else if (fname.equals("putInt")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System.putInt(I)V");
            frame.pop();
        } else if (fname.equals("putIntLn")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putIntLn(I)V");
            frame.pop();
        } else if (fname.equals("getFloat")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/getFloat()F");
            frame.push();
        } else if (fname.equals("putFloat")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putFloat(F)V");
            frame.pop();
        } else if (fname.equals("putFloatLn")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putFloatLn(F)V");
            frame.pop();
        } else if (fname.equals("putBool")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putBool(Z)V");
            frame.pop();
        } else if (fname.equals("putBoolLn")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putBoolLn(Z)V");
            frame.pop();
        } else if (fname.equals("putString")) {
            ast.AL.visit(this, o);
            emit(JVM.INVOKESTATIC, "VC/lang/System/putString(Ljava/lang/String;)V");
            frame.pop();
        } else if (fname.equals("putStringLn")) {
            ast.AL.visit(this, o);
            emit(JVM.INVOKESTATIC, "VC/lang/System/putStringLn(Ljava/lang/String;)V");
            frame.pop();
        } else if (fname.equals("putLn")) {
            ast.AL.visit(this, o); // push args (if any) into the op stack
            emit("invokestatic VC/lang/System/putLn()V");
        } else { // programmer-defined functions

            FuncDecl fAST = (FuncDecl) ast.I.decl;

            // all functions except main are assumed to be instance methods
            if (frame.isMain())
                emit("aload_1"); // vc.funcname(...)
            else
                emit("aload_0"); // this.funcname(...)
            frame.push();

            ast.AL.visit(this, o);

            String retType = VCtoJavaType(fAST.T);

            // The types of the parameters of the called function are not
            // directly available in the FuncDecl node but can be gathered
            // by traversing its field PL.

            StringBuffer argsTypes = new StringBuffer("");
            List fpl = fAST.PL;
            // while (!fpl.isEmpty()) {
            //     if (((ParaList) fpl).P.T.equals(StdEnvironment.booleanType))
            //         argsTypes.append("Z");
            //     else if (((ParaList) fpl).P.T.equals(StdEnvironment.intType))
            //         argsTypes.append("I");
            //     else
            //         argsTypes.append("F");
            //     fpl = ((ParaList) fpl).PL;
            // }
            int size = 0;
            while (!fpl.isEmpty()) {
                ParaDecl decl = ((ParaList) fpl).P;
                size++;
                if(decl.T.isArrayType()){
                    argsTypes.append("[");
                    argsTypes.append(VCtoJavaType(((ArrayType) decl.T).T));
                }
                else{
                    argsTypes.append(VCtoJavaType(decl.T));
                }
                fpl = ((ParaList) fpl).PL;
            }

            emit("invokevirtual", classname + "/" + fname + "(" + argsTypes + ")" + retType);
            frame.pop(size + 1);

            if (!retType.equals("V"))
                frame.push();
        }
        return null;
    }

    public Object visitEmptyExpr(EmptyExpr ast, Object o) {
        return null;
    }

    public Object visitIntExpr(IntExpr ast, Object o) {
        ast.IL.visit(this, o);
        return null;
    }

    public Object visitFloatExpr(FloatExpr ast, Object o) {
        ast.FL.visit(this, o);
        return null;
    }

    public Object visitBooleanExpr(BooleanExpr ast, Object o) {
        ast.BL.visit(this, o);
        return null;
    }

    public Object visitStringExpr(StringExpr ast, Object o) {
        ast.SL.visit(this, o);
        return null;
    }

    public Object visitUnaryExpr(UnaryExpr ast, Object o) {
        Type tAST;
        Frame frame = (Frame) o;
        ast.E.visit(this, frame);
        switch (ast.O.spelling) {
            case "i!":
                emit(JVM.ICONST_0);
                frame.push();
                emitIF_ICMPCOND("i==", frame);
                break;
            
            case "i+":
                break;

            case "f+":
                break;
            
            case "i-":
                emit(JVM.INEG);
                break;

            case "f-":
                emit(JVM.FNEG);
                break;
            
            case "i2f":
                emit(JVM.I2F);
                break;

            default:
                System.out.println("Should Never got here, line 402");
                break;
        }


        return null;
    }

    public Object visitBinaryExpr(BinaryExpr ast, Object o) {
        Frame frame = (Frame) o;
        if(ast.O.spelling.equals("i||") || ast.O.spelling.equals("i&&")){
            emitRELATION(ast.O.spelling, ast.E1, ast.E2, frame);
        }
        else{
            ast.E1.visit(this, o);
            ast.E2.visit(this, o);
            switch (ast.O.spelling) {
                case "i+":
                    emit(JVM.IADD);
                    frame.pop();
                    break;
                
                case "i-":
                    emit(JVM.INEG);
                    emit(JVM.IADD);
                    frame.pop();
                    break;

                case "i*":
                    emit(JVM.IMUL);
                    frame.pop();
                    break;

                case "i/":
                    emit(JVM.IDIV);
                    frame.pop();
                    break;

                case "f+":
                    emit(JVM.FADD);
                    frame.pop();
                    break;

                case "f-":
                    emit(JVM.FNEG);
                    emit(JVM.FADD);
                    frame.pop();
                    break;

                case "f*":
                    emit(JVM.FMUL);
                    frame.pop();
                    break;

                case "f/":
                    emit(JVM.FDIV);
                    frame.pop();
                    break;
                
                case "i!=":
                case "i==":
                case "i<":
                case "i<=":
                case "i>":
                case "i>=":
                    emitIF_ICMPCOND(ast.O.spelling, frame);
                    break;

                case "f!=":
                case "f==":
                case "f<":
                case "f<=":
                case "f>":
                case "f>=":
                    emitFCMP(ast.O.spelling, frame);
                    break;

                default:
                    System.out.println("Should Not Get here, line 444");
                    break;
            }
        }
        return null;
    }

    public Object visitInitExpr(InitExpr ast, Object o) {
        // Finish InitExpr here so we won't bother pass type all around
        int index = 0;
        Frame frame = (Frame) o;
        Type tAST = ((ArrayType) ((Decl) ast.parent).T).T;
        List list = ast.IL;
        while(!list.isEmptyExprList()){
            emit(JVM.DUP);
            frame.push();
            emitICONST(index);
            frame.push();
            ((ExprList) list).E.visit(this, frame);
            emitI_F_BASTORE(tAST, frame);
            list = ((ExprList) list).EL;
            index++;
        }
        return null;
    }

    public Object visitExprList(ExprList ast, Object o) {
        // never used
        return null;
    }

    public Object visitArrayExpr(ArrayExpr ast, Object o) {
        Frame frame = (Frame) o;
        ast.V.visit(this, frame);
        ast.E.visit(this, frame);
        // we might be lvalue or rvalue
        // only when we are at left side of assignment
        // we are lvalue
        boolean lvalue = false;
        if(ast.parent instanceof AssignExpr){
            if(((AssignExpr) ast.parent).E1 == ast){
                lvalue = true;
            }
        }
        if(!lvalue){
            emitI_F_BALOAD(ast.type, frame);
        }
        return null;
    }

    public Object visitVarExpr(VarExpr ast, Object o) {
        ast.V.visit(this, o);
        return null;
    }

    public Object visitAssignExpr(AssignExpr ast, Object o) {
        Frame frame = (Frame) o;
        if(ast.E1 instanceof ArrayExpr){
            ast.E1.visit(this, frame);
            ast.E2.visit(this, frame);
            emitI_F_BASTORE(ast.E2.type, frame);
        }
        else{
            Ident id = ((SimpleVar) ((VarExpr) ast.E1).V).I;
            ast.E2.visit(this, frame);
            if(ast.E2.type.isFloatType()){
                emitFSTORE(id);
            }
            else if(ast.E2.type.isBooleanType()){
                emitISTORE(id);
            }
            else if(ast.E2.type.isIntType()){
                emitISTORE(id);
            }
            else{
                System.out.println("Should never get here, ln 541");
            }
            frame.pop();
        }
        return null;
    }


    public Object visitEmptyExprList(EmptyExprList ast, Object o) {
        return null;
    }
    // Declarations

    public Object visitDeclList(DeclList ast, Object o) {
        ast.D.visit(this, o);
        ast.DL.visit(this, o);
        return null;
    }

    public Object visitEmptyDeclList(EmptyDeclList ast, Object o) {
        return null;
    }

    public Object visitFuncDecl(FuncDecl ast, Object o) {

        Frame frame;

        if (ast.I.spelling.equals("main")) {

            frame = new Frame(true);

            // Assume that main has one String parameter and reserve 0 for it
            frame.getNewIndex();

            emit(JVM.METHOD_START, "public static main([Ljava/lang/String;)V");
            // Assume implicitly that
            //      classname vc$;
            // appears before all local variable declarations.
            // (1) Reserve 1 for this object reference.

            frame.getNewIndex();

        } else {

            frame = new Frame(false);

            // all other programmer-defined functions are treated as if
            // they were instance methods
            frame.getNewIndex(); // reserve 0 for "this"

            String retType = VCtoJavaType(ast.T);

            // The types of the parameters of the called function are not
            // directly available in the FuncDecl node but can be gathered
            // by traversing its field PL.

            StringBuffer argsTypes = new StringBuffer("");
            List fpl = ast.PL;
            while (!fpl.isEmpty()) {
                ParaDecl decl = ((ParaList) fpl).P;
                if(decl.T.isArrayType()){
                    argsTypes.append("[");
                    argsTypes.append(VCtoJavaType(((ArrayType) decl.T).T));
                }
                else{
                    argsTypes.append(VCtoJavaType(decl.T));
                }
                fpl = ((ParaList) fpl).PL;
            }

            emit(JVM.METHOD_START, ast.I.spelling + "(" + argsTypes + ")" + retType);
        // set all parameter variables
        }


        ast.S.visit(this, frame);

        // JVM requires an explicit return in every method.
        // In VC, a function returning void may not contain a return, and
        // a function returning int or float is not guaranteed to contain
        // a return. Therefore, we add one at the end just to be sure.

        if (ast.T.equals(StdEnvironment.voidType)) {
            emit("");
            emit("; return may not be present in a VC function returning void");
            emit("; The following return inserted by the VC compiler");
            emit(JVM.RETURN);
        } else if (ast.I.spelling.equals("main")) {
            // In case VC's main does not have a return itself
            emit(JVM.RETURN);
        } else
            emit(JVM.NOP);

        emit("");
        emit("; set limits used by this method");
        emit(JVM.LIMIT, "locals", frame.getNewIndex());

        emit(JVM.LIMIT, "stack", frame.getMaximumStackSize());
        emit(".end method");

        return null;
    }

    public Object visitGlobalVarDecl(GlobalVarDecl ast, Object o) {
        // nothing to be done
        return null;
    }

    public Object visitLocalVarDecl(LocalVarDecl ast, Object o) {
        Frame frame = (Frame) o;
        String T;
        ast.index = frame.getNewIndex();
        if(ast.T.isArrayType()){
            T = ast.T.toString();
        }
        else{
            T = VCtoJavaType(ast.T);
        }

        emit(JVM.VAR + " " + ast.index + " is " + ast.I.spelling + " " + T + " from " + (String) frame.scopeStart.peek() + " to " + (String) frame.scopeEnd.peek());

        if(ast.T.isArrayType()){
            ArrayType tAST = (ArrayType) ast.T;
            int size = Integer.parseInt(((IntExpr) tAST.E).IL.spelling);
            emitICONST(size);
            frame.push();
            emit(JVM.NEWARRAY, tAST.T.toString());
        }
        if (!ast.E.isEmptyExpr()) {
            ast.E.visit(this, o);
            if(ast.T.isArrayType()){
                if (ast.index >= 0 && ast.index <= 3)
                    emit(JVM.ASTORE + "_" + ast.index);
                else
                    emit(JVM.ASTORE, ast.index);
                frame.pop();
            }
            else if (ast.T.equals(StdEnvironment.floatType)) {
                // cannot call emitFSTORE(ast.I) since this I is not an
                // applied occurrence
                if (ast.index >= 0 && ast.index <= 3)
                    emit(JVM.FSTORE + "_" + ast.index);
                else
                    emit(JVM.FSTORE, ast.index);
                frame.pop();
            } else {
                // cannot call emitISTORE(ast.I) since this I is not an
                // applied occurrence
                if (ast.index >= 0 && ast.index <= 3)
                    emit(JVM.ISTORE + "_" + ast.index);
                else
                    emit(JVM.ISTORE, ast.index);
                frame.pop();
            }
        }
        else{
            if(ast.T.isArrayType()){
                if (ast.index >= 0 && ast.index <= 3)
                    emit(JVM.ASTORE + "_" + ast.index);
                else
                    emit(JVM.ASTORE, ast.index);
                frame.pop();
            }
        }

        return null;
    }

    // Parameters

    public Object visitParaList(ParaList ast, Object o) {
        ast.P.visit(this, o);
        ast.PL.visit(this, o);
        return null;
    }

    public Object visitParaDecl(ParaDecl ast, Object o) {
        Frame frame = (Frame) o;
        ast.index = frame.getNewIndex();
        String T;
        if(ast.T.isArrayType()){
            T = "[" + VCtoJavaType(((ArrayType)ast.T).T);
        }
        else{
            T = VCtoJavaType(ast.T);
        }

        emit(JVM.VAR + " " + ast.index + " is " + ast.I.spelling + " " + T + " from " + (String) frame.scopeStart.peek() + " to " + (String) frame.scopeEnd.peek());
        return null;
    }

    public Object visitEmptyParaList(EmptyParaList ast, Object o) {
        return null;
    }

    // Arguments

    public Object visitArgList(ArgList ast, Object o) {
        ast.A.visit(this, o);
        ast.AL.visit(this, o);
        return null;
    }

    public Object visitArg(Arg ast, Object o) {
        ast.E.visit(this, o);
        return null;
    }

    public Object visitEmptyArgList(EmptyArgList ast, Object o) {
        return null;
    }

    // Types

    public Object visitIntType(IntType ast, Object o) {
        return null;
    }

    public Object visitFloatType(FloatType ast, Object o) {
        return null;
    }

    public Object visitBooleanType(BooleanType ast, Object o) {
        return null;
    }

    public Object visitVoidType(VoidType ast, Object o) {
        return null;
    }

    public Object visitErrorType(ErrorType ast, Object o) {
        return null;
    }

    public Object visitStringType(StringType ast, Object o) {
        return null;
    }

    public Object visitArrayType(ArrayType ast, Object o) {
        return null;
    }


    // Literals, Identifiers and Operators

    public Object visitIdent(Ident ast, Object o) {
        return null;
    }

    public Object visitIntLiteral(IntLiteral ast, Object o) {
        Frame frame = (Frame) o;
        emitICONST(Integer.parseInt(ast.spelling));
        frame.push();
        return null;
    }

    public Object visitFloatLiteral(FloatLiteral ast, Object o) {
        Frame frame = (Frame) o;
        emitFCONST(Float.parseFloat(ast.spelling));
        frame.push();
        return null;
    }

    public Object visitBooleanLiteral(BooleanLiteral ast, Object o) {
        Frame frame = (Frame) o;
        emitBCONST(ast.spelling.equals("true"));
        frame.push();
        return null;
    }

    public Object visitStringLiteral(StringLiteral ast, Object o) {
        Frame frame = (Frame) o;
        emit(JVM.LDC, "\"" + ast.spelling + "\"");
        frame.push();
        return null;
    }

    public Object visitOperator(Operator ast, Object o) {
        return null;
    }

    // Variables

    public Object visitSimpleVar(SimpleVar ast, Object o) {
        Frame frame = (Frame) o;
        Ident id = ast.I;
        Decl decl = (Decl) id.decl;
        if(decl instanceof GlobalVarDecl){
            String T;
            if(decl.T.isArrayType()){
                T = decl.T.toString();
            }
            else{
                T = VCtoJavaType(decl.T);
            }
            emitGETSTATIC(T, id.spelling);
            frame.push();
        }
        else if(decl instanceof LocalVarDecl ||
                decl instanceof ParaDecl){
            int index = decl.index;
            if(decl.T.isArrayType()){
                emitALOAD(index);
                frame.push();
            }
            else if(decl.T.isFloatType()){
                emitFLOAD(index);
                frame.push();
            }
            else{
                // bool and int
                emitILOAD(index);
                frame.push();
            }
        }
        else{
            // it should be funcdecl
        }
        return null;
    }

    // Auxiliary methods for byte code generation

    // The following method appends an instruction directly into the JVM
    // Code Store. It is called by all other overloaded emit methods.

    private void emit(String s) {
        JVM.append(new Instruction(s));
    }

    private void emit(String s1, String s2) {
        emit(s1 + " " + s2);
    }

    private void emit(String s1, int i) {
        emit(s1 + " " + i);
    }

    private void emit(String s1, float f) {
        emit(s1 + " " + f);
    }

    private void emit(String s1, String s2, int i) {
        emit(s1 + " " + s2 + " " + i);
    }

    private void emit(String s1, String s2, String s3) {
        emit(s1 + " " + s2 + " " + s3);
    }

    private void emitIF_ICMPCOND(String op, Frame frame) {
        String opcode;

        if (op.equals("i!="))
            opcode = JVM.IF_ICMPNE;
        else if (op.equals("i=="))
            opcode = JVM.IF_ICMPEQ;
        else if (op.equals("i<"))
            opcode = JVM.IF_ICMPLT;
        else if (op.equals("i<="))
            opcode = JVM.IF_ICMPLE;
        else if (op.equals("i>"))
            opcode = JVM.IF_ICMPGT;
        else // if (op.equals("i>="))
            opcode = JVM.IF_ICMPGE;

        String falseLabel = frame.getNewLabel();
        String nextLabel = frame.getNewLabel();

        emit(opcode, falseLabel);
        frame.pop(2);
        emit("iconst_0");
        emit("goto", nextLabel);
        emit(falseLabel + ":");
        emit(JVM.ICONST_1);
        frame.push();
        emit(nextLabel + ":");
    }

    private void emitFCMP(String op, Frame frame) {
        String opcode;

        if (op.equals("f!="))
            opcode = JVM.IFNE;
        else if (op.equals("f=="))
            opcode = JVM.IFEQ;
        else if (op.equals("f<"))
            opcode = JVM.IFLT;
        else if (op.equals("f<="))
            opcode = JVM.IFLE;
        else if (op.equals("f>"))
            opcode = JVM.IFGT;
        else // if (op.equals("f>="))
            opcode = JVM.IFGE;

        String falseLabel = frame.getNewLabel();
        String nextLabel = frame.getNewLabel();

        emit(JVM.FCMPG);
        frame.pop(2);
        emit(opcode, falseLabel);
        emit(JVM.ICONST_0);
        emit("goto", nextLabel);
        emit(falseLabel + ":");
        emit(JVM.ICONST_1);
        frame.push();
        emit(nextLabel + ":");

    }

    private void emitILOAD(int index) {
        if (index >= 0 && index <= 3)
            emit(JVM.ILOAD + "_" + index);
        else
            emit(JVM.ILOAD, index);
    }

    private void emitFLOAD(int index) {
        if (index >= 0 && index <= 3)
            emit(JVM.FLOAD + "_" + index);
        else
            emit(JVM.FLOAD, index);
    }

    private void emitALOAD(int index) {
        if (index >= 0 && index <= 3)
            emit(JVM.ALOAD + "_" + index);
        else
            emit(JVM.ALOAD, index);
    }

    private void emitGETSTATIC(String T, String I) {
        emit(JVM.GETSTATIC, classname + "/" + I, T);
    }

    private void emitISTORE(Ident ast) {
        int index;
        if (ast.decl instanceof ParaDecl)
            index = ((ParaDecl) ast.decl).index;
        else
            index = ((LocalVarDecl) ast.decl).index;

        if (index >= 0 && index <= 3)
            emit(JVM.ISTORE + "_" + index);
        else
            emit(JVM.ISTORE, index);
    }

    private void emitFSTORE(Ident ast) {
        int index;
        if (ast.decl instanceof ParaDecl)
            index = ((ParaDecl) ast.decl).index;
        else
            index = ((LocalVarDecl) ast.decl).index;
        if (index >= 0 && index <= 3)
            emit(JVM.FSTORE + "_" + index);
        else
            emit(JVM.FSTORE, index);
    }

    private void emitPUTSTATIC(String T, String I) {
        emit(JVM.PUTSTATIC, classname + "/" + I, T);
    }

    private void emitICONST(int value) {
        if (value == -1)
            emit(JVM.ICONST_M1);
        else if (value >= 0 && value <= 5)
            emit(JVM.ICONST + "_" + value);
        else if (value >= -128 && value <= 127)
            emit(JVM.BIPUSH, value);
        else if (value >= -32768 && value <= 32767)
            emit(JVM.SIPUSH, value);
        else
            emit(JVM.LDC, value);
    }

    private void emitFCONST(float value) {
        if (value == 0.0)
            emit(JVM.FCONST_0);
        else if (value == 1.0)
            emit(JVM.FCONST_1);
        else if (value == 2.0)
            emit(JVM.FCONST_2);
        else
            emit(JVM.LDC, value);
    }

    private void emitBCONST(boolean value) {
        if (value)
            emit(JVM.ICONST_1);
        else
            emit(JVM.ICONST_0);
    }

    private String VCtoJavaType(Type t) {
        if (t.equals(StdEnvironment.booleanType))
            return "Z";
        else if (t.equals(StdEnvironment.intType))
            return "I";
        else if (t.equals(StdEnvironment.floatType))
            return "F";
        else // if (t.equals(StdEnvironment.voidType))
            return "V";
    }

    private void emitI_F_BASTORE(Type ast, Frame frame) {
        if (ast.isFloatType()) {
            emit(JVM.FASTORE);
        } else if (ast.isIntType()) {
            emit(JVM.IASTORE);
        } else if (ast.isBooleanType()) {
            emit(JVM.BASTORE);
        } else {
            System.out.println("We don't have other arrays. Something wrong");
        }
        frame.pop(3);
    }
    private void emitI_F_BALOAD(Type ast, Frame frame){
        if (ast.isFloatType()) {
            emit(JVM.FALOAD);
        } else if (ast.isIntType()) {
            emit(JVM.IALOAD);
        } else if (ast.isBooleanType()) {
            emit(JVM.BALOAD);
        } else {
            System.out.println("We don't have other arrays. Something wrong");
        }
        frame.pop();
    }

    private void emitRELATION(String op, Expr E1, Expr E2, Frame frame){
        String opcode;
        String truecode;
        String falsecode;
        if(op.equals("i||")){
            opcode = JVM.IFNE;
            truecode = JVM.ICONST_0;
            falsecode = JVM.ICONST_1;
        }
        else{   // i&&
            opcode = JVM.IFEQ;
            truecode = JVM.ICONST_1;
            falsecode = JVM.ICONST_0;
        }

        String falseLable = frame.getNewLabel();
        String nextLabel = frame.getNewLabel();
        E1.visit(this, frame);
        emit(opcode, falseLable);
        frame.pop();
        E2.visit(this, frame);
        emit(opcode, falseLable);
        frame.pop();
        emit(truecode);
        emit("goto", nextLabel);
        emit(falseLable + ":");
        emit(falsecode);
        frame.push();
        emit(nextLabel + ":");
    }

}
