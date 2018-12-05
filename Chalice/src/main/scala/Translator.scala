//-----------------------------------------------------------------------------
//
// Copyright (C) Microsoft Corporation.  All Rights Reserved.
//
//-----------------------------------------------------------------------------
package chalice;
import scala.util.parsing.input.Position
import scala.util.parsing.input.NoPosition

import Boogie.Proc, Boogie.NamedType, Boogie.NewBVar, Boogie.Havoc, Boogie.Stmt, Boogie.Const,
       Boogie.Decl, Boogie.Expr, Boogie.FunctionApp, Boogie.Axiom, Boogie.BVar, Boogie.BType,
       Boogie.VarExpr, Boogie.IndexedType, Boogie.Comment, Boogie.MapUpdate, Boogie.MapSelect,
       Boogie.If, Boogie.Lambda, Boogie.Trigger;

case class ErrorMessage(pos: Position, message: String)

class Translator {
  import TranslationHelper._;
  var currentClass = null: Class;
  var modules = Nil: List[String]
  var etran = new ExpressionTranslator(null);
  
  def translateProgram(decls: List[TopLevelDecl]): List[Decl] = {
    decls flatMap {
      case cl: Class => translateClass(cl)
      case ch: Channel =>
        translateClass(ChannelClass(ch)) :::
        translateWhereClause(ch)
        /* TODO: waitlevel not allowed in postcondition of things forked (or, rather, joined) */
    }
  }

  def translateClass(cl: Class): List[Decl] = {
    currentClass = cl;
    etran = new ExpressionTranslator(cl);
    var declarations: List[Decl] = Nil;
    // add module (if no added yet)
    if(modules forall {mname => ! mname.equals(cl.module)}) {
      declarations = Const(ModuleName(cl), true, ModuleType) :: declarations;
      modules = cl.module :: modules;
    }
    // add class name
    declarations = Const(className(cl).id, true, TypeName) :: declarations;
    // translate monitor invariant
    declarations = declarations ::: translateMonitorInvariant(cl.MonitorInvariants, cl.pos);
    // translate each member
    for(member <- cl.members) {
      etran.fpi.reset
      declarations = declarations ::: translateMember(member);
    }
    declarations
  }

  /**********************************************************************
  *****************            MEMBERS                  *****************
  **********************************************************************/
  
  def translateMember(member: Member): List[Decl] = {
    member match {
      case f: Field =>
        translateField(f)
      case m: Method =>
        translateMethod(m)
      case f: Function => 
        translateFunction(f)
      case pred: Predicate =>
        translatePredicate(pred)
      case inv: MonitorInvariant =>
        Nil // already dealt with before
      case _: Condition =>
        throw new NotSupportedException("not yet implemented")
      case mt: MethodTransform =>
        translateMethodTransform(mt)
      case ci: CouplingInvariant =>
        Nil
    }
  }
  
  def translateWhereClause(ch: Channel): List[Decl] = {
    
    // pick new k
    val (whereKV, whereK) = Boogie.NewBVar("whereK", treal, true)
    val whereKStmts = BLocal(whereKV) :: bassume(0.0 < whereK && 1000.0*whereK < permissionOnePercent)
    
    // check definedness of where clause
    Proc(ch.channelId + "$whereClause$checkDefinedness",
      NewBVarWhere("this", new Type(currentClass)) :: (ch.parameters map {i => Variable2BVarWhere(i)}),
      Nil,
      GlobalNames,
      DefaultPrecondition(),
      whereKStmts :::
      DefinePreInitialState :::
      InhaleWithChecking(List(ch.where), "channel where clause", whereK) :::
      // smoke test: is the where clause equivalent to false?
      (if (Chalice.smoke) {
        val a = SmokeTest.initSmokeAssert(ch.pos, "Where clause of channel " + ch.channelId + " is equivalent to false.")
        translateStatement(a.chaliceAssert, whereK)
      } else Nil)
    )
  }

  def translateMonitorInvariant(invs: List[MonitorInvariant], pos: Position): List[Decl] = {
    val (h0V, h0) = NewBVar("h0", theap, true);
    val (m0V, m0) = NewBVar("m0", tmask, true);
    val (sm0V, sm0) = NewBVar("sm0", tmask, true);
    val (c0V, c0) = NewBVar("c0", tcredits, true);
    val (h1V, h1) = NewBVar("h1", theap, true);
    val (m1V, m1) = NewBVar("m1", tmask, true);
    val (sm1V, sm1) = NewBVar("sm1", tmask, true);
    val (c1V, c1) = NewBVar("c1", tcredits, true);
    val (lkV, lk) = NewBVar("lk", tref, true);
    
    // pick new k
    val (methodKV, methodK) = Boogie.NewBVar("methodK", treal, true)
    val methodKStmts = BLocal(methodKV) :: bassume(0.0 < methodK && 1000.0*methodK < permissionOnePercent)
    
    val oldTranslator = new ExpressionTranslator(Globals(h1, m1, sm1, c1), Globals(h0, m0, sm0, c0), currentClass);
    Proc(currentClass.id + "$monitorinvariant$checkDefinedness",
      List(NewBVarWhere("this", new Type(currentClass))),
      Nil,
      GlobalNames,
      DefaultPrecondition(),
        methodKStmts :::
        BLocal(h0V) :: BLocal(m0V) :: BLocal(sm0V) :: BLocal(c0V) :: BLocal(h1V) :: BLocal(m1V) :: BLocal(sm1V) :: BLocal(c1V) :: BLocal(lkV) ::
        bassume(wf(h0, m0, sm0)) :: bassume(wf(h1, m1, sm1)) ::
        resetState(oldTranslator) :::
        oldTranslator.Inhale(invs map { mi => mi.e}, "monitor invariant", false, methodK) :::
        resetState(etran) :::
        // check that invariant is well-defined
        etran.WhereOldIs(h1, m1, sm1, c1).Inhale(invs map { mi => mi.e}, "monitor invariant", true, methodK) :::
        // smoke test: is the monitor invariant equivalent to false?
        (if (Chalice.smoke) {
          val a = SmokeTest.initSmokeAssert(pos, "Monitor invariant is equivalent to false.")
          translateStatement(a.chaliceAssert, methodK)
        } else Nil) :::
        (if (! Chalice.checkLeaks || invs.length == 0) Nil else
          // check that there are no loops among .mu permissions in monitors
          // !CanWrite[this,mu]
          bassert(!etran.CanWrite(VarExpr("this"), "mu"), invs(0).pos, "Monitor invariant is not allowed to hold write permission to this.mu") ::
          // (forall lk :: lk != null && lk != this && CanRead[lk,mu] ==>
          //   CanRead[this,mu] && Heap[this,mu] << Heap[lk,mu])
          bassert(
            (lk !=@ NullLiteral() && lk !=@ VarExpr("this") && etran.CanRead(lk, "mu")) ==>
            (etran.CanRead(VarExpr("this"), "mu") &&
             new FunctionApp("MuBelow", etran.Heap.select(VarExpr("this"), "mu"), etran.Heap.select(lk, "mu"))),
            invs(0).pos,
            "Monitor invariant can hold permission of other o.mu field only this.mu if this.mu<<o.mu")
        ) :::
        //check that invariant is reflexive
        etran.UseCurrentAsOld().Exhale(invs map {mi => (mi.e, ErrorMessage(mi.pos, "Monitor invariant might not be reflexive."))}, "invariant reflexive?", false, methodK, true) :::
        bassert(DebtCheck(), pos, "Monitor invariant is not allowed to contain debt.")
    )
  }

  def translateField(f: Field): List[Decl] = {
    Const(f.FullName, true, FieldType(f.typ.typ)) ::
    Axiom(NonPredicateField(f.FullName))
  }

  def translateFunction(f: Function): List[Decl] = {
    val myresult = BVar("result", f.out.typ);
    etran = etran.CheckTermination(! Chalice.skipTermination);
    val checkBody = f.definition match {case Some(e) => isDefined(e); case None => Nil};
    etran = etran.CheckTermination(false);
    
    // pick new k
    val (functionKV, functionK) = Boogie.NewBVar("functionK", treal, true)
    val functionKStmts = BLocal(functionKV) :: bassume(0.0 < functionK && 1000.0*functionK < permissionOnePercent)
    
    // Boogie function that represents the Chalice function
    {
      val bIns = if (f.isStatic) {
        BVar("heap", theap) :: (f.ins map Variable2BVar)
      } else {
        BVar("heap", theap) :: BVar("this", tref) :: (f.ins map Variable2BVar)
      }
      Boogie.Function(functionName(f), bIns, BVar("$myresult", f.out.typ))
    } ::
    // check definedness of the function's precondition and body
    Proc(f.FullName + "$checkDefinedness",
      {
    	val insVar = f.ins map {i => Variable2BVarWhere(i)}
    	if (!f.isStatic) {
    	  NewBVarWhere("this", new Type(currentClass)) :: insVar
    	} else {
    	  insVar
    	}
      },
      Nil,
      GlobalNames,
      DefaultPrecondition(f.isStatic),
      functionKStmts :::
      bassume(FunctionContextHeight ==@ f.height) ::
      DefinePreInitialState :::
      // check definedness of the precondition
      InhaleWithChecking(Preconditions(f.spec) map { p => (if(0 < Chalice.defaults) UnfoldPredicatesWithReceiverThis(p) else p)}, "precondition", functionK) :::
      bassume(CurrentModule ==@ VarExpr(ModuleName(currentClass))) :: // verify the body assuming that you are in the module
      // smoke test: is precondition equivalent to false?
      (if (Chalice.smoke) {
        val a = SmokeTest.initSmokeAssert(f.pos, "Precondition of function " + f.Id + " is equivalent to false.")
        translateStatement(a.chaliceAssert, functionK)
      } else Nil) :::
      // check definedness of function body
      checkBody :::
      (f.definition match {case Some(e) => BLocal(myresult) :: (Boogie.VarExpr("result") := etran.Tr(e)); case None => Nil}) :::
      // assume canCall for all recursive calls
      {
        var b: Expr = true
        f.definition match {
          case Some(e) => e transform {
            case app @ FunctionApplication(obj, id, args) if id == f.Id =>
              b = b && FunctionApp(functionName(f) + "#canCall", (obj :: args) map etran.Tr); None
            case _ => None
          }
          case _ =>
        }
        bassume(b) :: Nil
      } :::
      // check that postcondition holds
      ExhaleWithChecking(Postconditions(f.spec) map { post => ((if(0 < Chalice.defaults) UnfoldPredicatesWithReceiverThis(post) else post),
              ErrorMessage(f.pos, "Postcondition at " + post.pos + " might not hold."))}, "function postcondition", functionK, true)) ::
    // definition axiom
    (f.definition match {
      case Some(definition) => definitionAxiom(f, definition);
      case None => Nil
    }) :::
    // framing axiom (+ frame function)
    framingAxiom(f) :::
    // postcondition axiom(s)
    postconditionAxiom(f)
  }
  
  def definitionAxiom(f: Function, definition: Expression): List[Decl] = {
    val inArgs = (f.ins map {i => Boogie.VarExpr(i.UniqueName)})
    val thisArg = VarExpr("this")
    val args = if (f.isStatic) inArgs else thisArg :: inArgs;

    /////    
    
    val formalsNoHeapNoMask = if (f.isStatic) {
        (f.ins map Variable2BVar)
      } else {
        BVar("this", tref) :: (f.ins map Variable2BVar)
      }

    val formalsNoMask = BVar(HeapName, theap) :: formalsNoHeapNoMask
    val formals = BVar(MaskName, tmask) :: BVar(SecMaskName, tmask) :: formalsNoMask
    val applyF = FunctionApp(functionName(f), List(etran.Heap) ::: args);
    val limitedApplyF = FunctionApp(functionName(f) + "#limited", List(etran.Heap) ::: args)
    /////
    val limitedFTrigger = FunctionApp(functionName(f) + "#limited#trigger", args)
    
    val pre = Preconditions(f.spec).foldLeft(BoolLiteral(true): Expression)({ (a, b) => And(a, b) });
    val wellformed = wf(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName))
    val triggers = f.dependentPredicates map (p => new Trigger(List(limitedApplyF, wellformed, FunctionApp("#" + p.FullName+"#trigger", thisArg :: Nil))))
    /////
    val newTriggers = new Trigger(List(limitedFTrigger, wellformed) ::: (f.dependentPredicates map (p => FunctionApp("#" + p.FullName+"#trigger", thisArg :: Nil))))

    /** Limit application of the function by introducing a second (limited) function */
    val body = etran.Tr(
      if (true) { // used to be: if (f.isRecursive && ! f.isUnlimited)    ... but now we treat all functions uniformly
        val limited = Map() ++ (f.SCC zip (f.SCC map {f =>
          val result = Function(f.id + "#limited", f.ins, f.out, f.spec, None);
          result.isStatic = f.isStatic
          result.Parent = f.Parent;
          result;
        }));
        def limit: Expression => Option[Expression] = _ match {
          case app @ FunctionApplication(obj, id, args) if (f.SCC contains app.f) =>
            val result = FunctionApplication(obj transform limit, id, args map (e => e transform limit));
            result.f = limited(app.f);
            Some(result)
          case _ => None
        }
        definition transform limit;
      } else {
        definition
      }
    );

    Boogie.Function(functionName(f) + "#canCall", formalsNoHeapNoMask, BVar("$myresult", tbool)) ::
    /* axiom (forall h: HeapType, m, sm: MaskType, this: ref, x_1: t_1, ..., x_n: t_n ::
         wf(h, m, sm) && CurrentModule == module#C ==> #C.f(h, m, this, x_1, ..., x_n) == tr(body))
    */
    Axiom(new Boogie.Forall(Nil,
      formals, newTriggers :: List(new Trigger(List(applyF,wellformed))) ,
        (wellformed && (CurrentModule ==@ ModuleName(currentClass)) && etran.TrAll(pre))
        ==>
        (applyF ==@ body))) ::
    (if (true)
       // used to be:  (if (f.isRecursive)    ... but now we treat all functions uniformly
      // define the limited function (even for unlimited function since its SCC might have limited functions)
      Boogie.Function(functionName(f) + "#limited", formalsNoMask, BVar("$myresult", f.out.typ)) ::
      Axiom(new Boogie.Forall(Nil, formals,
          new Trigger(List(applyF,wellformed)) :: Nil,  
          //  new Trigger(List(applyF,wellformed)) :: triggers,  // commented, as secondary patterns seem not to be working
            (wellformed ==> (applyF ==@ limitedApplyF)))) ::
      Boogie.Function(functionName(f) + "#limited#trigger", formalsNoHeapNoMask, BVar("$myresult", tbool)) ::
      Axiom(new Boogie.Forall(Nil, formals,
            List(new Trigger(List(limitedApplyF,wellformed))),
            (wellformed ==> limitedFTrigger))) ::         
            Nil  ///// above
    else
      Nil)
  }

  def framingAxiom(f: Function): List[Decl] = {
    val pre = Preconditions(f.spec).foldLeft(BoolLiteral(true): Expression)({ (a, b) => And(a, b) });
    var hasAccessSeq = false;
    pre visit {_ match {case _: AccessSeq => hasAccessSeq = true; case _ => }}

    if (!hasAccessSeq) {
      // Encoding with heapFragment and combine
      /* function ##C.f(state, ref, t_1, ..., t_n) returns (t);
         axiom (forall h: HeapType, m, sm: MaskType, this: ref, x_1: t_1, ..., x_n: t_n ::
            wf(h, m, sm) ==> #C.f(h, m, sm, this, x_1, ..., x_n) ==  ##C.f(partialHeap, this, x_1, ..., x_n))
      */
      val partialHeap = functionDependencies(pre, etran);
      val inArgs = (f.ins map {i => Boogie.VarExpr(i.UniqueName)});
      val frameFunctionName = "#" + functionName(f);

      val args = if (!f.isStatic) VarExpr("this") :: inArgs else inArgs;
      val applyF = FunctionApp(functionName(f) + (if (f.isRecursive) "#limited" else ""), List(etran.Heap) ::: args);
      val applyFrameFunction = FunctionApp(frameFunctionName, partialHeap :: args)
      val wellformed = wf(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName))
      
      if (!f.isStatic) (
	      Boogie.Function(frameFunctionName, Boogie.BVar("state", tpartialheap) :: Boogie.BVar("this", tref) :: (f.ins map Variable2BVar), new BVar("$myresult", f.out.typ)) ::
	      Axiom(new Boogie.Forall(
	        BVar(HeapName, theap) :: BVar(MaskName, tmask) :: BVar(SecMaskName, tmask) :: BVar("this", tref) :: (f.ins map Variable2BVar),
	        new Trigger(List(applyF, wellformed)),
	          (wellformed)
	          ==>
	          (applyF ==@ applyFrameFunction))
	      )
      ) else (
          Boogie.Function(frameFunctionName, Boogie.BVar("state", tpartialheap) :: (f.ins map Variable2BVar), new BVar("$myresult", f.out.typ)) ::
	      Axiom(new Boogie.Forall(
	        BVar(HeapName, theap) :: BVar(MaskName, tmask) :: BVar(SecMaskName, tmask) :: (f.ins map Variable2BVar),
	        new Trigger(List(applyF, wellformed)),
	          (wellformed)
	          ==>
	          (applyF ==@ applyFrameFunction))
	      )
      )
    } else {
      // Encoding with universal quantification over two heaps
      /* axiom (forall h1, h2: HeapType, m1, m2, sm1, sm2: MaskType, this: ref, x_1: t_1, ..., x_n: t_n ::
            wf(h1,m1,sm1) && wf(h2,m2,sm1) && functionDependenciesEqual(h1, h2, #C.f) ==>
              #C.f(h1, m1, sm1, this, x_1, ..., x_n) == #C.f(h2, m2, sm2, this, x_1, ..., x_n)
       */
      var args = if (!f.isStatic) VarExpr("this") :: (f.ins map {i => Boogie.VarExpr(i.UniqueName)})
                 else (f.ins map {i => Boogie.VarExpr(i.UniqueName)})

      // create two heaps
      val (globals1V, globals1) = etran.FreshGlobals("a"); val etran1 = new ExpressionTranslator(globals1, currentClass);
      val (globals2V, globals2) = etran.FreshGlobals("b"); val etran2 = new ExpressionTranslator(globals2, currentClass);
      val List(heap1, mask1, secmask1, _) = globals1V;
      val List(heap2, mask2, secmask2, _) = globals2V;
      val apply1 = FunctionApp(functionName(f), etran1.Heap :: args)
      val apply2 = FunctionApp(functionName(f), etran2.Heap :: args)
      val wellformed1 = wf(etran1.Heap, etran1.Mask, etran1.SecMask)
      val wellformed2 = wf(etran2.Heap, etran2.Mask, etran2.SecMask)
      
      if (!f.isStatic) (
	      Axiom(new Boogie.Forall(
	        heap1 :: heap2 :: mask1 :: mask2 :: secmask1 :: secmask2 :: BVar("this", tref) :: (f.ins map Variable2BVar),
	        new Trigger(List(apply1, apply2, wellformed1, wellformed2)),
	          (wellformed1 && wellformed2 && functionDependenciesEqual(pre, etran1, etran2))
	          ==>
	          (apply1 ==@ apply2)
	      ))
      ) else (
         Axiom(new Boogie.Forall(
	        heap1 :: heap2 :: mask1 :: mask2 :: secmask1 :: secmask2 :: (f.ins map Variable2BVar),
	        new Trigger(List(apply1, apply2, wellformed1, wellformed2)),
	          (wellformed1 && wellformed2 && functionDependenciesEqual(pre, etran1, etran2))
	          ==>
	          (apply1 ==@ apply2)
	      ))
      )
    }
  }
  
  def postconditionAxiom(f: Function): List[Decl] = {
    /* axiom (forall h: HeapType, m, sm: MaskType, this: ref, x_1: t_1, ..., x_n: t_n ::
          wf(h, m, sm) && (CanAssumeFunctionDefs || f.height < FunctionContextHeight) ==> Q[#C.f(h, m, this, x_1, ..., x_n)/result]
    */
    val inArgs = (f.ins map {i => Boogie.VarExpr(i.UniqueName)});
    val myresult = Boogie.BVar("result", f.out.typ);
    val args = VarExpr("this") :: inArgs;
    val applyFLimited = FunctionApp(functionName(f)+"#limited", List(VarExpr(HeapName)) ::: args)
    val canCall = FunctionApp(functionName(f) + "#canCall", args)
    val wellformed = wf(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName))
    
    //postcondition axioms
    (Postconditions(f.spec) map { post : Expression =>
      Axiom(new Boogie.Forall(
        BVar(HeapName, theap) :: BVar(MaskName, tmask) :: BVar(SecMaskName, tmask) :: BVar("this", tref) :: (f.ins map Variable2BVar),
        new Trigger(List(applyFLimited, wellformed)),
        (wellformed && (CanAssumeFunctionDefs || f.height < FunctionContextHeight || canCall))
          ==>
        etran.Tr(SubstResult(post, f.apply(ExplicitThisExpr(), f.ins map { arg => new VariableExpr(arg) })))
        ))
     })
  }

  def translatePredicate(pred: Predicate): List[Decl] = {
    
    // pick new k
    val (predicateKV, predicateK) = Boogie.NewBVar("predicateK", treal, true)
    val predicateKStmts = BLocal(predicateKV) :: bassume(0.0 < predicateK && 1000.0*predicateK < permissionOnePercent)
    
    // const unique class.name: HeapType;
    Const(pred.FullName, true, FieldType(tint)) ::
    Const(pred.FullName+"#m", true, FieldType(tpmask)) ::
    Axiom(PredicateField(pred.FullName)) ::
    // axiom PredicateField(f);
    Axiom(FunctionApp("predicateMaskField", List(VarExpr(pred.FullName))) ==@ VarExpr(pred.FullName + "#m")) ::
    // trigger function to unfold function definitions
    Boogie.Function("#" + pred.FullName + "#trigger", BVar("this", tref) :: Nil, BVar("$myresult", tbool)) ::
    // check definedness of predicate body
    Proc(pred.FullName + "$checkDefinedness",
      List(NewBVarWhere("this", new Type(currentClass))),
      Nil,
      GlobalNames,
      DefaultPrecondition(),
      predicateKStmts :::
      DefinePreInitialState :::
      InhaleWithChecking(List(DefinitionOf(pred)), "predicate definition", predicateK) :::
      // smoke test: is the predicate equivalent to false?
      (if (Chalice.smoke) {
        val a = SmokeTest.initSmokeAssert(pred.pos, "Predicate " + pred.FullName + " is equivalent to false.")
        translateStatement(a.chaliceAssert, predicateK)
      } else Nil)
    )
  }

  def translateMethod(method: Method): List[Decl] = {
    
    // pick new k for this method, that represents the fraction for read permissions
    val (methodKV, methodK) = Boogie.NewBVar("methodK", treal, true)
    val methodKStmts = BLocal(methodKV) :: bassume(0.0 < methodK && 1000.0*methodK < permissionOnePercent)
    
    // check definedness of the method contract
    Proc(method.FullName + "$checkDefinedness", 
      NewBVarWhere("this", new Type(currentClass)) :: (method.ins map {i => Variable2BVarWhere(i)}),
      method.outs map {i => Variable2BVarWhere(i)},
      GlobalNames,
      DefaultPrecondition(),
        methodKStmts :::
        DefinePreInitialState :::
        bassume(CanAssumeFunctionDefs) ::
        // check precondition
        InhaleWithChecking(Preconditions(method.spec), "precondition", methodK) :::
        DefineInitialState :::
        resetState(etran) :::
        // check postcondition
        InhaleWithChecking(Postconditions(method.spec), "postcondition", methodK) :::
        // check lockchange
        (LockChanges(method.spec) flatMap { lc => isDefined(lc)})) ::
    {
    etran.fpi.reset
    // check that method body satisfies the method contract
    Proc(method.FullName,
      NewBVarWhere("this", new Type(currentClass)) :: (method.ins map {i => Variable2BVarWhere(i)}),
      method.outs map {i => Variable2BVarWhere(i)},
      GlobalNames,
      DefaultPrecondition(),
        methodKStmts :::
        bassume(CurrentModule ==@ Boogie.VarExpr(ModuleName(currentClass))) ::
        bassume(CanAssumeFunctionDefs) ::
        DefinePreInitialState :::
        Inhale(Preconditions(method.spec) map { p => (if(0 < Chalice.defaults) UnfoldPredicatesWithReceiverThis(p) else p)}, "precondition", methodK) :::
        DefineInitialState :::
        translateStatements(method.body, methodK) :::
        Exhale(Postconditions(method.spec) map { p => ((if(0 < Chalice.defaults) UnfoldPredicatesWithReceiverThis(p) else p), ErrorMessage(method.pos, "The postcondition at " + p.pos + " might not hold."))}, "postcondition", methodK, true) :::
        (if(Chalice.checkLeaks) isLeaking(method.pos, "Method " + method.FullName + " might leak references.") else Nil) :::
        bassert(LockFrame(LockChanges(method.spec), etran), method.pos, "Method might lock/unlock more than allowed.") :::
        bassert(DebtCheck, method.pos, "Method body is not allowed to leave any debt."))
    }
  }

  def translateMethodTransform(mt: MethodTransform): List[Decl] = {
    // extract coupling invariants from the class pool of invariants
    val pool = mt.Parent.CouplingInvariants

    def extractInv(e: Expression): Expression = desugar(e) match {
      case And(a,b) => And(extractInv(a), extractInv(b))
      case Implies(a,b) => Implies(a, extractInv(b))
      case Access(ma, Full) if ! ma.isPredicate =>
        {for (ci <- pool; 
          if (ci.fields.contains(ma.f))) 
          yield scaleExpressionByPermission(ci.e, ci.fraction(ma.f), ma.pos)}.foldLeft(BoolLiteral(true):Expression)(And(_,_))
      case _: PermissionExpr => throw new NotSupportedException("not supported")
      case _ => BoolLiteral(true)
    }

    val preCI = Preconditions(mt.Spec).map(extractInv)
    val postCI = Postconditions(mt.refines.Spec).map(extractInv)

    // pick new k for this method, that represents the fraction for read permissions
    val (methodKV, methodK) = Boogie.NewBVar("methodK", treal, true)
    val methodKStmts = BLocal(methodKV) :: bassume(0.0 < methodK && 1000.0*methodK < permissionOnePercent)
 
    // check definedness of refinement specifications
    Proc(mt.FullName + "$checkDefinedness",
      NewBVarWhere("this", new Type(currentClass)) :: (mt.Ins map {i => Variable2BVarWhere(i)}),
      mt.Outs map {i => Variable2BVarWhere(i)},
      GlobalNames,
      DefaultPrecondition(),
        methodKStmts :::
        DefinePreInitialState :::
        bassume(CanAssumeFunctionDefs) ::
        // check precondition
        InhaleWithChecking(Preconditions(mt.Spec) ::: preCI, "precondition", methodK) :::
        DefineInitialState :::
        resetState(etran) :::
        // check postcondition
        InhaleWithChecking(Postconditions(mt.refines.Spec), "postcondition", methodK) :::
        tag(InhaleWithChecking(postCI ::: Postconditions(mt.spec), "postcondition", methodK), keepTag)
      ) ::
    // check correctness of refinement
    Proc(mt.FullName,
      NewBVarWhere("this", new Type(currentClass)) :: (mt.Ins map {i => Variable2BVarWhere(i)}),
      mt.Outs map {i => Variable2BVarWhere(i)},
      GlobalNames,
      DefaultPrecondition(),
        methodKStmts ::: 
        assert2assume {
          bassume(CurrentModule ==@ Boogie.VarExpr(ModuleName(currentClass))) ::
          bassume(CanAssumeFunctionDefs) ::
          DefinePreInitialState :::
          Inhale(Preconditions(mt.Spec) ::: preCI, "precondition", methodK) :::
          DefineInitialState :::
          translateStatements(mt.body, methodK) :::
          Exhale(Postconditions(mt.refines.Spec) map {p => (p, ErrorMessage(p.pos, "The postcondition at " + p.pos + " might not hold."))}, "postcondition", methodK, true) :::
          tag(Exhale(
            (postCI map {p => (p, ErrorMessage(mt.pos, "The coupling invariant might not be preserved."))}) :::
            (Postconditions(mt.spec) map {p => (p, ErrorMessage(p.pos, "The postcondition at " + p.pos + " might not hold."))}), "postcondition", methodK, true), keepTag)
        }
      )

  }

  def DebtCheck() = {
    // (forall ch :: ch == null || 0 <= Credits[ch])
    val (chV, ch) = NewBVar("ch", tref, false)
    new Boogie.Forall(chV, (ch ==@ bnull) || (0 <= new MapSelect(etran.Credits, ch)))
  }

  def DefaultPrecondition(isStatic: Boolean = false) = {
    if (!isStatic) {
      "requires this!=null;" ::
      "free requires wf(Heap, Mask, SecMask);" :: Nil
    } else {
      "free requires wf(Heap, Mask, SecMask);" :: Nil
    }
  }

  def DefinePreInitialState = {
    Comment("define pre-initial state") ::
    (etran.Mask := ZeroMask) :: (etran.SecMask := ZeroMask) :: (etran.Credits := ZeroCredits)
  }
  def DefineInitialState = {
    val (refV, ref) = Boogie.NewBVar("ref", tref, true)
    val (pmaskV, pmask) = Boogie.NewBVar("pmask", FieldType(tpmask), true)
    Comment("define initial state") ::
    bassume(etran.Heap ==@ Boogie.Old(etran.Heap)) ::
    bassume(etran.Mask ==@ Boogie.Old(etran.Mask)) ::
    bassume(etran.SecMask ==@ Boogie.Old(etran.SecMask)) ::
    bassume(etran.Credits ==@ Boogie.Old(etran.Credits)) ::
    bassume((etran.Heap.select(ref, pmask.id) ==@ ZeroPMask).forall(refV).forall(pmaskV))
  }

  /**********************************************************************
  *****************           STATEMENTS                *****************
  **********************************************************************/
  def translateStatements(statements: List[Statement], methodK: Expr): List[Stmt] = statements flatMap (v => translateStatement(v, methodK))

  def translateStatement(s: Statement, methodK: Expr): List[Stmt] = {
    s match {
      case a@Assert(e) =>
        a.smokeErrorNr match {
          case None =>
            val (tmpGlobalsV, tmpGlobals) = etran.FreshGlobals("assert");
            val tmpTranslator = new ExpressionTranslator(tmpGlobals, etran.oldEtran.globals, currentClass);        
            Comment("assert") ::
            // exhale e in a copy of the heap/mask/credits
            BLocals(tmpGlobalsV) :::
            copyState(tmpGlobals, etran) :::
            tmpTranslator.Exhale(List((e, ErrorMessage(s.pos, "Assertion might not hold."))), "assert", true, methodK, true)
          case Some(err) =>
            bassert(e, a.pos, "SMOKE-TEST-" + err + ". ("+SmokeTest.smokeWarningMessage(err)+")", 0) :: Nil
        }
      case Assume(e) =>
        Comment("assume") ::
        isDefined(e) :::
        bassume(e)
      case BlockStmt(ss) =>
        translateStatements(ss, methodK)
      case IfStmt(guard, then, els) =>
        val (condV, cond) = Boogie.NewBVar("cond", tbool, true)
        val oldConditions = etran.fpi.currentConditions
        etran.fpi.currentConditions += ((cond, true))
        val tt = translateStatement(then, methodK)
        val et = els match {
          case None => Nil
          case Some(els) =>
            etran.fpi.currentConditions = oldConditions
            etran.fpi.currentConditions += ((cond, false))
            translateStatement(els, methodK)
        }
        Comment("if") ::
        BLocal(condV) ::
        (cond := etran.Tr(guard)) ::
        isDefined(guard) :::
        Boogie.If(cond, tt, et)
      case w: WhileStmt =>
        translateWhile(w, methodK)
      case Assign(lhs, rhs) =>
        def assignOrAssumeEqual(r: Boogie.Expr): List[Boogie.Stmt] = {
          if (lhs.v.isImmutable) {
            // this must be a "ghost const"
            val name = lhs.v.UniqueName
            bassert(! VarExpr("assigned$" + name), lhs.pos, "Const variable can be assigned to only once.") ::
            bassume(lhs ==@ r) ::
            (VarExpr("assigned$" + name) := true)
          } else {
            lhs := r
          }
        }
        Comment("assigment to " + lhs.id) ::
        (rhs match {
          case rhs@NewRhs(c, initialization, lower, upper) => // x := new C;
            val (nw, ss) = translateAllocation(rhs.typ, initialization, lower, upper, rhs.pos);
            ss ::: assignOrAssumeEqual(new VarExpr(nw)) 
          case rhs: Expression => // x := E;
            isDefined(rhs) ::: assignOrAssumeEqual(rhs)
        })
      case FieldUpdate(lhs@MemberAccess(target, f), rhs) =>
        val (statements, toStore : Expr) = 
          (rhs match {
            case rhs @ NewRhs(c, initialization, lower, upper) =>
              // e.f := new C;
              val (nw,ss) = translateAllocation(rhs.typ, initialization, lower, upper, rhs.pos)
              (ss, new VarExpr(nw))
            case rhs : Expression =>
              // e.f := E; 
              (isDefined(rhs), TrExpr(rhs))
           });
        Comment("update field " + f) ::
        isDefined(target) :::
        bassert(CanWrite(target, lhs.f), s.pos, "Location might not be writable") ::
        statements ::: etran.Heap.store(target, lhs.f, toStore) :: bassume(wf(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName)))
      case lv : LocalVar =>
        translateLocalVarDecl(lv.v, false) :::
        { lv.rhs match {
          //update the local, provided a rhs was provided
          case None => Nil
          case Some(rhs) => translateStatement(Assign(new VariableExpr(lv.v), rhs), methodK) }}
      case s: SpecStmt => translateSpecStmt(s, methodK)
      case c: Call => translateCall(c, methodK)
      case Install(obj, lowerBounds, upperBounds) =>
        Comment("install") ::
        isDefined(obj) :::
        bassert(nonNull(obj), s.pos, "The target of the install statement might be null.") ::
        bassert(isHeld(obj), s.pos, "The lock of the target of the install statement might not be held.") ::
        // assert CanWrite(obj.mu); assume lowerbounds < obj.mu < upperBounds;
        UpdateMu(obj, false, false, lowerBounds, upperBounds, ErrorMessage(s.pos, "Install might fail."))
      case Share(obj, lowerBounds, upperBounds) =>
        val (preShareMaskV, preShareMask) = Boogie.NewBVar("preShareMask", tmask, true)    
        Comment("share") ::
        // remember the mask immediately before the share
        BLocal(preShareMaskV) :: Boogie.Assign(preShareMask, etran.Mask) ::
        isDefined(obj) :::
        bassert(nonNull(obj), s.pos, "The target of the share statement might be null.") ::
        UpdateMu(obj, true, false, lowerBounds, upperBounds, ErrorMessage(s.pos, "Share might fail.")) :::
        bassume(!isHeld(obj) && ! isRdHeld(obj)) :: // follows from o.mu==lockbottom
        // assume a seen state is the one right before the share
        bassume(LastSeenHeap(etran.Heap.select(obj, "mu"), etran.Heap.select(obj, "held")) ==@ etran.Heap) ::
        bassume(LastSeenMask(etran.Heap.select(obj, "mu"), etran.Heap.select(obj, "held")) ==@ preShareMask) ::
        bassume(LastSeenCredits(etran.Heap.select(obj, "mu"), etran.Heap.select(obj, "held")) ==@ etran.Credits) ::
        // exhale the monitor invariant (using the current state as the old state)
        ExhaleInvariants(obj, false, ErrorMessage(s.pos, "Monitor invariant might not hold."), etran.UseCurrentAsOld(), methodK)
      case Unshare(obj) =>
        val (heldV, held) = Boogie.NewBVar("held", Boogie.NamedType("int"), true)
        val o = TrExpr(obj)
        Comment("unshare") ::
        isDefined(obj) :::
        bassert(nonNull(o), s.pos, "The target of the unshare statement might be null.") ::
        bassert(CanWrite(o, "mu"), s.pos, "The mu field of the target of the unshare statement might not be writable.") ::
        bassert(isShared(o), s.pos, "The target of the unshare statement might not be shared.") ::
        bassert(isHeld(o), s.pos, "The target of the unshare statement might not be locked by the current thread.") :: // locked or read-locked
        etran.Heap.store(o, "mu", bLockBottom) ::
        // havoc o.held where 0<=o.held 
        BLocal(heldV) :: Boogie.Havoc(held) :: bassume(held <= 0) ::
        etran.Heap.store(o, "held", held) ::
        // set o.rdheld to false
        etran.Heap.store(o, "rdheld", false)
      case Acquire(obj) =>
        Comment("acquire") ::
        isDefined(obj) :::
        bassert(nonNull(TrExpr(obj)), s.pos, "The target of the acquire statement might be null.") ::
        TrAcquire(s, obj, methodK)
      case Release(obj) =>
        Comment("release") ::
        isDefined(obj) :::
        bassert(nonNull(TrExpr(obj)), s.pos, "The target of the release statement might be null.") ::
        TrRelease(s, obj, methodK)
      case Lock(e, body, readonly) =>
        val objV = new Variable("lock", new Type(e.typ))
        val obj = new VariableExpr(objV)
        val sname = if (readonly) "rd lock" else "lock"
        val o = TrExpr(obj)
        Comment(sname) ::
        isDefined(e) :::
        BLocal(Variable2BVar(objV)) :: (o := TrExpr(e)) ::
        bassert(nonNull(o), s.pos, "The target of the " + sname + " statement might be null.") ::
        { if (readonly) {
            TrRdAcquire(s, obj, methodK) :::
            translateStatement(body, methodK) :::
            TrRdRelease(s, obj, methodK)
          } else {
            TrAcquire(s, obj, methodK) :::
            translateStatement(body, methodK) :::
            TrRelease(s, obj, methodK)
          }
        }
      case RdAcquire(obj) =>
        Comment("rd acquire") ::
        isDefined(obj) :::
        bassert(nonNull(TrExpr(obj)), s.pos, "The target of the read-acquire statement might be null.") ::
        TrRdAcquire(s, obj, methodK)
      case rdrelease@RdRelease(obj) =>
        Comment("rd release") ::
        isDefined(obj) :::
        bassert(nonNull(TrExpr(obj)), obj.pos, "The target of the read-release statement might be null.") ::
        TrRdRelease(s, obj, methodK)
      case downgrade@Downgrade(obj) =>
        val o = TrExpr(obj);
        val prevHeapV = new Boogie.BVar("prevHeap", theap, true)
        Comment("downgrade") ::
        isDefined(obj) :::
        bassert(nonNull(o), s.pos, "The target of the downgrade statement might be null.") ::
        bassert(isHeld(o), s.pos, "The lock of the target of the downgrade statement might not be held by the current thread.") ::
        bassert(! isRdHeld(o), s.pos, "The current thread might hold the read lock.") ::
        ExhaleInvariants(obj, false, ErrorMessage(downgrade.pos, "Monitor invariant might not hold."), methodK) :::
        BLocal(prevHeapV) ::
        InhaleInvariants(obj, true, methodK) :::
        bassume(etran.Heap ==@ new Boogie.VarExpr(prevHeapV)) ::
        etran.Heap.store(o, "rdheld", true)
      case Free(obj) =>
        val o = TrExpr(obj);
        isDefined(obj) :::
        bassert(nonNull(o), s.pos, "The target of the free statement might be null.") ::
        (for (f <- obj.typ.Fields ++ RootClass.MentionableFields) yield
          bassert(CanWrite(o, f.FullName), s.pos, "The field " + f.id + " of the target of the free statement might not be writable.")) :::
        (for (f <- obj.typ.Fields ++ RootClass.MentionableFields) yield
          etran.SetNoPermission(o, f.FullName, etran.Mask))
        // probably need to havoc all the fields! Do we check enough?
      case fold@Fold(acc@Access(pred@MemberAccess(e, f), perm)) =>
        val o = TrExpr(e);
        var definition = scaleExpressionByPermission(SubstThis(DefinitionOf(pred.predicate), e), perm, fold.pos)
        val (receiverV, receiver) = Boogie.NewBVar("predRec", tref, true)
        val (versionV, version) = Boogie.NewBVar("predVer", tint, true)
        val (flagV, flag) = Boogie.NewBVar("predFlag", tbool, true)
        
        // pick new k
        val (foldKV, foldK) = Boogie.NewBVar("foldK", treal, true)
        val stmts = Comment("fold") ::
        functionTrigger(o, pred.predicate) ::
        BLocal(foldKV) :: bassume(0.0 < foldK && 1000.0*foldK < percentPermission(1) && 1000.0*foldK < methodK) ::
        isDefined(e) :::
        isDefined(perm) :::
        bassert(nonNull(o), s.pos, "The target of the fold statement might be null.") ::
        // remove the definition from the current state, and replace by predicate itself
        BLocal(receiverV) :: (receiver := o) ::
        BLocal(versionV) :: (version := etran.Heap.select(o, pred.predicate.FullName)) ::
        BLocal(flagV) :: (flag := true) ::
        etran.ExhaleAndTransferToSecMask(o, pred.predicate, List((definition, ErrorMessage(s.pos, "Fold might fail because the definition of " + pred.predicate.FullName + " does not hold."))), "fold", foldK, false, receiver, pred.predicate.FullName, version) :::
        Inhale(List(acc), "fold", foldK) :::
        etran.keepFoldedLocations(definition, o, pred.predicate, etran.Mask, etran.Heap, etran.fpi.getFoldedPredicates(pred.predicate)) :::
        bassume(wf(etran.Heap, etran.Mask, etran.SecMask))
        
        // record folded predicate
        etran.fpi.addFoldedPredicate(FoldedPredicate(pred.predicate, receiver, version, etran.fpi.currentConditions, flag))
        
        stmts
      case unfld@Unfold(acc@Access(pred@MemberAccess(e, f), perm:Permission)) =>
        val o = TrExpr(e);
        val definition = scaleExpressionByPermission(SubstThis(DefinitionOf(pred.predicate), e), perm, unfld.pos)
        
        // pick new k
        val (unfoldKV, unfoldK) = Boogie.NewBVar("unfoldK", treal, true)
        // record version of unfolded instance
        val (receiverV, receiver) = Boogie.NewBVar("predRec", tref, true)
        val (versionV, version) = Boogie.NewBVar("predVer", tint, true)
        Comment("unfold") ::
        functionTrigger(o, pred.predicate) ::
        BLocal(receiverV) :: (receiver := o) ::
        BLocal(versionV) :: (version := etran.Heap.select(o, pred.predicate.FullName)) ::
        BLocal(unfoldKV) :: bassume(0.0 < unfoldK && unfoldK < percentPermission(1) && 1000.0*unfoldK < methodK) ::
        isDefined(e) :::
        bassert(nonNull(o), s.pos, "The target of the fold statement might be null.") ::
        isDefined(perm) :::
        ExhaleDuringUnfold(List((acc, ErrorMessage(s.pos, "unfold might fail because the predicate " + pred.predicate.FullName + " does not hold."))), "unfold", unfoldK, false) :::
        etran.Inhale(List(definition), "unfold", false, unfoldK, receiver, pred.predicate.FullName, version)
      case c@CallAsync(declaresLocal, token, obj, id, args) =>
        val formalThisV = new Variable("this", new Type(c.m.Parent))
        val formalThis = new VariableExpr(formalThisV)
        val formalInsV = for (p <- c.m.ins) yield new Variable(p.id, p.t)
        val formalIns = for (v <- formalInsV) yield new VariableExpr(v)

        val (tokenV,tokenId) = NewBVar("token", tref, true)
        val (asyncStateV,asyncState) = NewBVar("asyncstate", tint, true)
        val (preCallHeapV, preCallHeap) = NewBVar("preCallHeap", theap, true)
        val (preCallMaskV, preCallMask) = NewBVar("preCallMask", tmask, true)
        val (preCallSecMaskV, preCallSecMask) = NewBVar("preCallSecMask", tmask, true)
        val (preCallCreditsV, preCallCredits) = NewBVar("preCallCredits", tcredits, true)
        val (argsSeqV, argsSeq) = NewBVar("argsSeq", tArgSeq, true)
        val argsSeqLength = 1 + args.length;
        
        // pick new k for this fork
        val (asyncMethodCallKV, asyncMethodCallK) = Boogie.NewBVar("asyncMethodCallK", treal, true)
        BLocal(asyncMethodCallKV) ::
        bassume(0.0 < asyncMethodCallK && 1000.0*asyncMethodCallK < percentPermission(1) && 1000.0*asyncMethodCallK < methodK) ::
        Comment("call " + id) ::
        // declare the local variable, if needed
        { if (c.local == null)
            List[Stmt]()
          else
            List(BLocal(Variable2BVarWhere(c.local))) } :::
        // remember the value of the heap/mask/credits
        BLocal(preCallHeapV) :: (preCallHeap := etran.Heap) ::
        BLocal(preCallMaskV) :: (preCallMask := etran.Mask) ::
        BLocal(preCallSecMaskV) :: (preCallSecMask := etran.SecMask) ::
        BLocal(preCallCreditsV) :: (preCallCredits := etran.Credits) ::
        BLocal(argsSeqV) ::
        // introduce formal parameters and pre-state globals
        (for (v <- formalThisV :: formalInsV) yield BLocal(Variable2BVarWhere(v))) :::
        // check definedness of arguments
        isDefined(obj) :::
        bassert(nonNull(obj), c.pos, "The target of the method call might be null.") ::
        (args flatMap { e: Expression => isDefined(e)}) :::
        // assign actual ins to formal ins
        (formalThis := obj) ::
        (for ((v,e) <- formalIns zip args) yield (v := e)) :::
        // insert all arguments in the argument sequence
        Boogie.AssignMap(argsSeq, 0, formalThis) ::
        { var i = 1
          for (v <- formalIns) yield { val r = Boogie.AssignMap(argsSeq, i, v); i += 1; r }
        } :::
        // exhale preconditions
        Exhale(Preconditions(c.m.spec) map
          (p => SubstVars(p, formalThis, c.m.ins, formalIns)) zip (Preconditions(c.m.spec) map { p => ErrorMessage(c.pos, "The precondition at " + p.pos + " might not hold.")}), "precondition", asyncMethodCallK, false) :::
        // create a new token
        BLocal(tokenV) :: Havoc(tokenId) :: bassume(nonNull(tokenId)) ::
        // the following assumes help in proving that the token is fresh
        bassume(etran.Heap.select(tokenId, "joinable") ==@ 0) ::
        bassume(new Boogie.MapSelect(etran.Mask, tokenId, "joinable", "perm$N")==@ 0.0) ::
        bassume(new Boogie.MapSelect(etran.Mask, tokenId, "joinable", "perm$R")==@ 0.0) ::
        etran.IncPermission(tokenId, "joinable", permissionFull) :::
        // create a fresh value for the joinable field
        BLocal(asyncStateV) :: Boogie.Havoc(asyncState) :: bassume(asyncState !=@ 0) ::
        etran.Heap.store(tokenId, "joinable", asyncState) ::
        // also store the k used for this fork, such that the same k can be used in the join
        etran.Heap.store(tokenId, forkK, asyncMethodCallK) ::
        // assume the pre call state for the token is the state before inhaling the precondition
        bassume(CallHeap(asyncState) ==@ preCallHeap) ::
        bassume(CallMask(asyncState) ==@ preCallMask) ::
        bassume(CallSecMask(asyncState) ==@ preCallSecMask) ::
        bassume(CallCredits(asyncState) ==@ preCallCredits) ::
        bassume(CallArgs(asyncState) ==@ argsSeq) :::
        // assign the returned token to the variable
        { if (token != null) List(token := tokenId) else List() } :::
        bassume(wf(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName))) :: Nil
      case jn@JoinAsync(lhs, token) =>
        val formalThisV = new Variable("this", new Type(jn.m.Parent))
        val formalThis = new VariableExpr(formalThisV)
        val formalInsV = for (p <- jn.m.ins) yield new Variable(p.id, p.t)
        val formalIns = for (v <- formalInsV) yield new VariableExpr(v)
        val formalOutsV = for (p <- jn.m.outs) yield new Variable(p.id, p.t)
        val formalOuts = for (v <- formalOutsV) yield new VariableExpr(v)

        val (argsSeqV, argsSeq) = NewBVar("argsSeq", tArgSeq, true)
        val (preCallHeapV, preCallHeap) = NewBVar("preCallHeap", theap, true);
        val (preCallMaskV, preCallMask) = NewBVar("preCallMask", tmask, true);
        val (preCallSecMaskV, preCallSecMask) = NewBVar("preCallSecMask", tmask, true);
        val (preCallCreditsV, preCallCredits) = NewBVar("preCallCredits", tcredits, true);
        val postEtran = new ExpressionTranslator(etran.globals, Globals(preCallHeap, preCallMask, preCallSecMask, preCallCredits), currentClass);
        val (asyncJoinKV, asyncJoinK) = Boogie.NewBVar("asyncJoinK", treal, true)
        
        Comment("join async") :: 
        // pick new k for this join
        BLocal(asyncJoinKV) ::
        bassume(0.0 < asyncJoinK) ::
        // try to use the same k as for the fork
        bassume(asyncJoinK ==@ etran.Heap.select(token, forkK)) :: 
        // check that token is well-defined
        isDefined(token) :::
        // check that we did not join yet
        bassert(CanWrite(token, "joinable"), jn.pos, "The joinable field might not be writable.") ::
        bassert(etran.Heap.select(token, "joinable") !=@ 0, jn.pos, "The joinable field might not be true.") ::
        // lookup token.joinable
        BLocal(argsSeqV) :: (argsSeq := CallArgs(etran.Heap.select(token, "joinable"))) ::
        // retrieve the call's pre-state from token.joinable
        BLocal(preCallHeapV) :: (preCallHeap := CallHeap(etran.Heap.select(token, "joinable"))) :: 
        BLocal(preCallMaskV) :: (preCallMask := CallMask(etran.Heap.select(token, "joinable"))) ::
        BLocal(preCallSecMaskV) :: (preCallSecMask := CallSecMask(etran.Heap.select(token, "joinable"))) ::
        BLocal(preCallCreditsV) :: (preCallCredits := CallCredits(etran.Heap.select(token, "joinable"))) ::
        // introduce locals for the out parameters
        (for (v <- formalThisV :: formalInsV ::: formalOutsV) yield BLocal(Variable2BVarWhere(v))) :::
        // initialize the in parameters
        (formalThis := new MapSelect(argsSeq, 0)) ::
        { var i = 1
          (formalIns map { v => val r = (v := new MapSelect(argsSeq, i)); i += 1; r })
        } :::
        // havoc formal outs
        (for (v <- formalOuts) yield Havoc(v)) :::
        // set joinable to false
        etran.Heap.store(token, "joinable", 0) ::
        etran.SetNoPermission(token, "joinable", etran.Mask) ::
        // inhale postcondition of the call
        postEtran.Inhale(Postconditions(jn.m.spec) map
                         { p => SubstVars(p, formalThis, jn.m.ins ++ jn.m.outs, formalIns ++ formalOuts)}, "postcondition", false, asyncJoinK) :::
        // assign formal outs to actual outs
        (for ((v,e) <- lhs zip formalOuts) yield (v := e))
      case s@Send(ch, args) =>
        val channel = ch.typ.asInstanceOf[ChannelClass].ch
        val formalThisV = new Variable("this", new Type(ch.typ))
        val formalThis = new VariableExpr(formalThisV)
        val formalParamsV = for (p <- channel.parameters) yield new Variable(p.id, p.t)
        val formalParams = for (v <- formalParamsV) yield new VariableExpr(v)
        Comment("send") ::
        // introduce formal parameters
        (for (v <- formalThisV :: formalParamsV) yield BLocal(Variable2BVarWhere(v))) :::
        // check definedness of arguments
        isDefined(ch) :::
        bassert(nonNull(ch), ch.pos, "The channel might be null.") ::
        (args flatMap { e: Expression => isDefined(e)}) :::
        // assign actual ins to formal parameters
        (formalThis := ch) ::
        (for ((v,e) <- formalParams zip args) yield (v := e)) :::
        // increase credits
        new Boogie.MapUpdate(etran.Credits, TrExpr(ch), new Boogie.MapSelect(etran.Credits, TrExpr(ch)) + 1) ::
        // exhale where clause
        Exhale(List(
          (SubstVars(channel.where, formalThis, channel.parameters, formalParams),
           ErrorMessage(s.pos, "The where clause at " + channel.where.pos + " might not hold."))),
          "channel where clause", methodK, false)
      case r@Receive(_, ch, outs) =>
        val channel = ch.typ.asInstanceOf[ChannelClass].ch
        val formalThisV = new Variable("this", new Type(ch.typ))
        val formalThis = new VariableExpr(formalThisV)
        val formalParamsV = for (p <- channel.parameters) yield new Variable(p.id, p.t)
        val formalParams = for (v <- formalParamsV) yield new VariableExpr(v)
        Comment("receive") ::
        // check definedness of arguments
        isDefined(ch) :::
        bassert(nonNull(ch), ch.pos, "The channel might be null.") ::
        // check that credits are positive
        bassert(0 < new Boogie.MapSelect(etran.Credits, TrExpr(ch)), r.pos, "receive operation requires a credit") ::
        // ...and check: waitlevel << ch.mu
        bassert(CanRead(ch, "mu"), r.pos, "The mu field of the channel in the receive statement might not be readable.") ::
        bassert(etran.MaxLockIsBelowX(etran.Heap.select(ch, "mu")), r.pos, "The channel must lie above waitlevel in the wait order") ::
        // introduce locals for the parameters
        (for (v <- formalThisV :: formalParamsV) yield BLocal(Variable2BVarWhere(v))) :::
        // initialize the parameters; that is, set "this" to the channel and havoc the other formal parameters
        (formalThis := ch) ::
        (for (v <- formalParams) yield Havoc(v)) :::
        // inhale where clause
        Inhale(List(SubstVars(channel.where, formalThis, channel.parameters, formalParams)), "channel where clause", methodK) :::
        // declare any new local variables among the actual outs
        (for (v <- r.locals) yield BLocal(Variable2BVarWhere(v))) :::
        // assign formal outs to actual outs
        (for ((v,e) <- outs zip formalParams) yield (v := e)) :::
        // decrease credits
        new Boogie.MapUpdate(etran.Credits, TrExpr(ch), new Boogie.MapSelect(etran.Credits, TrExpr(ch)) - 1)
      case r: RefinementBlock =>
        translateRefinement(r, methodK)
      case _: Signal => throw new NotSupportedException("not implemented")
      case _: Wait => throw new NotSupportedException("not implemented")      
    }
  }

  def translateLocalVarDecl(v: Variable, assignConst: Boolean) = {
    val bv = Variable2BVarWhere(v)
    Comment("local " + v) ::
    BLocal(bv) ::
    { if (v.isImmutable) {
        val isAssignedVar = new Boogie.BVar("assigned$" + bv.id, BoolClass)
        // havoc x; var assigned$x: bool; assigned$x := false;
        Havoc(new Boogie.VarExpr(bv)) ::
        BLocal(isAssignedVar) ::
        (new Boogie.VarExpr(isAssignedVar) := assignConst)
      } else
        Nil }
  }

  def translateAllocation(cl: Class, initialization: List[Init], lowerBounds: List[Expression], upperBounds: List[Expression], pos: Position): (Boogie.BVar, List[Boogie.Stmt]) = {
    val (nw, nwe) = NewBVar("nw", cl, true)
    val (ttV,tt) = Boogie.NewTVar("T")
    val f = new Boogie.BVar("f", FieldType(tt))
    (nw,
      Comment("new") ::
      BLocal(nw) :: Havoc(nwe) ::
      bassume(nonNull(nwe) && (dtype(nwe) ==@ className(cl))) ::
      bassume(new Boogie.Forall(ttV, f, etran.HasNoPermission(nwe, f.id))) ::
      // initial values of fields:
      (if (cl.IsChannel)
         UpdateMu(nwe, false, true, lowerBounds, upperBounds, ErrorMessage(pos, "new might fail."))
       else
         List(bassume(etran.Heap.select(nwe, "mu") ==@ bLockBottom))) :::
      bassume(etran.Heap.select(nwe, "held") <= 0) ::
      bassume(etran.Heap.select(nwe, "rdheld") ==@ false) ::
      // give access to user-defined fields and special fields:
      (for (f <- cl.Fields ++ RootClass.MentionableFields) yield
        etran.IncPermission(nwe, f.FullName, permissionFull)).flatten :::
      // initialize fields according to the initialization
      (initialization flatMap { init => isDefined(init.e) ::: etran.Heap.store(nwe, init.f.FullName, init.e) })
    )
  }

  def TrAcquire(s: Statement, nonNullObj: Expression, currentK: Expr) = {
    val o = TrExpr(nonNullObj);
    val (lastAcquireVar, lastAcquire) = Boogie.NewBVar("lastAcquire", IntClass, true)
    val (lastSeenHeldV, lastSeenHeld) = Boogie.NewBVar("lastSeenHeld", tint, true)
    val (lastSeenMuV, lastSeenMu) = Boogie.NewBVar("lastSeenMu", tmu, true)
    (if (Chalice.skipDeadlockChecks)
       bassume(CanRead(o, "mu")) ::
       bassume(etran.MaxLockIsBelowX(etran.Heap.select(o,"mu")))
     else
       bassert(CanRead(o, "mu"), s.pos, "The mu field of the target of the acquire statement might not be readable.") ::
       bassert(etran.MaxLockIsBelowX(etran.Heap.select(o,"mu")), s.pos, "The mu field of the target of the acquire statement might not be above waitlevel.")) :::
    bassume(etran.Heap.select(o,"mu") !=@ bLockBottom) ::  // this isn't strictly necessary, it seems; but we might as well include it
    // remember the state right before releasing
    BLocal(lastSeenMuV) :: (lastSeenMu := etran.Heap.select(o, "mu")) ::
    BLocal(lastSeenHeldV) :: Havoc(lastSeenHeld) :: (lastSeenHeld := etran.Heap.select(o, "held")) ::
    bassume(! isHeld(o) && ! isRdHeld(o)) :: // this assume follows from the previous assert
    // update the thread's locking state
    BLocal(lastAcquireVar) :: Havoc(lastAcquire) :: bassume(0 < lastAcquire) ::
    etran.Heap.store(o, "held", lastAcquire) ::
    InhaleInvariants(nonNullObj, false, etran.WhereOldIs(
      LastSeenHeap(lastSeenMu, lastSeenHeld),
      LastSeenMask(lastSeenMu, lastSeenHeld),
      LastSeenSecMask(lastSeenMu, lastSeenHeld),
      LastSeenCredits(lastSeenMu, lastSeenHeld)), currentK) :::
    // remember values of Heap/Mask/Credits globals (for proving history constraint at release)
    bassume(AcquireHeap(lastAcquire) ==@ etran.Heap) ::
    bassume(AcquireMask(lastAcquire) ==@ etran.Mask) ::
    bassume(AcquireSecMask(lastAcquire) ==@ etran.SecMask) ::
    bassume(AcquireCredits(lastAcquire) ==@ etran.Credits)
  }
  def TrRelease(s: Statement, nonNullObj: Expression, currentK: Expr) = {
    val (heldV, held) = Boogie.NewBVar("held", tint, true) 
    val (prevLmV, prevLm) = Boogie.NewBVar("prevLM", tref, true)
    val (preReleaseHeapV, preReleaseHeap) = NewBVar("preReleaseHeap", theap, true)
    val (preReleaseMaskV, preReleaseMask) = NewBVar("preReleaseMask", tmask, true)
    val (preReleaseSecMaskV, preReleaseSecMask) = NewBVar("preReleaseSecMask", tmask, true)
    val (preReleaseCreditsV, preReleaseCredits) = NewBVar("preReleaseCredits", tcredits, true)
    val o = TrExpr(nonNullObj);
    BLocal(preReleaseHeapV) :: (preReleaseHeap := etran.Heap) ::
    BLocal(preReleaseMaskV) :: (preReleaseMask := etran.Mask) ::
    BLocal(preReleaseSecMaskV) :: (preReleaseSecMask := etran.SecMask) ::
    BLocal(preReleaseCreditsV) :: (preReleaseCredits := etran.Credits) ::
    bassert(isHeld(o), s.pos, "The target of the release statement might not be locked by the current thread.") ::
    bassert(!isRdHeld(o), s.pos, "Release might fail because the current thread might hold the read lock.") ::
    ExhaleInvariants(nonNullObj, false, ErrorMessage(s.pos, "Monitor invariant might hot hold."), etran.WhereOldIs(
      AcquireHeap(etran.Heap.select(o, "held")),
      AcquireMask(etran.Heap.select(o, "held")),
      AcquireSecMask(etran.Heap.select(o, "held")),
      AcquireCredits(etran.Heap.select(o, "held"))), currentK) :::
    // havoc o.held where 0<=o.held 
    BLocal(heldV) :: Havoc(held) :: bassume(held <= 0) ::
    etran.Heap.store(o, "held", held) ::
    // assume a seen state is the one right before the share
    bassume(LastSeenHeap(etran.Heap.select(o, "mu"), held) ==@ preReleaseHeap) ::
    bassume(LastSeenMask(etran.Heap.select(o, "mu"), held) ==@ preReleaseMask) ::
    bassume(LastSeenSecMask(etran.Heap.select(o, "mu"), held) ==@ preReleaseSecMask) ::
    bassume(LastSeenCredits(etran.Heap.select(o, "mu"), held) ==@ preReleaseCredits)
  }
  def TrRdAcquire(s: Statement, nonNullObj: Expression, currentK: Expr) = {
    val (heldV, held) = Boogie.NewBVar("held", tint, true)
    val o = TrExpr(nonNullObj)
    bassert(CanRead(o, "mu"), s.pos, "The mu field of the target of the read-acquire statement might not be readable.") ::
    bassert(etran.MaxLockIsBelowX(etran.Heap.select(o, "mu")), s.pos, "The mu field of the target of the read-acquire statement might not be above waitlevel.") ::
    bassume(etran.Heap.select(o,"mu") !=@ bLockBottom) ::  // this isn't strictly necessary, it seems; but we might as well include it
    bassume(! isHeld(o) && ! isRdHeld(o)) ::
    BLocal(heldV) :: Havoc(held) :: bassume(held <= 0) ::
    etran.Heap.store(o, "held", held) ::
    etran.Heap.store(o, "rdheld", true) ::
    InhaleInvariants(nonNullObj, true, currentK)
  }
  def TrRdRelease(s: Statement, nonNullObj: Expression, currentK: Expr) = {
    val (heldV, held) = Boogie.NewBVar("held", tint, true)
    val o = TrExpr(nonNullObj);
    bassert(isRdHeld(o), s.pos, "The current thread might not hold the read-lock of the object being released.") ::
    ExhaleInvariants(nonNullObj, true, ErrorMessage(s.pos, "Monitor invariant might not hold."), currentK) :::
    BLocal(heldV) :: Havoc(held) :: bassume(held <= 0) ::
    etran.Heap.store(o, "held", held) ::
    etran.Heap.store(o, "rdheld", false)
  }

  def translateSpecStmt(s: SpecStmt, methodK: Expr): List[Stmt] = {
    val (preGlobalsV, preGlobals) = etran.FreshGlobals("pre")

    // pick new k for the spec stmt
    val (specKV, specK) = Boogie.NewBVar("specStmtK", treal, true)

    BLocal(specKV) ::
    bassume(0.0 < specK && 1000.0*specK < percentPermission(1) && 1000.0*specK < methodK) ::
    // declare new local variables
    s.locals.flatMap(v => translateLocalVarDecl(v, true)) :::
    Comment("spec statement") ::
    BLocals(preGlobalsV) :::
    // remember values of globals
    copyState(preGlobals, etran) :::
    // exhale preconditions
    etran.Exhale(List((s.pre, ErrorMessage(s.pos, "The specification statement precondition at " + s.pos + " might not hold."))), "spec stmt precondition", true, specK, false) :::
    // havoc locals
    (s.lhs.map(l => Boogie.Havoc(l))) :::
    // inhale postconditions (using the state before the call as the "old" state)
    etran.FromPreGlobals(preGlobals).Inhale(List(s.post), "spec stmt postcondition", false, specK)
  }

  def translateCall(c: Call, methodK: Expr): List[Stmt] = {
    val obj = c.obj;
    val lhs = c.lhs;
    val id = c.id;
    val args = c.args;
    val formalThisV = new Variable("this", new Type(c.m.Parent))
    val formalThis = new VariableExpr(formalThisV)
    val formalInsV = for (p <- c.m.Ins) yield new Variable(p.id, p.t)
    val formalIns = for (v <- formalInsV) yield new VariableExpr(v)
    val formalOutsV = for (p <- c.m.Outs) yield new Variable(p.id, p.t)
    val formalOuts = for (v <- formalOutsV) yield new VariableExpr(v)
    val (preGlobalsV, preGlobals) = etran.FreshGlobals("call")
    val postEtran = etran.FromPreGlobals(preGlobals)
    
    // pick new k for this method call
    val (methodCallKV, methodCallK) = Boogie.NewBVar("methodCallK", treal, true)
    BLocal(methodCallKV) ::
    bassume(0.0 < methodCallK && 1000.0*methodCallK < percentPermission(1) && 1000.0*methodCallK < methodK) ::
    Comment("call " + id) ::
    // introduce formal parameters and pre-state globals
    (for (v <- formalThisV :: formalInsV ::: formalOutsV) yield BLocal(Variable2BVarWhere(v))) :::
    BLocals(preGlobalsV) :::
    // remember values of globals
    copyState(preGlobals, etran) :::
    // check definedness of arguments
    isDefined(obj) :::
    bassert(nonNull(obj), c.pos, "The target of the method call might be null.") ::
    (args flatMap { e: Expression => isDefined(e)}) :::
    // assign actual ins to formal ins
    (formalThis := obj) ::
    (for ((v,e) <- formalIns zip args) yield (v := e)) :::
    // exhale preconditions
    Exhale(Preconditions(c.m.Spec) map
          (p => SubstVars(p, formalThis, c.m.Ins, formalIns)) zip (Preconditions(c.m.Spec) map { p => ErrorMessage(c.pos, "The precondition at " + p.pos + " might not hold.")}), "precondition", methodCallK, false) :::
    // havoc formal outs
    (for (v <- formalOuts) yield Havoc(v)) :::
    // havoc lockchanges
    LockHavoc(for (e <- LockChanges(c.m.Spec) map (p => SubstVars(p, formalThis, c.m.Ins ++ c.m.Outs, formalIns ++ formalOuts))) yield etran.Tr(e), postEtran) :::
    // inhale postconditions (using the state before the call as the "old" state)
    postEtran.Inhale(Postconditions(c.m.Spec) map
                     (p => SubstVars(p, formalThis, c.m.Ins ++ c.m.Outs, formalIns ++ formalOuts)) , "postcondition", false, methodCallK) :::
    // declare any new local variables among the actual outs
    (for (v <- c.locals) yield BLocal(Variable2BVarWhere(v))) :::
    // assign formal outs to actual outs
    (for ((v,e) <- lhs zip formalOuts) yield (v :=e))
  }

  def translateWhile(w: WhileStmt, methodK: Expr): List[Stmt] = {
    val guard = w.guard;
    val lkch = w.lkch;
    val body = w.body;

    val (preLoopGlobalsV, preLoopGlobals) = etran.FreshGlobals("while")
    val loopEtran = etran.FromPreGlobals(preLoopGlobals)
    val (iterStartGlobalsV, iterStartGlobals) = etran.FreshGlobals("iterStart")
    val iterStartEtran = etran.FromPreGlobals(iterStartGlobals)
    val saveLocalsV = for (v <- w.LoopTargets) yield new Variable(v.id, v.t)
    val iterStartLocalsV = for (v <- w.LoopTargets) yield new Variable(v.id, v.t)
    val lkchOld = lkch map (e => SubstVars(e, w.LoopTargets,
                                             for (v <- saveLocalsV) yield new VariableExpr(v)))
    val lkchIterStart = lkch map (e => SubstVars(e, w.LoopTargets,
                                                   for (v <- iterStartLocalsV) yield new VariableExpr(v)))
    val oldLocks = lkchOld map (e => loopEtran.oldEtran.Tr(e))
    val iterStartLocks = lkchIterStart map (e => iterStartEtran.oldEtran.Tr(e))
    val newLocks = lkch map (e => loopEtran.Tr(e));
    val (whileKV, whileK) = Boogie.NewBVar("whileK", treal, true)
    val previousEtran = etran // save etran
    
    Comment("while") ::
    // pick new k for this method call
    BLocal(whileKV) ::
    bassume(0.0 < whileK && 1000.0*whileK < percentPermission(1) && 1000.0*whileK < methodK) ::
    // save globals
    BLocals(preLoopGlobalsV) :::
    copyState(preLoopGlobals, loopEtran) :::
    // check invariant on entry to the loop
    Exhale(w.oldInvs map { inv => (inv, ErrorMessage(inv.pos, "The loop invariant might not hold on entry to the loop."))}, "loop invariant, initially", whileK, false) :::
    tag(Exhale(w.newInvs map { inv => (inv, ErrorMessage(inv.pos, "The loop invariant might not hold on entry to the loop."))}, "loop invariant, initially", whileK, false), keepTag) :::
    List(bassert(DebtCheck, w.pos, "Loop invariant must consume all debt on entry to the loop.")) :::
    // check lockchange on entry to the loop
    Comment("check lockchange on entry to the loop") ::
    (bassert(LockFrame(lkch, etran), w.pos, "Method execution before loop might lock/unlock more than allowed by lockchange clause of loop.")) ::
    // save values of local-variable loop targets
    (for (sv <- saveLocalsV) yield BLocal(Variable2BVarWhere(sv))) :::
    (for ((v,sv) <- w.LoopTargets zip saveLocalsV) yield (new VariableExpr(sv) := new VariableExpr(v))) :::
    // havoc local-variable loop targets
    (w.LoopTargets :\ List[Boogie.Stmt]()) ( (v,vars) => (v match {
      case v: Variable if v.isImmutable => Boogie.Havoc(Boogie.VarExpr("assigned$" + v.id))
      case _ => Boogie.Havoc(Boogie.VarExpr(v.UniqueName)) }) :: vars) :::
    Boogie.If(null,
    // 1. CHECK  DEFINEDNESS OF INVARIANT
      { etran = etran.resetFpi
      Comment("check loop invariant definedness") ::
      //(w.LoopTargets.toList map { v: Variable => Boogie.Havoc(Boogie.VarExpr(v.id)) }) :::
      resetState(etran) :::
      InhaleWithChecking(w.oldInvs, "loop invariant definedness", whileK) :::
      tag(InhaleWithChecking(w.newInvs, "loop invariant definedness", whileK), keepTag) :::                  
      bassume(false) }
    , Boogie.If(null,
    // 2. CHECK LOOP BODY
      // Renew state: set Mask to ZeroMask and Credits to ZeroCredits, and havoc Heap everywhere except
      // at {old(local),local}.{held,rdheld}
      { etran = etran.resetFpi
      resetState(etran) :::
      Inhale(w.Invs, "loop invariant, body", whileK) :::
      // assume lockchange at the beginning of the loop iteration
      Comment("assume lockchange at the beginning of the loop iteration") ::
      (bassume(LockFrame(lkch, etran))) ::
      // this is the state at the beginning of the loop iteration; save these values
      BLocals(iterStartGlobalsV) :::
      copyState(iterStartGlobals, iterStartEtran) :::
      (for (isv <- iterStartLocalsV) yield BLocal(Variable2BVarWhere(isv))) :::
      (for ((v,isv) <- w.LoopTargets zip iterStartLocalsV) yield
         (new VariableExpr(isv) := new VariableExpr(v))) :::
      // evaluate the guard
      isDefined(guard) ::: List(bassume(guard)) :::
      translateStatement(body, whileK) ::: 
      // check invariant
      Exhale(w.oldInvs map { inv => (inv, ErrorMessage(inv.pos, "The loop invariant at " + inv.pos + " might not be preserved by the loop."))}, "loop invariant, maintained", whileK, true) :::
      tag(Exhale(w.newInvs map { inv => (inv, ErrorMessage(inv.pos, "The loop invariant at " + inv.pos + " might not be preserved by the loop."))}, "loop invariant, maintained", whileK, true), keepTag) :::
      isLeaking(w.pos, "The loop might leak references.") :::
      // check lockchange after loop iteration
      Comment("check lockchange after loop iteration") ::
        (bassert(LockFrame(lkch, etran), w.pos, "The loop might lock/unlock more than the lockchange clause allows.")) ::
      // perform debt check
      bassert(DebtCheck, w.pos, "Loop body is not allowed to leave any debt.") :::
      bassume(false)},
   // 3. AFTER LOOP
     { etran = previousEtran
     LockHavoc(oldLocks ++ newLocks, loopEtran) :::
     // assume lockchange after the loop
     Comment("assume lockchange after the loop") ::
     (bassume(LockFrame(lkch, etran))) ::
     Inhale(w.Invs, "loop invariant, after loop", whileK) :::
     bassume(!guard)}))
  }

  def translateRefinement(r: RefinementBlock, methodK: Expr): List[Stmt] = {
    // abstract expression translator
    val absTran = etran;
    // concrete expression translate
    val (conGlobalsV, conGlobals) = etran.FreshGlobals("concrete")
    val conTran = new ExpressionTranslator(conGlobals, etran.oldEtran.globals, currentClass); // TODO: what about FoldedPredicateInfo?
    // shared locals existing before the block (excluding immutable)
    val before = for (v <- r.before; if (! v.isImmutable)) yield v;
    // shared locals declared in the block
    val (duringA, duringC) = r.during;
    // variables for locals before (to restore for the abstract version)
    val beforeV = for (v <- before) yield new Variable(v.id, v.t)
    // variables for locals after (to compare with the abstract version)
    val afterV = for (v <- before) yield new Variable(v.id, v.t)

    Comment("refinement block") ::
    // save heap
    BLocals(conGlobalsV) :::
    copyState(conGlobals, etran) :::
    // save shared local variables
    (for (v <- beforeV) yield BLocal(Variable2BVarWhere(v))) :::            
    (for ((v, w) <- beforeV zip before) yield (new VariableExpr(v) := new VariableExpr(w))) :::
    // run concrete C on the fresh heap
    {
      etran = conTran;
      Comment("concrete program:") ::
      tag(translateStatements(r.con, methodK), keepTag)
    } :::
    // run angelically A on the old heap
    Comment("abstract program:") ::
    { etran = absTran;
    r.abs match {
      case List(s: SpecStmt) =>
        var (m, me) = NewBVar("specMask", tmask, true)
        var (sm, sme) = NewBVar("specSecMask", tmask, true)
        tag(
          Comment("give witnesses to the declared local variables") ::
          (for (v <- duringA) yield BLocal(Variable2BVarWhere(v))) :::
          (for ((v, w) <- duringA zip duringC) yield (new VariableExpr(v) := new VariableExpr(w))) :::
          BLocal(m) :: BLocal(sm) ::
          (me := absTran.Mask) :: (sme := absTran.SecMask) ::
          absTran.Exhale(me, sme, List((s.post,ErrorMessage(r.pos, "Refinement may fail to satisfy specification statement post-condition."))), "SpecStmt", false, methodK, false) :::
          (for ((v, w) <- beforeV zip before; if (! s.lhs.exists(ve => ve.v == w))) yield
             bassert(new VariableExpr(v) ==@ new VariableExpr(w), r.pos, "Refinement may change a variable outside of the frame of the specification statement: " + v.id)),
          keepTag)
      case _ =>
        // save locals after
        (for (v <- afterV) yield BLocal(Variable2BVarWhere(v))) :::
        (for ((v, w) <- afterV zip before) yield (new VariableExpr(v) := new VariableExpr(w))) :::
        // restore locals before
        (for ((v, w) <- before zip beforeV) yield (new VariableExpr(v) := new VariableExpr(w))) :::
        translateStatements(r.abs, methodK) :::
        // assert equality on shared locals
        tag(
          (for ((v, w) <- afterV zip before) yield
            bassert(new VariableExpr(v) ==@ new VariableExpr(w), r.pos, "Refinement may produce a different value for the pre-state local variable: " + v.id)) :::
          (for ((v, w) <- duringA zip duringC) yield
            bassert(new VariableExpr(v) ==@ new VariableExpr(w), r.pos, "Refinement may produce a different value for the declared variable: " + v.id)),
          keepTag)
    }} :::
    {
      val (v,ve) = NewBVar("this", tref, true)
      // TODO: check for mask coupling
      // TODO: we only inhale concrete values for "This"

      def copy(e: Expression):List[Stmt] = e match {
        case And(a,b) => copy(a) ::: copy(b)
        case Implies(a,b) => Boogie.If(absTran.Tr(a), copy(b), Nil)
        case Access(ma, _) if ! ma.isPredicate => absTran.Heap.store(absTran.Tr(ma.e), new VarExpr(ma.f.FullName), conTran.Heap.select(absTran.Tr(ma.e), ma.f.FullName))
        case _: PermissionExpr => throw new NotSupportedException("not implemented")
        case _ => Nil
      }

      // copy variables in the coupling invariants to the abstract heap (to preserve their values across refinement blocks and establish invariant)
      (for (ci <- currentClass.CouplingInvariants)
        yield Boogie.If((ci.fields.map(f => absTran.CanRead(new VarExpr("this"), f.FullName)).reduceLeft(_ || _)),
          copy(ci.e), Nil)) :::
      // assert equality on shared globals (except those that are replaced)
      tag(
        for (f <- currentClass.refines.Fields; if ! currentClass.CouplingInvariants.exists(_.fields.contains(f)))
          yield bassert((absTran.Heap.select(ve, f.FullName) ==@ conTran.Heap.select(ve, f.FullName)).forall(v), r.pos, "Refinement may change the value of the field " + f.FullName),
        keepTag)            
    } :::
    Comment("end of the refinement block")
  }

  def UpdateMu(o: Expr, allowOnlyFromBottom: Boolean, justAssumeValue: Boolean,
               lowerBounds: List[Expression], upperBounds: List[Expression], error: ErrorMessage): List[Stmt] = {
    def BoundIsNullObject(b: Expression): Boogie.Expr = {
      if (b.typ.IsMu) false else b ==@ bnull
    }
    def MuValue(b: Expression): Expr = {
      if (b.typ.IsMu) b else etran.Heap.select(b, "mu")
    }
    def Below(a: Expr, b: Expr) = {
      new FunctionApp("MuBelow", a, b)
    }
    val (muV, mu) = Boogie.NewBVar("mu", Boogie.NamedType("Mu"), true)
    // check that bounds are well-defined
    ((lowerBounds ++ upperBounds) flatMap { bound => isDefined(bound)}) :::
    // check that we have full access to o.mu
    (if (!justAssumeValue)
      List(bassert(CanWrite(o, "mu"), error.pos, error.message + " The mu field of the target might not be writable."))
     else
       List()) :::
    // ...and that o.mu starts off as lockbottom, if desired
    (if (allowOnlyFromBottom)
      List(bassert(etran.Heap.select(o,"mu") ==@ bLockBottom,
                   error.pos, error.message + " The object may already be shared (i.e., mu may not be LockBottom)"))
     else
      List()) :::
    // check for each bound that if it is a non-null object, then its mu field is readable
    (for (bound <- lowerBounds ++ upperBounds if !bound.typ.IsMu) yield
      bassert((bound ==@ bnull) || CanRead(bound, "mu"), bound.pos, "The mu field of bound at " + bound.pos + " might not be readable." )) :::
    // check that each lower bound is smaller than each upper bound
    (for (lb <- lowerBounds; ub <- upperBounds) yield
      bassert( (etran.ShaveOffOld(lb), etran.ShaveOffOld(ub)) match {
        case ((MaxLockLiteral(),o0), (MaxLockLiteral(),o1)) =>
          if (o0 == o1)
            false
          else
            etran.TemporalMaxLockComparison(etran.ChooseEtran(o0), etran.ChooseEtran(o1))
        case ((MaxLockLiteral(),o), _) => etran.ChooseEtran(o).MaxLockIsBelowX(MuValue(ub))
        case (_, (MaxLockLiteral(),o)) => etran.ChooseEtran(o).MaxLockIsAboveX(MuValue(lb))
        case _ => BoundIsNullObject(lb) ||
                  BoundIsNullObject(ub) ||
                  Below(MuValue(lb), MuValue(ub)) }, lb.pos, "The lower bound at " + lb.pos + " might not be smaller than the upper bound at " + ub.pos + ".")) :::
    // havoc mu
    BLocal(muV) :: Havoc(mu) :: bassume(mu !=@ bLockBottom) ::
    // assume that mu is between the given bounds (or above waitlevel if no bounds are given)
    (if (lowerBounds == Nil && upperBounds == Nil) {
      // assume waitlevel << mu
      List(bassume(etran.MaxLockIsBelowX(mu)))
    } else {
      (for (lb <- lowerBounds) yield
        // assume lb << mu
        bassume(
          if (etran.IsMaxLockLit(lb)) {
            val (f,o) = etran.ShaveOffOld(lb)
            etran.ChooseEtran(o).MaxLockIsBelowX(mu)
          } else
            (BoundIsNullObject(lb) || Below(MuValue(lb), mu)))) :::
      (for (ub <- upperBounds) yield
        // assume mu << ub
        bassume(
          if (etran.IsMaxLockLit(ub)) {
            val (f,o) = etran.ShaveOffOld(ub)
            etran.ChooseEtran(o).MaxLockIsAboveX(mu)
          } else
            (BoundIsNullObject(ub) || Below(mu, MuValue(ub)))))
    }) :::
    // store the mu field
    (if (justAssumeValue) bassume(etran.Heap.select(o, "mu") ==@ mu) else etran.Heap.store(o, "mu", mu))
  }

  def isLeaking(pos: Position, msg: String): List[Boogie.Stmt] = {
    if(Chalice.checkLeaks) {
      var o = Boogie.VarExpr("$o");
      var f = "$f";
      val (ttV,tt) = Boogie.NewTVar("T")
      List(
        bassert(new Boogie.Forall(
          List(ttV),
          List(Boogie.BVar("$o", tref), Boogie.BVar("$f", FieldType(tt))),
          Nil,
          (o ==@ bnull) || ((new MapSelect(etran.Mask, o, f, "perm$R") ==@ 0.0) && (new MapSelect(etran.Mask, o, f, "perm$N") ==@ 0.0))
        ), pos, msg)
      )
    } else {
      Nil
    }
  }

  def LockFrame(lkch: List[Expression], etran: ExpressionTranslator) =
    LocksUnchanged(for (l <- lkch) yield etran.Tr(l), etran)
  def LocksUnchanged(exceptions: List[Boogie.Expr], etran: ExpressionTranslator) = {
    val (lkV, lk) = Boogie.NewBVar("lk", tref, true)
    val b: Boogie.Expr = false
    Boogie.Forall(Nil, List(lkV),
                  List(new Trigger(etran.Heap.select(lk, "held")), new Trigger(etran.Heap.select(lk, "rdheld"))),
                  (((0 < etran.Heap.select(lk, "held")) ==@
                    (0 < etran.oldEtran.Heap.select(lk, "held"))) &&
                   (new Boogie.MapSelect(etran.Heap, lk, "rdheld") ==@
                    new Boogie.MapSelect(etran.oldEtran.Heap, lk, "rdheld"))) ||
          // It seems we should exclude newly-allocated objects from lockchange. Since Chalice does not have an "alloc" field,
          // we could use the "mu" field as an approximation, but that breaks the HandOverHand example. So we leave it for now.
          // (new Boogie.MapSelect(etran.oldEtran.Heap, lk, "mu") ==@ bLockBottom) ||
                  ((exceptions :\ b) ((e,ll) => ll || (lk ==@ e))))
  }
  def LockHavoc(locks: List[Boogie.Expr], etran: ExpressionTranslator) = {
    val (heldV, held) = NewBVar("isHeld", IntClass, true)
    val (rdheldV, rdheld) = NewBVar("isRdHeld", BoolClass, true)
    BLocal(heldV) :: BLocal(rdheldV) ::
    (for (o <- locks) yield {  // todo: somewhere we should worry about Df(l)
      Havoc(held) :: Havoc(rdheld) ::
      bassume(rdheld ==> (0 < held)) ::
      new MapUpdate(etran.Heap, o, VarExpr("held"), held) ::
      new MapUpdate(etran.Heap, o, VarExpr("rdheld"), rdheld) }).flatten
  }
  def NumberOfLocksHeldIsInvariant(oldLocks: List[Boogie.Expr], newLocks: List[Boogie.Expr],
                                   etran: ExpressionTranslator) = {
    (for ((o,n) <- oldLocks zip newLocks) yield {
      // oo.held == nn.held && oo.rdheld == nn.rdheld
      (((0 < new Boogie.MapSelect(etran.oldEtran.Heap, o, "held")) ==@
        (0 < new Boogie.MapSelect(etran.Heap, n, "held"))) &&
       (new Boogie.MapSelect(etran.oldEtran.Heap, o, "rdheld") ==@
        new Boogie.MapSelect(etran.Heap, n, "rdheld"))) ::
      // no.held == on.held && no.rdheld == on.rdheld
      (((0 < new Boogie.MapSelect(etran.Heap, o, "held")) ==@
        (0 < new Boogie.MapSelect(etran.oldEtran.Heap, n, "held"))) &&
       (new Boogie.MapSelect(etran.Heap, o, "rdheld") ==@
        new Boogie.MapSelect(etran.oldEtran.Heap, n, "rdheld"))) ::
      // o == n || (oo.held != no.held && (!oo.rdheld || !no.rdheld))
      ((o ==@ n) ||
       (((0 < new Boogie.MapSelect(etran.oldEtran.Heap, o, "held")) !=@ (0 < new Boogie.MapSelect(etran.Heap, o, "held"))) &&
        ((! new Boogie.MapSelect(etran.oldEtran.Heap, o, "rdheld")) ||
         (! new Boogie.MapSelect(etran.Heap, o, "rdheld"))))) ::
      Nil
    }).flatten
  }

  implicit def lift(s: Stmt): List[Stmt] = List(s)
  def isDefined(e: Expression) = etran.isDefined(e)(true)
  def TrExpr(e: Expression) = etran.Tr(e)

  def InhaleInvariants(obj: Expression, readonly: Boolean, tran: ExpressionTranslator, currentK: Expr) = {
    val shV = new Variable("sh", new Type(obj.typ))
    val sh = new VariableExpr(shV)
    BLocal(Variable2BVar(shV)) :: Boogie.Assign(TrExpr(sh), TrExpr(obj)) ::
    tran.Inhale(obj.typ.MonitorInvariants map
           (inv => SubstThis(inv.e, sh)) map
           (inv => (if (readonly) SubstRd(inv) else inv)), "monitor invariant", false, currentK)
  }
  def ExhaleInvariants(obj: Expression, readonly: Boolean, msg: ErrorMessage, tran: ExpressionTranslator, currentK: Expr) = {
    val shV = new Variable("sh", new Type(obj.typ))
    val sh = new VariableExpr(shV)
    BLocal(Variable2BVar(shV)) :: Boogie.Assign(TrExpr(sh), TrExpr(obj)) ::
    tran.Exhale(obj.typ.MonitorInvariants map
           (inv => SubstThis(inv.e, sh)) map
           (inv => (if (readonly) SubstRd(inv) else inv, msg)), "monitor invariant", false, currentK, false)
  }
  def InhaleInvariants(obj: Expression, readonly: Boolean, currentK: Expr) = {
    val shV = new Variable("sh", new Type(obj.typ))
    val sh = new VariableExpr(shV)
    BLocal(Variable2BVar(shV)) :: Boogie.Assign(TrExpr(sh), TrExpr(obj)) ::
    Inhale(obj.typ.MonitorInvariants map
           (inv => SubstThis(inv.e, sh)) map
           (inv => (if (readonly) SubstRd(inv) else inv)), "monitor invariant", currentK)
  }
  def ExhaleInvariants(obj: Expression, readonly: Boolean, msg: ErrorMessage, currentK: Expr) = {
    val shV = new Variable("sh", new Type(obj.typ))
    val sh = new VariableExpr(shV)
    BLocal(Variable2BVar(shV)) :: Boogie.Assign(TrExpr(sh), TrExpr(obj)) ::
    Exhale(obj.typ.MonitorInvariants map
           (inv => SubstThis(inv.e, sh)) map
           (inv => (if (readonly) SubstRd(inv) else inv, msg)), "monitor invariant", currentK, false)
  }

  def Inhale(predicates: List[Expression], occasion: String, currentK: Expr): List[Boogie.Stmt] = etran.Inhale(predicates, occasion, false, currentK)
  def Exhale(predicates: List[(Expression, ErrorMessage)], occasion: String, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = etran.Exhale(predicates, occasion, false, currentK, exactchecking)
  def ExhaleDuringUnfold(predicates: List[(Expression, ErrorMessage)], occasion: String, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = etran.ExhaleDuringUnfold(predicates, occasion, false, currentK, exactchecking)
  def InhaleWithChecking(predicates: List[Expression], occasion: String, currentK: Expr): List[Boogie.Stmt] = etran.Inhale(predicates, occasion, true, currentK)
  def ExhaleWithChecking(predicates: List[(Expression, ErrorMessage)], occasion: String, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = etran.Exhale(predicates, occasion, true, currentK, exactchecking)

  def CanRead(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = etran.CanRead(obj, field)
  def CanWrite(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = etran.CanWrite(obj, field)


/**********************************************************************
*****************          EXPRESSIONS                *****************
**********************************************************************/

/** Represents a predicate that has been folded by ourselfs, or that we have peeked
 * at using unfolding.
 */
case class FoldedPredicate(predicate: Predicate, receiver: Expr, version: Expr, conditions: Set[(VarExpr,Boolean)], flag: Expr)

/** All information that we need to keep track of about folded predicates. */
class FoldedPredicatesInfo {
  
  private var foldedPredicates: List[FoldedPredicate] = List()
  var currentConditions: Set[(VarExpr,Boolean)] = Set()
  
  /** Add a predicate that we have folded */
  def addFoldedPredicate(predicate: FoldedPredicate) {
    foldedPredicates ::= predicate
  }
  
  /** Start again with the empty information about folded predicates. */
  def reset {
    foldedPredicates = List()
    currentConditions = Set()
  }
  
  /** return a list of folded predicates that might match for predicate */
  def getFoldedPredicates(predicate: Predicate): List[FoldedPredicate] = {
    foldedPredicates filter (fp => fp.predicate.FullName == predicate.FullName)
  }
  
  /** return a list of all folded predicates */
  def getFoldedPredicates(): List[FoldedPredicate] = {
    foldedPredicates
  }
  
  /** get an upper bound on the recursion depth when updating the secondary mask */
  def getRecursionBound(predicate: Predicate): Int = {
    foldedPredicates length
  }
  
  /** get an upper bound on the recursion depth when updating the secondary mask */
  def getRecursionBound(): Int = {
    foldedPredicates length
  }
  
}
object FoldedPredicatesInfo {
  def apply() = new FoldedPredicatesInfo()
}

case class Globals(heap: Expr, mask: Expr, secmask: Expr, credits: Expr) {
  def list: List[Expr] = List(heap, mask, secmask, credits)
}

class ExpressionTranslator(val globals: Globals, preGlobals: Globals, val fpi: FoldedPredicatesInfo, currentClass: Class, checkTermination: Boolean) {

  import TranslationHelper._

  val Heap = globals.heap;
  val Mask = globals.mask;
  val SecMask = globals.secmask;
  val Credits = globals.credits;
  lazy val oldEtran = new ExpressionTranslator(preGlobals, preGlobals, fpi, currentClass, checkTermination)

  def this(globals: Globals, preGlobals: Globals, fpi: FoldedPredicatesInfo, currentClass: Class) = this(globals, preGlobals, fpi, currentClass, false)
  def this(globals: Globals, preGlobals: Globals, currentClass: Class) = this(globals, preGlobals, FoldedPredicatesInfo(), currentClass, false)
  def this(globals: Globals, cl: Class) = this(globals, Globals(Boogie.Old(globals.heap), Boogie.Old(globals.mask), Boogie.Old(globals.secmask), Boogie.Old(globals.credits)), cl)
  def this(cl: Class) = this(Globals(VarExpr(HeapName), VarExpr(MaskName), VarExpr(SecMaskName), VarExpr(CreditsName)), cl)

  def ChooseEtran(chooseOld: Boolean) = if (chooseOld) oldEtran else this
  
  def isOldEtran = {
    Heap match {
      case Boogie.Old(_) => true
      case _ => false
    }
  }
  
  /** return a new etran which is identical, expect for the fpi */
  def resetFpi = {
    new ExpressionTranslator(globals, preGlobals, new FoldedPredicatesInfo, currentClass, checkTermination)
  }

  /**
   * Create a list of fresh global variables
   */
  def FreshGlobals(prefix: String): (List[Boogie.BVar], Globals) = {
    val vs = new Boogie.BVar(prefix + HeapName, theap, true) ::
    new Boogie.BVar(prefix + MaskName, tmask, true) ::
    new Boogie.BVar(prefix + SecMaskName, tmask, true) ::
    new Boogie.BVar(prefix + CreditsName, tcredits, true) ::
    Nil
    val es = vs map {v => new Boogie.VarExpr(v)}
    (vs, Globals(es(0), es(1), es(2), es(3)))
  }

  def FromPreGlobals(pg: Globals) = {
    new ExpressionTranslator(globals, pg, fpi, currentClass, checkTermination)
  }

  def UseCurrentAsOld() = {
    new ExpressionTranslator(globals, globals, fpi, currentClass, checkTermination);
  }

  def WhereOldIs(h: Boogie.Expr, m: Boogie.Expr, sm: Boogie.Expr, c: Boogie.Expr) = {
    new ExpressionTranslator(globals, Globals(h, m, sm, c), fpi, currentClass, checkTermination);
  }

  def CheckTermination(check: Boolean) = {
    new ExpressionTranslator(globals, preGlobals, fpi, currentClass, check);
  }
  
  /**********************************************************************
  *****************              TR/DF                  *****************
  **********************************************************************/

  def isDefined(e: Expression)(implicit assumption: Expr): List[Boogie.Stmt] = {
    def prove(goal: Expr, pos: Position, msg: String)(implicit assumption: Expr) =
      bassert(assumption ==> goal, pos, msg)
    
    desugar(e) match {
      case IntLiteral(n) => Nil
      case BoolLiteral(b) => Nil
      case NullLiteral() => Nil
      case StringLiteral(s) => Nil
      case MaxLockLiteral() => Nil
      case LockBottomLiteral() => Nil
      case _:ThisExpr => Nil
      case _:Result => Nil
      case _:BoogieExpr => Nil
      case _:VariableExpr => Nil
      case fs @ MemberAccess(e, f) =>       
        assert(!fs.isPredicate);
        isDefined(e) ::: 
        prove(nonNull(Tr(e)), e.pos, "Receiver might be null.") ::
        prove(CanRead(Tr(e), fs.f.FullName), fs.pos, "Location might not be readable.")
      case Full | Star | Epsilon | MethodEpsilon => Nil
      case ForkEpsilon(token) => isDefined(token)
      case MonitorEpsilon(Some(monitor)) => isDefined(monitor)
      case ChannelEpsilon(Some(channel)) => isDefined(channel)
      case PredicateEpsilon(_) => Nil
      case ChannelEpsilon(None) | MonitorEpsilon(None) => Nil
      case PermPlus(l,r) => isDefined(l) ::: isDefined(r)
      case PermMinus(l,r) => isDefined(l) ::: isDefined(r)
      case PermTimes(l,r) => isDefined(l) ::: isDefined(r)
      case IntPermTimes(l,r) => isDefined(l) ::: isDefined(r)
      case Frac(perm) => isDefined(perm)
      case Epsilons(p) => isDefined(p)
      case _:PermissionExpr => throw new InternalErrorException("permission expression unexpected here: " + e.pos + " (" + e + ")")
      case c@Credit(e, n) =>
        isDefined(e) :::
        isDefined(c.N)
      case Holds(e) =>
        isDefined(e)
      case RdHolds(e) =>
        isDefined(e)
      case _: Assigned => Nil
      case Old(e) =>
        oldEtran.isDefined(e)
      case IfThenElse(con, then, els) =>
        isDefined(con) ::: Boogie.If(Tr(con), isDefined(then), isDefined(els))
      case Not(e) =>
        isDefined(e)
      case func@FunctionApplication(obj, id, args) =>
        val (tmpGlobalsV, tmpGlobals) = this.FreshGlobals("fapp")
        val tmpTranslator = new ExpressionTranslator(tmpGlobals, this.oldEtran.globals, currentClass);
        
        // pick new k
        val (funcappKV, funcappK) = Boogie.NewBVar("funcappK", treal, true)
        
        // check definedness of receiver
        (if (!func.f.isStatic) {
          isDefined(obj)
        } else Nil) :::
        // check definedness of arguments
        (args flatMap { arg => isDefined(arg) }) :::
        // check that receiver is not null
        (if (!func.f.isStatic) {
          List(prove(nonNull(Tr(obj)), obj.pos, "Receiver might be null."))
        } else Nil) :::
        // check precondition of the function by exhaling the precondition in tmpHeap/tmpMask/tmpCredits
        Comment("check precondition of call") ::
        BLocal(funcappKV) :: bassume(0.0 < funcappK && 1000.0*funcappK < percentPermission(1)) ::
        bassume(assumption) ::
        BLocals(tmpGlobalsV) :::
        copyState(tmpGlobals, this) :::
        tmpTranslator.Exhale(Preconditions(func.f.spec) map { pre=> (if (func.f.isStatic) SubstVars(pre, func.f.ins, args) else SubstVars(pre, obj, func.f.ins, args), ErrorMessage(func.pos, "Precondition at " + pre.pos + " might not hold."))},
                             "function call",
                             false, funcappK, false) :::
        // size of the heap of callee must be strictly smaller than size of the heap of the caller
        (if(checkTermination) { List(prove(NonEmptyMask(tmpGlobals.mask), func.pos, "The heap of the callee might not be strictly smaller than the heap of the caller.")) } else Nil)
      case unfolding@Unfolding(acc@Access(pred@MemberAccess(obj, f), perm), e) =>
        val (tmpGlobalsV, tmpGlobals) = this.FreshGlobals("unfolding")
        val tmpTranslator = new ExpressionTranslator(tmpGlobals, this.oldEtran.globals, currentClass);
        val o = Tr(obj)
        val (flagV, flag) = Boogie.NewBVar("predFlag", tbool, true)
        
        val receiverOk = isDefined(obj) ::: prove(nonNull(o), obj.pos, "Receiver might be null.");
        val definition = scaleExpressionByPermission(SubstThis(DefinitionOf(pred.predicate), obj), perm, unfolding.pos)
        
        // pick new k
        val (unfoldingKV, unfoldingK) = Boogie.NewBVar("unfoldingK", treal, true)
        // record version of unfolded instance
	val (receiverV, receiver) = Boogie.NewBVar("predRec", tref, true)
	val (versionV, version) = Boogie.NewBVar("predVer", tint, true)
	
        val res = Comment("unfolding") ::
        BLocal(unfoldingKV) :: bassume(0.0 < unfoldingK && 1000.0*unfoldingK < percentPermission(1)) ::
        BLocal(flagV) :: (flag := true) ::
        BLocal(receiverV) :: (receiver := o) ::
        BLocal(versionV) :: (version := etran.Heap.select(o, pred.predicate.FullName)) :::
        // check definedness
        receiverOk ::: isDefined(perm) :::
        // copy state into temporary variables
        BLocals(tmpGlobalsV) :::
        copyState(tmpGlobals, this) :::
        // exhale the predicate
        tmpTranslator.ExhaleDuringUnfold(List((acc, ErrorMessage(unfolding.pos, "Unfolding might fail."))), "unfolding", false, unfoldingK, false) :::
        // inhale the definition of the predicate
        tmpTranslator.Inhale(List(definition), "unfolding", false, unfoldingK, receiver, pred.predicate.FullName, version) :::
        // update the predicate mask to indicate the predicates that are folded under 'pred'
        (if (isOldEtran) Nil
        else etran.keepFoldedLocations(definition, o, pred.predicate, etran.Mask, etran.Heap, etran.fpi.getFoldedPredicates(pred.predicate))) :::
        // check definedness of e in state where the predicate is unfolded
        tmpTranslator.isDefined(e) :::
        bassume(wf(etran.Heap, etran.Mask, etran.SecMask)) :: Nil
        
        // record folded predicate
        //val version = Heap.select(o, pred.predicate.FullName)
        if (!isOldEtran) fpi.addFoldedPredicate(FoldedPredicate(pred.predicate, o, version, fpi.currentConditions, flag))
        
        res
      case Iff(e0,e1) =>
        isDefined(e0) ::: isDefined(e1)
      case Implies(e0,e1) =>
        isDefined(e0) ::: isDefined(e1)(assumption && Tr(e0))
      case And(e0,e1) =>
        isDefined(e0) ::: isDefined(e1)(assumption && Tr(e0))
      case Or(e0,e1) =>
        isDefined(e0) ::: isDefined(e1)(assumption && Boogie.UnaryExpr("!", Tr(e0)))
      case LockBelow(e0,e1) =>
        var df = isDefined(e0) ::: isDefined(e1);
        if (e0.typ.IsRef) {
          df = df ::: List(prove(nonNull(Tr(e0)), e0.pos, "Receiver might be null."), prove(CanRead(Tr(e0),"mu"), e0.pos, "The mu field might not be readable."));
        }
         if (e1.typ.IsRef) {
          df = df ::: List(prove(nonNull(Tr(e1)), e1.pos, "Receiver might be null."), prove(CanRead(Tr(e1),"mu"), e1.pos, "The mu field might not be readable."));
        }
        df
      case e: CompareExpr =>
        isDefined(e.E0) ::: isDefined(e.E1)
      case Div(e0,e1) =>
        isDefined(e0) ::: isDefined(e1) :::
        List(prove(Tr(e1) !=@ 0, e1.pos, "Denominator might be zero."))
      case Mod(e0,e1) =>
        isDefined(e0) ::: isDefined(e1) ::: List(prove(Tr(e1) !=@ 0, e1.pos, "Denominator might be zero."))
      case e: ArithmeticExpr =>
        isDefined(e.E0) ::: isDefined(e.E1)
      case EmptySeq(t) => Nil
      case ExplicitSeq(es) =>
        es flatMap { e => isDefined(e) }
      case Range(min, max) =>
        isDefined(min) ::: isDefined(max) :::
        prove(Tr(min) <= Tr(max), e.pos, "Range minimum might not be smaller or equal to range maximum.")
      case Append(e0, e1) =>
        isDefined(e0) ::: isDefined(e1)
      case at@At(e0, e1) =>
        isDefined(e0) ::: isDefined(e1) :::
        prove(0 <= Tr(e1), at.pos, "Sequence index might be negative.") ::
        prove(Tr(e1) < SeqLength(Tr(e0)), at.pos, "Sequence index might be larger than or equal to the length of the sequence.")
      case Drop(e0, e1) => 
        isDefined(e0) ::: isDefined(e1) :::
        prove(0 <= Tr(e1), e.pos, "Cannot drop less than zero elements.") ::
        prove(Tr(e1) <= SeqLength(Tr(e0)), e.pos, "Cannot drop more than elements than the length of the sequence.")
      case Take(e0, e1) => 
        isDefined(e0) ::: isDefined(e1) :::
        prove(0 <= Tr(e1), e.pos, "Cannot take less than zero elements.") ::
        prove(Tr(e1) <= SeqLength(Tr(e0)), e.pos, "Cannot take more than elements than the length of the sequence.")
      case Length(e) =>
        isDefined(e)
      case Contains(e0, e1) =>
        isDefined(e0) ::: isDefined(e1)
      case Eval(h, e) =>
        val (evalHeap, evalMask, evalSecMask, evalCredits, checks, assumptions) = fromEvalState(h);
        val evalEtran = new ExpressionTranslator(Globals(evalHeap, evalMask, evalSecMask, evalCredits), this.oldEtran.globals, currentClass);
        evalEtran.isDefined(e)
      case _ : SeqQuantification => throw new InternalErrorException("should be desugared")
      case tq @ TypeQuantification(_, _, _, e, (min, max)) =>
        // replace variables since we need locals
        val vars = tq.variables map {v => val result = new Variable(v.id, v.t); result.pos = v.pos; result;}
        prove(Tr(min) <= Tr(max), e.pos, "Range minimum might not be smaller or equal to range maximum.") :::
        (vars map {v => BLocal(Variable2BVarWhere(v))}) :::
        isDefined(SubstVars(e, tq.variables, vars map {v => new VariableExpr(v);}))
      case tq @ TypeQuantification(_, _, _, e, _) =>
        // replace variables since we need locals
        val vars = tq.variables map {v => val result = new Variable(v.id, v.t); result.pos = v.pos; result;}
        (vars map {v => BLocal(Variable2BVarWhere(v))}) :::
        isDefined(SubstVars(e, tq.variables, vars map {v => new VariableExpr(v);}))
    }
  }

  /** Translate an expression, using 'trrec' in the recursive calls (which takes
   *  the expression and an expression translator as argument). The default
   *  behaviour is to translate only pure assertions (and throw and error otherwise).
   */
  def Tr(e: Expression): Boogie.Expr = (Tr(e,false))._1
  def Tr(e: Expression, listNeeded: Boolean) : (Boogie.Expr, List[Boogie.Stmt]) = (Tr(e, (ee,et) => et.Tr(ee,listNeeded), listNeeded))
  def Tr(e: Expression, trrec: (Expression, ExpressionTranslator) => (Boogie.Expr, List[Boogie.Stmt]), listNeeded: Boolean = false): (Boogie.Expr, List[Boogie.Stmt]) = {
    def trrecursive(e: Expression): (Boogie.Expr, List[Boogie.Stmt]) = trrec(e, this)
    desugar(e) match {
    case IntLiteral(n) => (n,Nil)
    case BoolLiteral(b) => (b,Nil)
    case NullLiteral() => (bnull,Nil)
    case StringLiteral(s) =>
      // since there currently are no operations defined on string, except == and !=, just translate
      // each string to a unique number
      (s.hashCode(),Nil)
    case BoogieExpr(b) => (b,Nil)
    case MaxLockLiteral() => throw new InternalErrorException("waitlevel case should be handled in << and == and !=")
    case LockBottomLiteral() => (bLockBottom,Nil)
    case _:ThisExpr => (VarExpr("this"),Nil)
    case _:Result => (VarExpr("result"),Nil)
    case ve : VariableExpr => (VarExpr(ve.v.UniqueName),Nil)
    case fs @ MemberAccess(e,_) =>
      assert(! fs.isPredicate);
      var (ee,ll) = trrecursive(e)
      var r = Heap.select(ee, fs.f.FullName);
      if (fs.f.isInstanceOf[SpecialField] && fs.f.id == "joinable")
        (r !=@ 0,ll) // joinable is encoded as an integer
      else
        (r,ll)
    case _:Permission => throw new InternalErrorException("permission unexpected here")
    case _:PermissionExpr => throw new InternalErrorException("permission expression unexpected here: " + e.pos)
    case _:Credit => throw new InternalErrorException("credit expression unexpected here")
    case Holds(e) =>
      var (ee,ll) = trrecursive(e)
      ((0 < Heap.select(ee, "held")) &&
      !Heap.select(ee, "rdheld"),ll)
    case RdHolds(e) =>
      var (ee,ll) = trrecursive(e)
      (Heap.select(ee, "rdheld"),ll)
    case a: Assigned =>
      (VarExpr("assigned$" + a.v.UniqueName),Nil)
    case Old(e) =>
      trrec(e, oldEtran)
    case IfThenElse(con, then, els) =>
      var (conE,conL) = trrecursive(con)
      var (thenE,thenL) = trrecursive(then)
      var (elsE,elsL) = trrecursive(els)
      (Boogie.Ite(conE, thenE, elsE), (if (listNeeded) (conL ::: thenL ::: elsL) else Nil))  // of type: VarExpr(TrClass(then.typ))
    case Not(e) =>
      var (ee,ll) = trrecursive(e)
      ((! ee),ll)
    case func@FunctionApplication(obj, id, args) => {
      var fullArgs = if (!func.f.isStatic) (obj :: args) else (args)
      var trArgs = fullArgs map {arg => trrecursive(arg)} // yields a list of (Expr, List[Boogie.Stmt]) pairs
      var trArgsE = trArgs.foldRight(List[Boogie.Expr]())((el, ll) => el._1 :: ll) // collect list of exprs
      var trArgsL = if (listNeeded) (trArgs.foldRight(List[Boogie.Stmt]())((x,y) => ((x._2) ::: y))) else Nil // concatenate lists of statements
      (FunctionApp(functionName(func.f), Heap :: trArgsE),trArgsL)
    }
    case uf@Unfolding(acc@Access(pred@MemberAccess(obj, f), perm), ufexpr) =>
      // record extra information resulting from "peeking inside" the predicate, generating appropriate statements (this is used in Exhale of an expression)
            val (ee,ll) = trrecursive(ufexpr)
            val (receiverV, receiver) = Boogie.NewBVar("predRec", tref, true)
            val (versionV, version) = Boogie.NewBVar("predVer", tint, true)
            val (flagV, flag) = Boogie.NewBVar("predFlag", tbool, true)
            val o = TrExpr(obj);
            
            val stmts = if (listNeeded) (BLocal(receiverV) :: (receiver := o) ::
            BLocal(flagV) :: (flag := true) ::
            functionTrigger(o, pred.predicate) ::
            BLocal(versionV) :: (version := Heap.select(o, pred.predicate.FullName)) :::
           // UpdateSecMaskDuringUnfold(pred.predicate, o, Heap.select(o, pred.predicate.FullName), perm, currentK) :::
              TransferPermissionToSecMask(pred.predicate, BoogieExpr(receiver), perm, uf.pos, receiver, pred.predicate.FullName, version)) else Nil
            
            (ee, ll ::: stmts)
    case Iff(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((ee0 <==> ee1), if (listNeeded) (l0 ::: l1) else Nil)
    case Implies(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((ee0 ==> ee1), if (listNeeded) (l0 ::: l1) else Nil)
    case And(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((ee0 && ee1), if (listNeeded) (l0 ::: l1) else Nil)
    case Or(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((ee0 || ee1), if (listNeeded) (l0 ::: l1) else Nil)
    case Eq(e0,e1) =>
      (ShaveOffOld(e0), ShaveOffOld(e1)) match {
        case ((MaxLockLiteral(),o0), (MaxLockLiteral(),o1)) =>
          if (o0 == o1)
            (true, Nil) // in this case, l0 and l1 would be Nil, (and Tr((MaxLockLiteral(),_)) is not defined)
          else
            (MaxLockPreserved, Nil) // in this case, l0 and l1 would be Nil, (and Tr((MaxLockLiteral(),_)) is not defined)
        case ((MaxLockLiteral(),o), _) => 
          var (ee1,l1) = trrecursive(e1)          
          (ChooseEtran(o).IsHighestLock(ee1), l1) // in this case, l0 would be Nil, (and Tr((MaxLockLiteral(),_)) is not defined)
        case (_, (MaxLockLiteral(),o)) => 
          var (ee0,l0) = trrecursive(e0) 
          (ChooseEtran(o).IsHighestLock(ee0), l0) // in this case, l1 would be Nil, (and Tr((MaxLockLiteral(),_)) is not defined)
        case _ => 
          var (ee0,l0) = trrecursive(e0) 
          var (ee1,l1) = trrecursive(e1)          
          ((if(e0.typ.IsSeq) FunctionApp("Seq#Equal", List(ee0, ee1)) else (ee0 ==@ ee1)), if (listNeeded) (l0 ::: l1) else Nil)
      }
    case Neq(e0,e1) =>
      trrecursive(Not(Eq(e0,e1)))
    case Less(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 < ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case AtMost(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 <= ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case AtLeast(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 >= ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case Greater(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 > ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case LockBelow(e0,e1) => {
      def MuValue(b: Expression): (Boogie.Expr, List[Boogie.Stmt]) = (
        trrecursive(b) match {
          case (eb, lb) =>
            ((if (b.typ.IsRef) new Boogie.MapSelect(Heap, eb, "mu") else eb),lb)
          }
        )
      (ShaveOffOld(e0), ShaveOffOld(e1)) match {
        case ((MaxLockLiteral(),o0), (MaxLockLiteral(),o1)) =>
          if (o0 == o1)
            (false,Nil) // in this case, l0 and l1 are guaranteed to be Nil
          else
            (TemporalMaxLockComparison(ChooseEtran(o0), ChooseEtran(o1)),Nil) // in this case, l0 and l1 are guaranteed to be Nil
        case ((MaxLockLiteral(),o), _) => 
          var (ee1, l1) = MuValue(e1)
          (ChooseEtran(o).MaxLockIsBelowX(ee1),l1)
        case (_, (MaxLockLiteral(),o)) => 
          var (ee0, l0) = MuValue(e0)
          (ChooseEtran(o).MaxLockIsAboveX(ee0),l0)
        case _ => 
          var (ee0, l0) = MuValue(e0)
          var (ee1, l1) = MuValue(e1)
          ((new FunctionApp("MuBelow", ee0, ee1)), if (listNeeded) (l0 ::: l1) else Nil) }
    }
    case Plus(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 + ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case Minus(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 - ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case Times(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 * ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case Div(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 / ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case Mod(e0,e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (ee0 % ee1, if (listNeeded) (l0 ::: l1) else Nil)
    case EmptySeq(t) =>
      (createEmptySeq, Nil)
    case ExplicitSeq(es) =>
      es match {
        case Nil => (createEmptySeq, Nil)
        case h :: Nil => 
          var (eh,lh) = trrecursive(h)
          (createSingletonSeq(eh),lh)
        case h :: t =>         
          var (eh,lh) = trrecursive(h)
          var (et,lt) = trrecursive(ExplicitSeq(t))
          ((createAppendSeq(createSingletonSeq(eh), et)), if (listNeeded) (lh ::: lt) else Nil)
      }
    case Range(min, max) =>
      var (emin,lmin) = trrecursive(min)
      var (emax,lmax) = trrecursive(max)
      ((createRange(emin, emax)), if (listNeeded) (lmin ::: lmax) else Nil)
    case Append(e0, e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((createAppendSeq(ee0, ee1)), if (listNeeded) (l0 ::: l1) else Nil)
    case at@At(e0, e1) => 
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((SeqIndex(ee0, ee1)), if (listNeeded) (l0 ::: l1) else Nil)
    case Drop(e0, e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      e1 match {
        case IntLiteral(0) =>
          (ee0, if (listNeeded) (l0 ::: l1) else Nil)
        case _ =>
          ((Boogie.FunctionApp("Seq#Drop", List(ee0, ee1))), if (listNeeded) (l0 ::: l1) else Nil)
      }
    case Take(e0, e1) =>
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      ((Boogie.FunctionApp("Seq#Take", List(ee0, ee1))), if (listNeeded) (l0 ::: l1) else Nil)
    case Length(e) => 
      var (ee,l) = trrecursive(e)
      (SeqLength(ee), l)
    case Contains(e0, e1) => 
      var (ee0,l0) = trrecursive(e0)
      var (ee1,l1) = trrecursive(e1)
      (SeqContains(ee1, ee0), if (listNeeded) (l0 ::: l1) else Nil) // Note: swapping of arguments
    case Eval(h, e) =>
      val (evalHeap, evalMask, evalSecMask, evalCredits, checks, assumptions) = fromEvalState(h);
      val evalEtran = new ExpressionTranslator(Globals(evalHeap, evalMask, evalSecMask, evalCredits), oldEtran.globals, currentClass);
      trrec(e, evalEtran)
    case _:SeqQuantification => throw new InternalErrorException("should be desugared")
    case tq @ TypeQuantification(Forall, _, _, e, _) =>
      val(ee,l) = trrecursive(e)
      val groupedTriggerSets = generateTriggers(tq.variables,e)
      val oneQuantifier : (((List[Trigger],List[Variable])) => Boogie.Expr) = ((trigsAndExtraVars) => (Boogie.Forall(Nil, (tq.variables ::: trigsAndExtraVars._2) map { v => Variable2BVar(v)}, trigsAndExtraVars._1, ee)))
      var firstTriggerSet : (List[Trigger],List[Variable]) = (Nil,Nil);
      var restTriggerSet : List[(List[Trigger],List[Variable])] = Nil;
      
      groupedTriggerSets match {
        case Nil =>
          firstTriggerSet = (Nil,Nil); // we will generate no triggers for this quantifier
          restTriggerSet = Nil
        case ts :: rest =>
          firstTriggerSet = ts;
          restTriggerSet = rest
      }
      (restTriggerSet.foldRight(oneQuantifier(firstTriggerSet))((trigset,expr) => (oneQuantifier(trigset) && expr)),l)
    case tq @ TypeQuantification(Exists, _, _, e, _) =>
      var (ee,l) = trrecursive(e)
      ((Boogie.Exists(Nil, tq.variables map { v => Variable2BVar(v)}, Nil, ee)),l)
    }
  }
  
    // This is used for searching for triggers for quantifiers around the expression "toSearch". The list "vs" gives the variables which need triggering
    // Returns a list of function applications (the framing function) paired with two sets of variables.
    // The first set of variables shows which of the "vs" occur (useful for deciding how to select applications for trigger sets later)
    // The second set of variables indicated the extra boolean variables which were introduced to "hide" problematic logical/comparison operators which may not occur in triggers.
    // e.g., if vs = [x] and toSearch = f(x, y ==> z) then a singleton list will be returned, containing (f(x,b),{x},{b}).
    def getFunctionAppsContaining(vs:List[Variable], toSearch : Expression): (List[(Boogie.FunctionApp,Set[Variable],Set[Variable])]) = {
      var functions: List[(Boogie.FunctionApp,Set[Variable],Set[Variable])] = List() // accumulate candidate functions to return
      var nestedBoundVars : List[Variable] = List(); // count all variables bound in nested quantifiers, to avoid considering function applications mentioning these

      // get all nested bound vars
      toSearch visit {
        _ match {          
          case qe : Quantification =>
            nestedBoundVars :::= qe.variables
          case _ =>
          }
      }

      // get all function applications
      toSearch visit {
      _ match {
        case fapp@FunctionApplication(obj, id, args) =>
          var extraVars : Set[Variable] = Set() // collect extra variables generated for this term
          var containsNestedBoundVars = false // flag to rule out this term
          // closure to generate fresh boolean variable
	  val freshBoolVar : (() => Option[Expression]) = {() =>
	    val newV = new Variable("b", new Type(BoolClass))
            extraVars += newV;
	    Some(new VariableExpr(newV))
	  }          
	  // replaces problematic logical/comparison expressions with fresh boolean variables
	  val boolExprEliminator : (Expression => Option[Expression]) = ((expr:Expression) =>
	  expr match {
	    case exp@Not(e) => freshBoolVar()
            case exp@Iff(e0,e1) => freshBoolVar()
	    case exp@Implies(e0,e1) => freshBoolVar()
	    case exp@And(e0,e1) => freshBoolVar()
	    case exp@Or(e0,e1) => freshBoolVar()
	    case Eq(e0,e1) => freshBoolVar()
	    case Neq(e0,e1) => freshBoolVar()
	    case Less(e0,e1) => freshBoolVar()
	    case AtMost(e0,e1) => freshBoolVar()
	    case AtLeast(e0,e1) => freshBoolVar()
	    case Greater(e0,e1) => freshBoolVar()
	    case _ => None
	  });
          var containedVars : Set[Variable] = Set()
          val processedArgs = args map (_.transform(boolExprEliminator)) // eliminate all boolean expressions forbidden from triggers, and replace with "extraVars"
          // collect all the sought (vs) variables in the function application
          processedArgs map {e => e visit {_ match {
            case ve@VariableExpr(s) =>
              val v : Variable = ve.v
              if (nestedBoundVars.contains(v)) (containsNestedBoundVars = true);
              if (vs.contains(v)) (containedVars += v)
            case _ =>}
          }
          }
          if (!containsNestedBoundVars && !containedVars.isEmpty) {
            val fullArgs = if (!fapp.f.isStatic) (obj :: processedArgs) else (processedArgs)
            val noOldETran = this.UseCurrentAsOld();       
            val trArgs = fullArgs map {arg => noOldETran.Tr(arg)} // translate args
            val triggerFunctionName = functionName(fapp.f) + "#limited#trigger";
            functions ::= (FunctionApp(triggerFunctionName, trArgs),containedVars,extraVars)
          }
        case _ =>}
      }
      functions
    }
    
    

  
  // Precondition : if vars is non-empty then every (f,vs) pair in functs satisfies the property that vars and vs are not disjoint.
  // Finds trigger sets by selecting entries from "functs" until all of "vars" occur, and accumulating the extra variables needed for each function term.
  // Returns a list of the trigger sets found, paired with the extra boolean variables they use
def buildTriggersCovering(vars : Set[Variable], functs : List[(Boogie.FunctionApp,Set[Variable],Set[Variable])], currentTrigger : List[Expr], extraVars : Set[Variable]) : List[(Trigger,Set[Variable])] = {
  if (vars.isEmpty) (List((Boogie.Trigger(currentTrigger),extraVars))) // we have found a suitable trigger set
    else (functs match {
      case Nil => Nil // this branch didn't result in a solution
      case ((f,vs,extra) :: rest) => {  
        val needed : Set[Variable] = vars.diff(vs) // variables still not triggered
        // try adding the next element of functs, or not..
        buildTriggersCovering(needed, (rest.filter(func => !func._2.intersect(needed).isEmpty)), f :: currentTrigger, extraVars|extra) ::: buildTriggersCovering(vars, rest, currentTrigger, extraVars)
        }
      }
    )
  }
  
  
  // Generates trigger sets to cover the variables "vs", by searching the expression "toSearch".
  // Returns a list of pairs of lists of trigger sets couple with the extra variables they require to be quantified over (each list of triggers must contain trigger sets which employ exactly the same extra variables).
  def generateTriggers(vs: List[Variable], toSearch : Expression) : List[(List[Trigger],List[Variable])] = {
    val functionApps : (List[(Boogie.FunctionApp,Set[Variable],Set[Variable])]) = getFunctionAppsContaining(vs, toSearch) // find suitable function applications
    if (functionApps.isEmpty) List() else {
      var triggerSetsToUse : List[(Trigger,Set[Variable])] = buildTriggersCovering(Set() ++ vs, functionApps, Nil, Set())
      var groupedTriggerSets : List[(List[Trigger],List[Variable])] = List() // group trigger sets by those which use the same sets of extra boolean variables
      
      while (!triggerSetsToUse.isEmpty) {
        triggerSetsToUse.partition((ts : (Trigger,Set[Variable])) => triggerSetsToUse.head._2.equals(ts._2)) match {
          case (sameVars,rest) => 
            triggerSetsToUse = rest;
            groupedTriggerSets ::= ((sameVars map (_._1)), sameVars.head._2.toList)
        }
      }
      groupedTriggerSets
    }
  }

  
  /** translate everything, including permissions and credit expressions */
  // Since this is only used in axiom generation (so far), the ability to generate "side-effects" from the evaluation of unfolding expressions (as in the list of boogie statements returned from Tr) is not implemented here (in axioms the side-effects are not wanted anyway)
  def TrAll(e: Expression): Expr = {
    def TrAllHelper(e: Expression, etran: ExpressionTranslator): Expr = e match {
      case pred@MemberAccess(e, p) if pred.isPredicate =>
        val tmp = Access(pred, Full); tmp.pos = pred.pos
        TrAll(tmp)
      case acc@Access(e,perm) =>
        val memberName = if(e.isPredicate) e.predicate.FullName else e.f.FullName;
        CanRead(Tr(e.e), memberName)
      case acc @ AccessSeq(s, Some(member), perm) =>
        if (member.isPredicate) throw new NotSupportedException("not yet implemented");
        val memberName = member.f.FullName;
        val (refV, ref) = Boogie.NewBVar("ref", tref, true);
        (SeqContains(Tr(s), ref) ==> CanRead(ref, memberName)).forall(refV)
      case _ => (etran.Tr(e, (ee : Expression, et : ExpressionTranslator) => (TrAllHelper(ee,et),Nil)))._1 //wrap TrAllHelper to give it the return type needed for Tr.
    }
    TrAllHelper(e, this)
  }

  def ShaveOffOld(e: Expression): (Expression, Boolean) = e match {
    case Old(e) =>
      val (f,o) = ShaveOffOld(e)
      (f,true)
    case _ => (e,false)
  }
  def IsMaxLockLit(e: Expression) = {
    val (f,o) = ShaveOffOld(e)
    f.isInstanceOf[MaxLockLiteral]
  }

  /**********************************************************************
  *****************          INHALE/EXHALE              *****************
  **********************************************************************/

  def Inhale(predicates: List[Expression], occasion: String, check: Boolean, currentK: Expr, unfoldReceiver: VarExpr = null, unfoldPredicateName: String = null, unfoldVersion: VarExpr = null): List[Boogie.Stmt] = {
    if (predicates.size == 0) return Nil;
    
    //val (ihV, ih) = Boogie.NewBVar("inhaleHeap", theap, true)
    Comment("inhale (" + occasion + ")") ::
    //BLocal(ihV) :: Boogie.Havoc(ih) ::
    //bassume(IsGoodInhaleState(ih, Heap, Mask, SecMask)) ::
    (for (p <- predicates) yield Inhale(p, Heap, check, currentK, unfoldReceiver, unfoldPredicateName, unfoldVersion)).flatten :::
    bassume(AreGoodMasks(Mask, SecMask)) ::
    bassume(wf(Heap, Mask, SecMask)) ::
    Comment("end inhale")
  }
  
  def InhalePermission(perm: Permission, obj: Expr, memberName: String, currentK: Expr, m: Expr = Mask): List[Boogie.Stmt] = {
    
    val (f, stmts) = extractKFromPermission(perm, currentK)
    val n = extractEpsilonsFromPermission(perm);
    
    stmts :::
    (perm.permissionType match {
      case PermissionType.Mixed =>
        bassume(f > 0.0 || (f == 0.0 && n > 0.0)) ::
        IncPermission(obj, memberName, f, m) :::
        IncPermissionEpsilon(obj, memberName, n, m)
      case PermissionType.Epsilons =>
        bassume(n > 0.0) ::
        IncPermissionEpsilon(obj, memberName, n, m)
      case PermissionType.Fraction =>
        bassume(f > 0.0) ::
        IncPermission(obj, memberName, f, m)
    })
  }
  
  def Inhale(p: Expression, ih: Boogie.Expr, check: Boolean, currentK: Expr, unfoldReceiver: VarExpr, unfoldPredicateName : String, unfoldVersion: VarExpr): List[Boogie.Stmt] =
    InhaleImplementation(p, ih, check, currentK, false, unfoldReceiver, unfoldPredicateName, unfoldVersion)
  
  def InhaleToSecMask(p: Expression, unfoldingReceiver: VarExpr = null, unfoldingPredicateName: String = null, unfoldingVersion: VarExpr = null): List[Boogie.Stmt] =
    InhaleImplementation(p, Heap /* it should not matter what we pass here */, false /* check */, -1 /* it should not matter what we pass here */, true, unfoldingReceiver, unfoldingPredicateName, unfoldingVersion)

  def InhaleImplementation(p: Expression, ih: Boogie.Expr, check: Boolean, currentK: Expr, transferToSecMask: Boolean, unfoldReceiver: VarExpr = null, unfoldPredicateName: String = null, unfoldVersion: VarExpr = null): List[Boogie.Stmt] = desugar(p) match {
    case pred@MemberAccess(e, p) if pred.isPredicate => 
      val chk = (if (check) {
        isDefined(e)(true) ::: 
        bassert(nonNull(Tr(e)), e.pos, "Receiver might be null.") :: Nil
      } else Nil)
      val tmp = Access(pred, Full);
      tmp.pos = pred.pos;
      chk ::: InhaleImplementation(tmp, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion)
    case AccessAll(obj, perm) => throw new InternalErrorException("should be desugared")
    case AccessSeq(s, None, perm) => throw new InternalErrorException("should be desugared")
    case acc@Access(e,perm) =>
      val trE = Tr(e.e)
      val module = currentClass.module;
      val memberName = if(e.isPredicate) e.predicate.FullName else e.f.FullName;

      // List(bassert(nonNull(trE), acc.pos, "The target of the acc predicate might be null."))
      (if(check) isDefined(e.e)(true) ::: isDefined(perm)(true)
      else Nil) :::
      bassume(nonNull(trE)) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      (if(e.isPredicate) Nil else List(bassume(TypeInformation(new Boogie.MapSelect(Heap, trE, memberName), e.f.typ.typ)))) :::
      InhalePermission(perm, trE, memberName, currentK, (if (transferToSecMask) SecMask else Mask)) :::
      // record "inside" relationship for predicate instances
      (if(e.isPredicate && unfoldReceiver != null) bassume(FunctionApp("#predicateInside#", unfoldReceiver :: VarExpr(unfoldPredicateName) :: unfoldVersion :: trE :: VarExpr(memberName) :: Heap.select(trE, memberName) :: Nil)) :: Nil else Nil) :::
      bassume(AreGoodMasks(Mask, SecMask)) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      bassume(wf(ih, Mask, SecMask))
    case acc @ AccessSeq(s, Some(member), perm) =>
	  if (transferToSecMask) throw new NotSupportedException("not yet implemented")
      if (member.isPredicate) throw new NotSupportedException("not yet implemented");
      val e = Tr(s);
      val memberName = member.f.FullName;
      val (refV, ref) = Boogie.NewBVar("ref", tref, true);
      
      val (r, stmts) = extractKFromPermission(perm, currentK)
      val n = extractEpsilonsFromPermission(perm);

      stmts :::
      // assume that the permission is positive
      bassume((SeqContains(e, ref) ==>
      (perm.permissionType match {
        case PermissionType.Fraction => r > 0.0
        case PermissionType.Mixed    => r > 0.0 || (r == 0.0 && n > 0.0)
        case PermissionType.Epsilons => n > 0.0
      })).forall(refV)) ::
      (if (check) isDefined(s)(true) ::: isDefined(perm)(true) else Nil) :::
      {
        val (aV,a) = Boogie.NewTVar("alpha");
        val (refV, ref) = Boogie.NewBVar("ref", tref, true);
        val (fV, f) = Boogie.NewBVar("f", FieldType(a), true);
        (Heap := Lambda(List(aV), List(refV, fV),
          (SeqContains(e, ref) && f ==@ memberName).thenElse
            (ih(ref, f), Heap(ref, f)))) ::
        bassume((SeqContains(e, ref) ==> TypeInformation(Heap(ref, memberName), member.f.typ.typ)).forall(refV))
      } :::
      bassume(wf(Heap, Mask, SecMask)) ::
      // update the map
      {
        val (aV,a) = Boogie.NewTVar("alpha");
        val (fV, f) = Boogie.NewBVar("f", FieldType(a), true);
        val (pcV,pc) = Boogie.NewBVar("p", tperm, true);
        Mask := Lambda(List(aV), List(refV, fV),
          (SeqContains(e, ref) && f ==@ memberName).thenElse
            (Lambda(List(), List(pcV),
              Boogie.Ite(pc ==@ "perm$R",
                Mask(ref, f)("perm$R") + r,
                Mask(ref, f)("perm$N") + n)),
            Mask(ref, f)))
      } :::
      bassume(AreGoodMasks(Mask, SecMask)) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      bassume(wf(ih, Mask, SecMask))
    case cr@Credit(ch, n) =>
      val trCh = Tr(ch)
      (if (check)
         isDefined(ch)(true) :::
         bassert(nonNull(trCh), ch.pos, "The target of the credit predicate might be null.") :::
         isDefined(cr.N)(true)
       else
         Nil) :::
      new Boogie.MapUpdate(Credits, trCh, new Boogie.MapSelect(Credits, trCh) + Tr(cr.N))
    case Implies(e0,e1) =>
      (if(check) isDefined(e0)(true) else Nil) :::
      Boogie.If(Tr(e0), InhaleImplementation(e1, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion), Nil)
    case IfThenElse(con, then, els) =>
      (if(check) isDefined(con)(true) else Nil) :::
      Boogie.If(Tr(con), InhaleImplementation(then, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion), InhaleImplementation(els, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion))
    case And(e0,e1) =>
      InhaleImplementation(e0, ih, check, currentK, transferToSecMask) ::: InhaleImplementation(e1, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion)
    case holds@Holds(e) =>
      val trE = Tr(e);
      (if(check)
         isDefined(e)(true) :::
         bassert(nonNull(trE), holds.pos, "The target of the holds predicate might be null.")
       else Nil) :::
      bassume(AreGoodMasks(Mask, SecMask)) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      bassume(wf(ih, Mask, SecMask)) ::
      new Boogie.MapUpdate(Heap, trE, VarExpr("held"),
                      new Boogie.MapSelect(ih, trE, "held")) ::
      bassume(0 < new Boogie.MapSelect(ih, trE, "held")) ::
      bassume(! new Boogie.MapSelect(ih, trE, "rdheld")) ::
      bassume(new Boogie.MapSelect(ih, trE, "mu") !=@ bLockBottom) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      bassume(AreGoodMasks(Mask, SecMask)) ::
      bassume(wf(Heap, Mask, SecMask)) ::
      bassume(wf(ih, Mask, SecMask))
    case Eval(h, e) => 
	  if (transferToSecMask) throw new NotSupportedException("not yet implemented")
      val (evalHeap, evalMask, evalSecMask, evalCredits, checks, proofOrAssume) = fromEvalState(h);
      val (preGlobalsV, preGlobals) = etran.FreshGlobals("eval")
      val preEtran = new ExpressionTranslator(preGlobals, currentClass)
      BLocals(preGlobalsV) :::
      (preGlobals.mask := ZeroMask) ::
      (preGlobals.secmask := ZeroMask) ::
      // Should we start from ZeroMask instead of an arbitrary mask? In that case, assume submask(preEtran.Mask, evalMask); at the end!
      (if(check) checks else Nil) :::
      // havoc the held field when inhaling eval(o.release, ...)
      (if(h.isInstanceOf[ReleaseState]) {
        val (freshHeldV, freshHeld) = NewBVar("freshHeld", tint, true);
        val obj = Tr(h.target());
        List(BLocal(freshHeldV), bassume((0<Heap.select(obj, "held")) <==> (0<freshHeld)), (Heap.select(obj, "held") := freshHeld))
      } else Nil) :::
      bassume(AreGoodMasks(preEtran.Mask, preEtran.SecMask)) ::
      bassume(wf(preEtran.Heap, preEtran.Mask, preEtran.SecMask)) ::
      bassume(proofOrAssume) ::
      preEtran.InhaleImplementation(e, ih, check, currentK, transferToSecMask, unfoldReceiver, unfoldPredicateName, unfoldVersion) :::
      bassume(preEtran.Heap ==@ evalHeap) ::
      bassume(submask(preEtran.Mask, evalMask))
    case uf@Unfolding(acc@Access(pred@MemberAccess(obj, f), perm), ufexpr) =>
	  if (transferToSecMask) return Nil
      // handle unfolding like the next case, but also record permissions of the predicate
      // in the secondary mask and track the predicate in the auxilary information
      val (receiverV, receiver) = Boogie.NewBVar("predRec", tref, true)
      val (versionV, version) = Boogie.NewBVar("predVer", tint, true)
      val (flagV, flag) = Boogie.NewBVar("predFlag", tbool, true)
      val o = TrExpr(obj);
      
      val stmts = BLocal(receiverV) :: (receiver := o) ::
      BLocal(flagV) :: (flag := true) ::
      functionTrigger(o, pred.predicate) ::
      BLocal(versionV) :: (version := Heap.select(o, pred.predicate.FullName)) ::
      (if(check) isDefined(uf)(true) else
        if (isOldEtran) Nil else
        // remove secondary permissions (if any), and add them again
        UpdateSecMaskDuringUnfold(pred.predicate, o, Heap.select(o, pred.predicate.FullName), perm, currentK) :::
        TransferPermissionToSecMask(pred.predicate, BoogieExpr(receiver), perm, uf.pos, receiver, pred.predicate.FullName, version)
      ) :::
      bassume(Tr(uf))
      
      // record folded predicate
      if (!isOldEtran) fpi.addFoldedPredicate(FoldedPredicate(pred.predicate, receiver, version, fpi.currentConditions, flag))
      
      stmts
    case e =>
      (if(check) isDefined(e)(true) else Nil) :::
      bassume(Tr(e))
  }
  
  /** Transfer the permissions mentioned in the body of the predicate to the
   * secondary mask.
  */
  def TransferPermissionToSecMask(pred: Predicate, obj: Expression, perm: Permission, pos: Position, unfoldingReceiver: VarExpr = null, unfoldingPredicateName: String = null, unfoldingVersion: VarExpr = null): List[Stmt] = {
    var definition = scaleExpressionByPermission(SubstThis(DefinitionOf(pred), obj), perm, pos)
    // go through definition and handle all permisions correctly
    InhaleToSecMask(definition, unfoldingReceiver, unfoldingPredicateName, unfoldingVersion)
  }
  
  // Exhale is done in two passes: In the first run, everything except permissions
  // which need exact checking are exhaled. Then, in the second run, those
  // permissions are exhaled. The behaviour is controlled with the parameter
  // onlyExactCheckingPermissions.
  // The reason for this behaviour is that we want to support preconditions like
  // "acc(o.f,100-rd) && acc(o.f,rd)", which should be equivalent to a full
  // permission to o.f. However, when we exhale in the given order, we cannot
  // use inexact checking for the abstract read permission ("acc(o.f,rd)"), as
  // this would introduce the (unsound) assumption that "methodcallk < mask[o.f]".
  // To avoid detecting this, we exhale all abstract read permissions first (or,
  // more precisely, we exhale all permissions with exact checking later).
  
  /** Regular exhale */
  def Exhale(predicates: List[(Expression, ErrorMessage)], occasion: String, check: Boolean, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = 
    Exhale(Mask, SecMask, predicates, occasion, check, currentK, exactchecking)
  /** Exhale as part of a unfold statement (just like a regular exhale, but no heap havocing) */
  def ExhaleDuringUnfold(predicates: List[(Expression, ErrorMessage)], occasion: String, check: Boolean, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = 
    Exhale(Mask, SecMask, predicates, occasion, check, currentK, exactchecking, false, -1, false, null, true)
  /** Regular exhale with specific mask/secmask */
  def Exhale(m: Expr, sm: Expr, predicates: List[(Expression, ErrorMessage)], occasion: String, check: Boolean, currentK: Expr, exactchecking: Boolean): List[Boogie.Stmt] = {
    Exhale(m, sm, predicates, occasion, check, currentK, exactchecking, false, -1, false, null, false)
  }
  /** Exhale that transfers permission to the secondary mask */
  def ExhaleAndTransferToSecMask(receiver: Expr, pred: Predicate, predicates: List[(Expression, ErrorMessage)], occasion: String, currentK: Expr, exactchecking: Boolean, foldReceiver: VarExpr = null, foldPredicateName: String = null, foldVersion: VarExpr = null): List[Boogie.Stmt] = {
    Exhale(receiver, pred, Mask, SecMask, predicates, occasion, false, currentK, exactchecking, true /* transfer to SecMask */, -1, false, null, false, foldReceiver, foldPredicateName, foldVersion)
  }
  /** Remove permission from the secondary mask, and assume all assertions that
   * would get generated. Recursion is bouded by the parameter depth.
   */
  def UpdateSecMask(predicate: Predicate, receiver: Expr, version: Expr, perm: Permission, currentK: Expr): List[Stmt] = {
    val depth = etran.fpi.getRecursionBound(predicate)
    UpdateSecMask(predicate, receiver, version, perm, currentK, depth, Map())
  }
  def UpdateSecMaskDuringUnfold(predicate: Predicate, receiver: Expr, version: Expr, perm: Permission, currentK: Expr): List[Stmt] = {
    UpdateSecMask(predicate, receiver, version, perm, currentK, 1, Map())
  }
  // no longer relevant (as of Sept 2012), since we don't take this approach with secondary mask any more
  def UpdateSecMask(predicate: Predicate, receiver: Expr, version: Expr, perm: Permission, currentK: Expr, depth: Int, previousReceivers: Map[String,List[Expr]]): List[Stmt] = {
    Nil 
  }
  /** Most general form of exhale; implements all the specific versions above */
  // Note: If isUpdatingSecMask, then m is actually a secondary mask, and at the
  // moment we do not want an assumption IsGoodMask(SecMask). Therefore, we omit
  // those if isUpdatingSecMask.
  // Furthermore, we do not want to generate assumptions that we have enough
  // permission available (something like "assume 50% >= SecMask[obj,f]"; note
  // that these assumption come from the assertions that check that the necessary
  // permissions are available, and are then turned into assumptions by
  // assertion2assumption.
  //
  // Assumption 1: if isUpdatingSecMask==true, then the exhale heap is not used at
  // all, and the regular heap is not changed.
  // Assumption 2: If isUpdatingSecMask is false, then recurseOnPredicatesDepth
  // is meaningless (and should be -1 by convention). Only if isUpdatingSecMask
  // is true, then the depth is important. It is initially set in the method
  // UpdateSecMask (because only there we have precise knowledge of what upper
  // bound we want to use).
  // Assumption 3: Similarly, if isUpdatingSecMask is false, then the list
  // previousReceivers is not needed, and thus null.
  // Assumption 4+5: duringUnfold can only be true if transferPermissionToSecMask
  // and isUpdatingSecMask are not.
  // Assumption 6: foldReceiver, foldPredicateName, foldVersion are all either non-null (in which case this exhale is the body of the corresponding predicate instance being folded) or all non-null.
  def Exhale(m: Expr, sm: Expr, predicates: List[(Expression, ErrorMessage)], occasion: String, check: Boolean, currentK: Expr, exactchecking: Boolean, transferPermissionToSecMask: Boolean, recurseOnPredicatesDepth: Int, isUpdatingSecMask: Boolean, previousReceivers: Map[String,List[Expr]], duringUnfold: Boolean, foldReceiver: VarExpr = null, foldPredicateName: String = null, foldVersion: VarExpr = null): List[Boogie.Stmt] =
    Exhale(null, null, m, sm, predicates, occasion, check, currentK, exactchecking, transferPermissionToSecMask, recurseOnPredicatesDepth, isUpdatingSecMask, previousReceivers, duringUnfold, foldReceiver, foldPredicateName, foldVersion)
  def Exhale(receiver: Expr, pred: Predicate, m: Expr, sm: Expr, predicates: List[(Expression, ErrorMessage)], occasion: String, check: Boolean, currentK: Expr, exactchecking: Boolean, transferPermissionToSecMask: Boolean, recurseOnPredicatesDepth: Int, isUpdatingSecMask: Boolean, previousReceivers: Map[String,List[Expr]], duringUnfold: Boolean, foldReceiver: VarExpr, foldPredicateName: String, foldVersion: VarExpr): List[Boogie.Stmt] = {
    assert ((isUpdatingSecMask && recurseOnPredicatesDepth >= 0) || (!isUpdatingSecMask && recurseOnPredicatesDepth == -1)) // check assumption 2
    assert (isUpdatingSecMask || (previousReceivers == null))
    assert (!(isUpdatingSecMask && duringUnfold))
    assert (!(transferPermissionToSecMask && duringUnfold))
    assert ((foldReceiver == null) == (foldPredicateName == null))
    assert ((foldPredicateName == null) == (foldVersion == null))
    if (predicates.size == 0) return Nil;
    val (ehV, eh) = Boogie.NewBVar("exhaleHeap", theap, true)
    var (emV, em: Expr) = Boogie.NewBVar("exhaleMask", tmask, true)
    Comment("begin exhale (" + occasion + ")") ::
    (if (!isUpdatingSecMask && !duringUnfold && !transferPermissionToSecMask)
      BLocal(emV) :: (em := m) ::
      BLocal(ehV) :: Boogie.Havoc(eh) :: Nil
    else {
      em = m
      Nil
    }) :::
    (for (p <- predicates) yield ExhaleHelper(p._1, em, sm, eh, p._2, check, currentK, exactchecking, false, transferPermissionToSecMask, recurseOnPredicatesDepth, isUpdatingSecMask, previousReceivers, duringUnfold, foldReceiver, foldPredicateName, foldVersion )).flatten :::
    (for (p <- predicates) yield ExhaleHelper(p._1, em, sm, eh, p._2, check, currentK, exactchecking, true, transferPermissionToSecMask, recurseOnPredicatesDepth, isUpdatingSecMask, previousReceivers, duringUnfold, foldReceiver, foldPredicateName, foldVersion )).flatten :::
    (if (!isUpdatingSecMask && !duringUnfold && !transferPermissionToSecMask)
      (m := em) ::
      bassume(IsGoodExhaleState(eh, Heap, m, sm)) ::
      //restoreFoldedLocations(m, Heap, eh) :::
      (Heap := eh) :: Nil
    else Nil) :::
    (if (isUpdatingSecMask) Nil else bassume(AreGoodMasks(m, sm)) :: Nil) :::
    bassume(wf(Heap, m, sm)) ::
    Comment("end exhale")
  }
  
  /** copy all the values of locations that are folded under any predicate (that we care about) one or more levels down from 'heap' to 'exhaleHeap' */
  def restoreFoldedLocations(mask: Expr, heap: Boogie.Expr, exhaleHeap: Boogie.Expr): List[Boogie.Stmt] = {
    val foldedPredicates = etran.fpi.getFoldedPredicates()
    (for (fp <- foldedPredicates) yield {
      val stmts = bassume(IsGoodExhalePredicateState(exhaleHeap, heap, heap.select(fp.receiver, fp.predicate.FullName+"#m")))
      Boogie.If(CanRead(fp.receiver, fp.predicate.FullName, mask, ZeroMask) && heap.select(fp.receiver, fp.predicate.FullName) ==@ fp.version, stmts, Nil) :: Nil
    }) flatten
  }
  
  /** the actual recursive method for restoreFoldedLocationsHelperPred */
  def keepFoldedLocations(expr: Expression, foldReceiver: Expr, foldPred: Predicate, mask: Expr, heap: Boogie.Expr, otherPredicates: List[FoldedPredicate]): List[Boogie.Stmt] = {
    val f = (expr: Expression) => keepFoldedLocations(expr, foldReceiver, foldPred, mask, heap, otherPredicates)
    expr match {
      case pred@MemberAccess(e, p) if pred.isPredicate =>
        val tmp = Access(pred, Full);
        tmp.pos = pred.pos;
        f(tmp)
      case AccessAll(obj, perm) =>
        throw new InternalErrorException("not implemented yet")
      case AccessSeq(s, None, perm) =>
        throw new InternalErrorException("not implemented yet")
      case acc@Access(e,perm) =>
        val memberName = if (e.isPredicate) e.predicate.FullName else e.f.FullName;
        val trE = Tr(e.e)
        (if (e.isPredicate) {
          val (ttV,tt) = Boogie.NewTVar("T")
          val (refV, ref) = Boogie.NewBVar("ref", tref, true)
          val (fV, f) = Boogie.NewBVar("f", FieldType(tt), true)
          val (pmV, pm: Expr) = Boogie.NewBVar("newPredicateMask", tpmask, true)
          val assumption = (heap.select(foldReceiver, foldPred.FullName+"#m").select(ref, f.id) || heap.select(trE, memberName+"#m").select(ref, f.id)) ==> pm.select(ref, f.id)
          BLocal(pmV) :: Havoc(pm) ::
          bassume(new Boogie.Forall(ttV, fV, assumption).forall(refV)) ::
          (heap.select(foldReceiver, foldPred.FullName+"#m") := pm) :: Nil
        } else Nil) :::
        (heap.select(foldReceiver, foldPred.FullName+"#m").select(trE, memberName) := true) :: Nil
      case acc @ AccessSeq(s, Some(member), perm) =>
        throw new InternalErrorException("not implemented yet")
      case cr@Credit(ch, n) =>
        Nil
      case Implies(e0,e1) =>
        Boogie.If(Tr(e0), f(e1), Nil)
      case IfThenElse(con, then, els) =>
        Boogie.If(Tr(con), f(then), f(els))
      case And(e0,e1) =>
        f(e0) ::: f(e1)
      case holds@Holds(e) =>
        Nil
      case Eval(h, e) =>
        Nil
      case e =>
        Nil
    }
  }
  
  // see comment in front of method exhale about parameter isUpdatingSecMask
  def ExhalePermission(perm: Permission, obj: Expr, memberName: String, currentK: Expr, pos: Position, error: ErrorMessage, em: Boogie.Expr, exactchecking: Boolean, isUpdatingSecMask: Boolean): List[Boogie.Stmt] = {
    val (f, stmts) = extractKFromPermission(perm, currentK)
    val n = extractEpsilonsFromPermission(perm);
    
    val res = stmts :::
    (perm.permissionType match {
      case PermissionType.Mixed =>
        bassert(f > 0.0 || (f == 0.0 && n > 0.0), error.pos, error.message + " The permission at " + pos + " might not be positive.") ::
        (if (isUpdatingSecMask) DecPermissionBoth2(obj, memberName, f, n, em, error, pos, exactchecking)
        else DecPermissionBoth(obj, memberName, f, n, em, error, pos, exactchecking))
      case PermissionType.Epsilons =>
        bassert(n > 0.0, error.pos, error.message + " The permission at " + pos + " might not be positive.") ::
        (if (isUpdatingSecMask) DecPermissionEpsilon2(obj, memberName, n, em, error, pos)
        else DecPermissionEpsilon(obj, memberName, n, em, error, pos))
      case PermissionType.Fraction =>
        bassert(f > 0.0, error.pos, error.message + " The permission at " + pos + " might not be positive.") ::
        (if (isUpdatingSecMask) DecPermission2(obj, memberName, f, em, error, pos, exactchecking)
        else DecPermission(obj, memberName, f, em, error, pos, exactchecking))
    })
    
    if (isUpdatingSecMask)
      res filter (a => !a.isInstanceOf[Boogie.Assert]) // we do not want "insufficient permission checks" at the moment, as they will be turned into (possibly wrong) assumptions
    else res
  }
  
  // does this permission require exact checking, or is it enough to check that we have any permission > 0?
  def needExactChecking(perm: Permission, default: Boolean): Boolean = {
    perm match {
      case Full => true
      case Frac(_) => true
      case Epsilon => default
      case PredicateEpsilon(_) | MonitorEpsilon(_) | ChannelEpsilon(_) | ForkEpsilon(_) => true
      case MethodEpsilon => default
      case Epsilons(p) => true
      case Star => false
      case IntPermTimes(lhs, rhs) => needExactChecking(rhs, default)
      case PermTimes(lhs, rhs) => {
        val l = needExactChecking(lhs, default);
        val r = needExactChecking(rhs, default);
        (if (l == false || r == false) false else true) // if one side doesn't need exact checking, the whole multiplication doesn't
      }
      case PermPlus(lhs, rhs) => {
        val l = needExactChecking(lhs, default);
        val r = needExactChecking(rhs, default);
        (if (l == true || r == true) true else false) // if one side needs exact checking, use exact
      }
      case PermMinus(lhs, rhs) => {
        val l = needExactChecking(lhs, default);
        val r = needExactChecking(rhs, default);
        (if (l == true || r == true) true else false) // if one side needs exact checking, use exact
      }
    }
  }
  
  def ExhaleHelper(p: Expression, m: Boogie.Expr, sm: Boogie.Expr, eh: Boogie.Expr, error: ErrorMessage, check: Boolean, currentK: Expr, exactchecking: Boolean, onlyExactCheckingPermissions: Boolean, transferPermissionToSecMask: Boolean, recurseOnPredicatesDepth: Int, isUpdatingSecMask: Boolean, previousReceivers: Map[String,List[Expr]], duringUnfold: Boolean, foldReceiver: VarExpr = null, foldPredicateName: String = null, foldVersion: VarExpr = null): List[Boogie.Stmt] = {
    val LocalExhaleHelper = (expr: Expression) => ExhaleHelper(expr, m, sm, eh, error, check, currentK, exactchecking, onlyExactCheckingPermissions, transferPermissionToSecMask, recurseOnPredicatesDepth, isUpdatingSecMask, previousReceivers, duringUnfold, foldReceiver, foldPredicateName, foldVersion)
    desugar(p) match {
    case pred@MemberAccess(e, p) if pred.isPredicate =>
      val tmp = Access(pred, Full);
      tmp.pos = pred.pos;
      LocalExhaleHelper(tmp)
    case AccessAll(obj, perm) => throw new InternalErrorException("should be desugared")
    case AccessSeq(s, None, perm) => throw new InternalErrorException("should be desugared")
    case acc@Access(e,perm) =>
      val ec = needExactChecking(perm, exactchecking)
      if (ec != onlyExactCheckingPermissions) Nil else {
        val memberName = if(e.isPredicate) e.predicate.FullName else e.f.FullName;
        val (starKV, starK) = NewBVar("starK", treal, true);
        val trE = Tr(e.e)
        
        // check definedness
        (if(check) isDefined(e.e)(true) :::
                   bassert(nonNull(Tr(e.e)), error.pos, error.message + " The target of the acc predicate at " + acc.pos + " might be null.") else Nil) :::
        (if(e.isPredicate && foldReceiver != null) bassume(FunctionApp("#predicateInside#", foldReceiver :: VarExpr(foldPredicateName) :: foldVersion :: trE :: VarExpr(memberName) :: Heap.select(trE, memberName) :: Nil)) :: Nil else Nil) :::
        // if the mask does not contain sufficient permissions, try folding acc(e, fraction)
        // TODO: include automagic again
        // check that the necessary permissions are there and remove them from the mask
        ExhalePermission(perm, trE, memberName, currentK, acc.pos, error, m, ec, isUpdatingSecMask) :::
        // update version number (if necessary)
        (if (e.isPredicate && !isUpdatingSecMask)
          Boogie.If(!CanRead(trE, memberName, m, sm), // if we cannot access the predicate anymore, then its version will be havoced
            (if (!duringUnfold) (if (!transferPermissionToSecMask) bassume(Heap.select(trE, memberName) < eh.select(trE, memberName)) :: Nil else Nil) // assume that the predicate's version grows monotonically
            else { // duringUnfold -> the heap is not havoced, thus we need to locally havoc the version
              val (oldVersV, oldVers) = Boogie.NewBVar("oldVers", tint, true)
              val (newVersV, newVers) = Boogie.NewBVar("newVers", tint, true)
              BLocal(oldVersV) :: (oldVers := Heap.select(trE, memberName)) ::
              BLocal(newVersV) :: Boogie.Havoc(newVers) :: (Heap.select(trE, memberName) := newVers) ::
              bassume(oldVers < Heap.select(trE, memberName)) :: Nil
            }),
            Nil) :: Nil
        else Nil) :::
        bassume(wf(Heap, m, sm)) :::
        (if (m != Mask || sm != SecMask) bassume(wf(Heap, Mask, SecMask)) :: Nil else Nil)
      }
    case acc @ AccessSeq(s, Some(member), perm) =>
      if (member.isPredicate) throw new NotSupportedException("not yet implemented");
      val ec = needExactChecking(perm, exactchecking);
      
      if (ec != onlyExactCheckingPermissions) Nil else {
        val e = Tr(s);
        val memberName = member.f.FullName;
        val (r, stmts) = extractKFromPermission(perm, currentK)
        val n = extractEpsilonsFromPermission(perm);
        
        stmts :::
        (if (check) isDefined(s)(true) ::: isDefined(perm)(true) else Nil) :::
        {
          val (aV,a) = Boogie.NewTVar("alpha");
          val (refV, ref) = Boogie.NewBVar("ref", tref, true);
          val (fV, f) = Boogie.NewBVar("f", FieldType(a), true);
          val (pcV,pc) = Boogie.NewBVar("p", tperm, true);
          val mr = m(ref, memberName)("perm$R");
          val mn = m(ref, memberName)("perm$N");
          
          // assert that the permission is positive
          bassert((SeqContains(e, ref) ==>
            (perm.permissionType match {
              case PermissionType.Fraction => r > 0.0
              case PermissionType.Mixed    => r > 0.0 || (r == 0.0 && n > 0.0)
              case PermissionType.Epsilons => n > 0.0
            })).forall(refV), error.pos, error.message + " The permission at " + acc.pos + " might not be positive.") ::
          // make sure enough permission is available
          //  (see comment in front of method exhale for explanation of isUpdatingSecMask)
          (if (!isUpdatingSecMask) bassert((SeqContains(e, ref) ==>
            ((perm,perm.permissionType) match {
              case _ if !ec     => mr > 0.0
              case (Star,_)     => mr > 0.0
              case (_,PermissionType.Fraction) => r <= mr && (r ==@ mr ==> 0.0 <= mn)
              case (_,PermissionType.Mixed)    => r <= mr && (r ==@ mr ==> n <= mn)
              case (_,PermissionType.Epsilons) => mr ==@ 0.0 ==> n <= mn
            })).forall(refV), error.pos, error.message + " Insufficient permission at " + acc.pos + " for " + member.f.FullName) :: Nil else Nil) :::
          // additional assumption on k if we have a star permission or use inexact checking
          ( perm match {
              case _ if !ec => bassume((SeqContains(e, ref) ==> (r < mr)).forall(refV)) :: Nil
              case Star => bassume((SeqContains(e, ref) ==> (r < mr)).forall(refV)) :: Nil
              case _ => Nil
          }) :::
          // update the map
          (m := Lambda(List(aV), List(refV, fV),
            (SeqContains(e, ref) && f ==@ memberName).thenElse(
              Lambda(List(), List(pcV), (pc ==@ "perm$R").thenElse(mr - r, mn - n)),
              m(ref, f))))
        } :::
        bassume(wf(Heap, m, sm)) :::
        (if (m != Mask || sm != SecMask) bassume(wf(Heap, Mask, SecMask)) :: Nil else Nil)
      }
    case cr@Credit(ch, n) if !onlyExactCheckingPermissions =>
      val trCh = Tr(ch)
      (if (check)
         isDefined(ch)(true) :::
         bassert(nonNull(trCh), ch.pos, "The target of the credit predicate might be null.") :::
         isDefined(cr.N)(true)
       else
         Nil) :::
       // only update the heap if we are not updating the secondary mask
      (if (!isUpdatingSecMask)
        new Boogie.MapUpdate(Credits, trCh, new Boogie.MapSelect(Credits, trCh) - Tr(cr.N)) :: Nil
      else Nil)
    case Implies(e0,e1) =>
      (if(check && !onlyExactCheckingPermissions) isDefined(e0)(true) else Nil) :::
      Boogie.If(Tr(e0), LocalExhaleHelper(e1), Nil)
    case IfThenElse(con, then, els) =>
      (if(check) isDefined(con)(true) else Nil) :::
      Boogie.If(Tr(con), LocalExhaleHelper(then), LocalExhaleHelper(els))
    case And(e0,e1) =>
      LocalExhaleHelper(e0) ::: LocalExhaleHelper(e1)
    case holds@Holds(e) if !onlyExactCheckingPermissions => 
      (if(check) isDefined(e)(true) :::
      bassert(nonNull(Tr(e)), error.pos, error.message + " The target of the holds predicate at " + holds.pos + " might be null.") :: Nil else Nil) :::
      bassert(0 < new Boogie.MapSelect(Heap, Tr(e), "held"), error.pos, error.message + " The current thread might not hold lock at " + holds.pos + ".") ::
      bassert(! new Boogie.MapSelect(Heap, Tr(e), "rdheld"), error.pos, error.message + " The current thread might hold the read lock at " + holds.pos + ".") ::
      (if (isUpdatingSecMask) Nil else bassume(AreGoodMasks(m, sm)) :: Nil) :::
      bassume(wf(Heap, m, sm))
    case Eval(h, e) if !onlyExactCheckingPermissions =>
      val (evalHeap, evalMask, evalSecMask, evalCredits, checks, proofOrAssume) = fromEvalState(h);
      val (preGlobalsV, preGlobals) = etran.FreshGlobals("eval")
      val preEtran = new ExpressionTranslator(preGlobals, currentClass);
      BLocals(preGlobalsV) :::
      copyState(preGlobals, Globals(evalHeap, evalMask, evalSecMask, evalCredits)) :::
      (if(check) checks else Nil) :::
      (if (isUpdatingSecMask) Nil else bassume(AreGoodMasks(preEtran.Mask, preEtran.SecMask)) :: Nil) :::
      bassume(wf(preEtran.Heap, preEtran.Mask, preEtran.SecMask)) ::
      bassert(proofOrAssume, p.pos, "Arguments for joinable might not match up.") ::
      preEtran.Exhale(List((e, error)), "eval", check, currentK, exactchecking)
    case e if !onlyExactCheckingPermissions => 
      val (ee,ll) = Tr(e, true) // keep list ll of statements here, in case we are missing effects from unfolding expressions (if we do not call isDefined(e) below, we won't add secondary permissions/"inside" instances)
      (if(check) isDefined(e)(true) else ll) ::: List(bassert(ee, error.pos, error.message + " The expression at " + e.pos + " might not evaluate to true."))
    case _ => Nil
  }}
  
  def extractKFromPermission(expr: Permission, currentK: Expr): (Expr, List[Boogie.Stmt]) = expr match {
    case Full => (permissionFull, Nil)
    case Epsilon => (currentK, Nil)
    case Epsilons(_) => (0.0, Nil)
    case PredicateEpsilon(_) => (predicateK, Nil)
    case MonitorEpsilon(_) => (monitorK, Nil)
    case ChannelEpsilon(_) => (channelK, Nil)
    case MethodEpsilon => (currentK, Nil)
    case ForkEpsilon(token) =>
      val fk = etran.Heap.select(Tr(token), forkK)
      (fk, bassume(0.0 < fk && fk < percentPermission(1)) /* this is always true for forkK */)
    case Star =>
      val (starKV, starK) = NewBVar("starK", treal, true);
      (starK, BLocal(starKV) :: bassume(starK > 0.0 /* an upper bound is provided later by DecPermission */) :: Nil)
    case Frac(p) => (percentPermission(Tr(p)), Nil)
    case IntPermTimes(lhs, rhs) => {
      val (r, rs) = extractKFromPermission(rhs, currentK)
      (int2real(lhs) * r, rs)
    }
    case PermTimes(lhs, rhs) => {
      val (l, ls) = extractKFromPermission(lhs, currentK)
      val (r, rs) = extractKFromPermission(rhs, currentK)
      val (resV, res) = Boogie.NewBVar("productK", treal, true)
      (res, ls ::: rs ::: BLocal(resV) :: bassume(permissionFull * res ==@ l * r) :: Nil)
    }
    case PermPlus(lhs, rhs) => {
      val (l, ls) = extractKFromPermission(lhs, currentK)
      val (r, rs) = extractKFromPermission(rhs, currentK)
      (l + r, Nil)
    }
    case PermMinus(lhs, rhs) => {
      val (l, ls) = extractKFromPermission(lhs, currentK)
      val (r, rs) = extractKFromPermission(rhs, currentK)
      (l - r, Nil)
    }
  }
  
  def extractEpsilonsFromPermission(expr: Permission): Expr = expr match {
    case _:Write => 0.0
    case Epsilons(n) => int2real(Tr(n))
    case PermTimes(lhs, rhs) => 0.0 // multiplication cannot give epsilons
    case IntPermTimes(lhs, rhs) => int2real(lhs) * extractEpsilonsFromPermission(rhs)
    case PermPlus(lhs, rhs) => {
      val l = extractEpsilonsFromPermission(lhs)
      val r = extractEpsilonsFromPermission(rhs)
      l + r
    }
    case PermMinus(lhs, rhs) => {
      val l = extractEpsilonsFromPermission(lhs)
      val r = extractEpsilonsFromPermission(rhs)
      l - r
    }
  }

  def fromEvalState(h: EvalState): (Expr, Expr, Expr, Expr, List[Stmt], Expr) = {
    h match {
      case AcquireState(obj) =>
        (AcquireHeap(Heap.select(Tr(obj), "held")),
         AcquireMask(Heap.select(Tr(obj), "held")),
         AcquireSecMask(Heap.select(Tr(obj), "held")),
         AcquireCredits(Heap.select(Tr(obj), "held")),
         isDefined(obj)(true), true)
      case ReleaseState(obj) =>
        (LastSeenHeap(Heap.select(Tr(obj), "mu"), Heap.select(Tr(obj), "held")),
         LastSeenMask(Heap.select(Tr(obj), "mu"), Heap.select(Tr(obj), "held")),
         LastSeenSecMask(Heap.select(Tr(obj), "mu"), Heap.select(Tr(obj), "held")),
         LastSeenCredits(Heap.select(Tr(obj), "mu"), Heap.select(Tr(obj), "held")),
         isDefined(obj)(true), true)
      case CallState(token, obj, id, args) =>
        val argsSeq = CallArgs(Heap.select(Tr(token), "joinable"));

        var f: ((Expression, Int)) => Expr =
            (a: (Expression, Int)) => a match {
                case (VariableExpr("?"),_) => true: Expr
                case _ => new MapSelect(argsSeq, a._2) ==@ Tr(a._1)
              }
        var ll: List[(Expression, Int)] = null
        ll = (args zip (1 until args.length+1).toList);
        
        var i = 0;
        (CallHeap(Heap.select(Tr(token), "joinable")), 
         CallMask(Heap.select(Tr(token), "joinable")),
         CallSecMask(Heap.select(Tr(token), "joinable")),
         CallCredits(Heap.select(Tr(token), "joinable")),
         isDefined(token)(true) :::
         isDefined(obj)(true) :::
         (args flatMap { a => isDefined(a)(true)}) :::
         bassert(CanRead(Tr(token), "joinable"), obj.pos, "Joinable field of the token might not be readable.") ::
         bassert(Heap.select(Tr(token), "joinable") !=@ 0, obj.pos, "Token might not be active."),
         (new MapSelect(argsSeq, 0) ==@ Tr(obj) ) &&
         ((ll map { 
            f
         }).foldLeft(true: Expr){ (a: Expr, b: Expr) => a && b})
        )
    }
  }

  /**********************************************************************
  *****************          PERMISSIONS                *****************
  **********************************************************************/

  def CanRead(obj: Boogie.Expr, field: Boogie.Expr, m: Boogie.Expr, sm: Boogie.Expr): Boogie.Expr = new Boogie.FunctionApp("CanRead", List(m, sm, obj, field))
  def CanRead(obj: Boogie.Expr, field: String, m: Boogie.Expr, sm: Boogie.Expr): Boogie.Expr = CanRead(obj, new Boogie.VarExpr(field), m, sm)
  def CanRead(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = new Boogie.FunctionApp("CanRead", List(Mask, SecMask, obj, field))
  def CanRead(obj: Boogie.Expr, field: String): Boogie.Expr = CanRead(obj, new Boogie.VarExpr(field))
  def CanReadForSure(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = new Boogie.FunctionApp("CanReadForSure", List(Mask, obj, field))
  def CanReadForSure(obj: Boogie.Expr, field: String): Boogie.Expr = CanReadForSure(obj, new Boogie.VarExpr(field))
  def CanReadForSure2(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = new Boogie.FunctionApp("CanReadForSure", List(SecMask, obj, field))
  def CanWrite(obj: Boogie.Expr, field: Boogie.Expr): Boogie.Expr = new Boogie.FunctionApp("CanWrite", Mask, obj, field)
  def CanWrite(obj: Boogie.Expr, field: String): Boogie.Expr = CanWrite(obj, new Boogie.VarExpr(field))
  def HasNoPermission(obj: Boogie.Expr, field: String) =
    (new Boogie.MapSelect(Mask, obj, field, "perm$R") ==@ 0.0) &&
    (new Boogie.MapSelect(Mask, obj, field, "perm$N") ==@ 0.0)
  def SetNoPermission(obj: Boogie.Expr, field: String, mask: Boogie.Expr) =
    Boogie.Assign(new Boogie.MapSelect(mask, obj, field), Boogie.VarExpr("Permission$Zero"))
  def HasFullPermission(obj: Boogie.Expr, field: String, mask: Boogie.Expr) =
    (new Boogie.MapSelect(mask, obj, field, "perm$R") ==@ permissionFull) &&
    (new Boogie.MapSelect(mask, obj, field, "perm$N") ==@ 0.0)
  def SetFullPermission(obj: Boogie.Expr, field: String) =
    Boogie.Assign(new Boogie.MapSelect(Mask, obj, field), Boogie.VarExpr("Permission$Full"))

  def IncPermission(obj: Boogie.Expr, field: String, howMuch: Boogie.Expr, m: Expr = Mask): List[Boogie.Stmt] = {
    MapUpdate3(m, obj, field, "perm$R", new Boogie.MapSelect(m, obj, field, "perm$R") + howMuch) :: Nil
  }
  def IncPermissionEpsilon(obj: Boogie.Expr, field: String, epsilons: Boogie.Expr, m: Expr = Mask): List[Boogie.Stmt] = {
    MapUpdate3(m, obj, field, "perm$N", new Boogie.MapSelect(m, obj, field, "perm$N") + epsilons) ::
    bassume(wf(Heap, m, SecMask)) :: Nil
  }
  def DecPermission(obj: Boogie.Expr, field: String, howMuch: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position, exactchecking: Boolean): List[Boogie.Stmt] = {
    val fP: Boogie.Expr = new Boogie.MapSelect(mask, obj, field, "perm$R")
    val fC: Boogie.Expr = new Boogie.MapSelect(mask, obj, field, "perm$N")
    (if (exactchecking) bassert(howMuch <= fP && (howMuch ==@ fP ==> 0.0 <= fC), error.pos, error.message + " Insufficient fraction at " + pos + " for " + field + ".") :: Nil
    else bassert(fP > 0.0, error.pos, error.message + " Insufficient fraction at " + pos + " for " + field + ".") :: bassume(howMuch < fP)) :::
    MapUpdate3(mask, obj, field, "perm$R", new Boogie.MapSelect(mask, obj, field, "perm$R") - howMuch)
  }
  def DecPermissionEpsilon(obj: Boogie.Expr, field: String, epsilons: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position): List[Boogie.Stmt] = {
    val xyz = new Boogie.MapSelect(mask, obj, field, "perm$N")
    bassert((new Boogie.MapSelect(mask, obj, field, "perm$R") ==@ 0.0) ==> (epsilons <= xyz), error.pos, error.message + " Insufficient epsilons at " + pos + "  for " + field + ".") ::
    MapUpdate3(mask, obj, field, "perm$N", new Boogie.MapSelect(mask, obj, field, "perm$N") - epsilons) ::
    bassume(wf(Heap, Mask, SecMask)) :: Nil
  }
  def DecPermissionBoth(obj: Boogie.Expr, field: String, howMuch: Boogie.Expr, epsilons: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position, exactchecking: Boolean): List[Boogie.Stmt] = {
    val fP: Boogie.Expr = new Boogie.MapSelect(mask, obj, field, "perm$R")
    val fC: Boogie.Expr = new Boogie.MapSelect(mask, obj, field, "perm$N")

    (if (exactchecking) bassert(howMuch <= fP && (howMuch ==@ fP ==> epsilons <= fC), error.pos, error.message + " Insufficient permission at " + pos + " for " + field + ".") :: Nil
    else bassert(fP > 0.0, error.pos, error.message + " Insufficient permission at " + pos + " for " + field + ".") :: bassume(howMuch < fP)) :::
    MapUpdate3(mask, obj, field, "perm$N", fC - epsilons) ::
    MapUpdate3(mask, obj, field, "perm$R", fP - howMuch) ::
    bassume(wf(Heap, Mask, SecMask)) :: Nil
  }
  def DecPermission2(obj: Boogie.Expr, field: String, howMuch: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position, exactchecking: Boolean): List[Boogie.Stmt] = {
    DecPermission(obj, field, howMuch, mask, error, pos, exactchecking) :::
    Boogie.If(new Boogie.MapSelect(mask, obj, field, "perm$R") < 0.0,
        MapUpdate3(mask, obj, field, "perm$R", 0.0),
        Nil)
  }
  def DecPermissionEpsilon2(obj: Boogie.Expr, field: String, epsilons: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position): List[Boogie.Stmt] = {
    DecPermissionEpsilon(obj, field, epsilons, mask, error, pos) :::
    Boogie.If(new Boogie.MapSelect(mask, obj, field, "perm$N") < 0.0,
        MapUpdate3(mask, obj, field, "perm$N", 0.0),
        Nil)
  }
  def DecPermissionBoth2(obj: Boogie.Expr, field: String, howMuch: Boogie.Expr, epsilons: Boogie.Expr, mask: Boogie.Expr, error: ErrorMessage, pos: Position, exactchecking: Boolean): List[Boogie.Stmt] = {
    DecPermissionBoth(obj, field, howMuch, epsilons, mask, error, pos, exactchecking) :::
    Boogie.If(new Boogie.MapSelect(mask, obj, field, "perm$R") < 0.0,
        MapUpdate3(mask, obj, field, "perm$R", 0.0),
        Nil) ::
    Boogie.If(new Boogie.MapSelect(mask, obj, field, "perm$N") < 0.0,
        MapUpdate3(mask, obj, field, "perm$N", 0.0),
        Nil) :: Nil
  }


  def MapUpdate3(m: Boogie.Expr, arg0: Boogie.Expr, arg1: String, arg2: String, rhs: Boogie.Expr) = {
    // m[a,b,c] := rhs
    // m[a,b][c] := rhs
    // m[a,b] := map[a,b][c := rhs]
    val m01 = new Boogie.MapSelect(m, arg0, arg1)
    Boogie.Assign(m01, Boogie.MapStore(m01, arg2, rhs))
  }

  def DecPerm(m: Expr, e: Expr, f: Expr, i: Expr) = FunctionApp("DecPerm", List(m, e, f, i))
  def DecEpsilons(m: Expr, e: Expr, f: Expr, i: Expr) = FunctionApp("DecEpsilons", List(m, e, f, i))
  def IncPerm(m: Expr, e: Expr, f: Expr, i: Expr) = FunctionApp("IncPerm", List(m, e, f, i))
  def IncEpsilons(m: Expr, e: Expr, f: Expr, i: Expr) = FunctionApp("IncEpsilons", List(m, e, f, i))


  def MaxLockIsBelowX(x: Boogie.Expr) = {  // waitlevel << x
    val (oV, o) = Boogie.NewBVar("o", tref, true)
    new Boogie.Forall(oV,
                      (contributesToWaitLevel(o, Heap, Credits)) ==>
                      new Boogie.FunctionApp("MuBelow", new Boogie.MapSelect(Heap, o, "mu"), x))
  }
  def MaxLockIsAboveX(x: Boogie.Expr) = {  // x << waitlevel
    val (oV, o) = Boogie.NewBVar("o", tref, true)
    new Boogie.Exists(oV,
                      (contributesToWaitLevel(o, Heap, Credits)) &&
                      new Boogie.FunctionApp("MuBelow", x, new Boogie.MapSelect(Heap, o, "mu")))
  }
  def IsHighestLock(x: Boogie.Expr) = {
    // (forall r :: r.held ==> r.mu << x || r.mu == x)
    val (rV, r) = Boogie.NewBVar("r", tref, true)
    new Boogie.Forall(rV,
                      contributesToWaitLevel(r, Heap, Credits) ==>
                        (new Boogie.FunctionApp("MuBelow", new MapSelect(Heap, r, "mu"), x) ||
                        (new Boogie.MapSelect(Heap, r, "mu") ==@ x)))
  }
  def MaxLockPreserved = {  // old(waitlevel) == waitlevel
    // I don't know what the best encoding of this conding is, so I'll try a disjunction.
    // Disjunct b0 is easier to prove, but it is stronger than b1.

    // (forall r: ref ::
    //     old(Heap)[r,held] == Heap[r,held] &&
    //     (Heap[r,held] ==> old(Heap)[r,mu] == Heap[r,mu]))
    val (rV, r) = Boogie.NewBVar("r", tref, true)
    val b0 = new Boogie.Forall(rV,
                      ((0 < new Boogie.MapSelect(oldEtran.Heap, r, "held")) ==@
                       (0 < new Boogie.MapSelect(Heap, r, "held"))) &&
                      ((0 < new Boogie.MapSelect(Heap, r, "held")) ==>
                        (new Boogie.MapSelect(oldEtran.Heap, r, "mu") ==@
                         new Boogie.MapSelect(Heap, r, "mu"))))

    // (forall o, p ::
    //     old(o.held) && (forall r :: old(r.held) ==> old(r.mu) << old(o.mu) || old(r.mu)==old(o.mu)) &&
    //         p.held  && (forall r ::     r.held  ==>     r.mu  <<     p.mu  ||     r.mu ==    p.mu )
    //     ==>
    //     old(o.mu) == p.mu)
    val (oV, o) = Boogie.NewBVar("o", tref, true)
    val (pV, p) = Boogie.NewBVar("p", tref, true)
    val b1 = Boogie.Forall(Nil, List(oV,pV), Nil,
                  ((0 < new Boogie.MapSelect(oldEtran.Heap, o, "held")) &&
                   oldEtran.IsHighestLock(new Boogie.MapSelect(oldEtran.Heap, o, "mu")) &&
                   (0 < new Boogie.MapSelect(Heap, p, "held")) &&
                   IsHighestLock(new Boogie.MapSelect(Heap, p, "mu")))
                  ==>
                  (new Boogie.MapSelect(oldEtran.Heap, o, "mu") ==@ new Boogie.MapSelect(Heap, p, "mu")))
    b0 || b1
  }
  def TemporalMaxLockComparison(e0: ExpressionTranslator, e1: ExpressionTranslator) = {  // e0(waitlevel) << e1(waitlevel)
    // (exists o ::
    //     e1(o.held) &&
    //     (forall r :: e0(r.held) ==> e0(r.mu) << e1(o.mu)))
    val (oV, o) = Boogie.NewBVar("o", tref, true)
    new Boogie.Exists(oV,
                      (0 < new Boogie.MapSelect(e1.Heap, o, "held")) &&
                      e0.MaxLockIsBelowX(new Boogie.MapSelect(e1.Heap, o, "mu")))
  }
}

  // implicit (uses etran)

  implicit def expression2Expr(e: Expression) = etran.Tr(e);

  // prelude (uses etran)
  def isHeld(e: Expr): Expr = (0 < etran.Heap.select(e, "held"))
  def isRdHeld(e: Expr): Expr = etran.Heap.select(e, "rdheld")
  def isShared(e: Expr): Expr = etran.Heap.select(e, "mu") !=@ bLockBottom

object TranslationHelper {
  // implicit conversions

  implicit def string2VarExpr(s: String) = VarExpr(s);
  implicit def field2Expr(f: Field) = VarExpr(f.FullName);
  implicit def bool2Bool(b: Boolean): Boogie.BoolLiteral = Boogie.BoolLiteral(b)
  implicit def int2Int(n: Int): Boogie.IntLiteral = Boogie.IntLiteral(n)
   implicit def real2Real(d: Double): Boogie.RealLiteral = Boogie.RealLiteral(d)
  implicit def lift(s: Boogie.Stmt): List[Boogie.Stmt] = List(s)
  implicit def type2BType(cl: Class): BType = {
    if(cl.IsRef) {
      tref
    } else if(cl.IsBool) {
      tbool
    } else if(cl.IsMu) {
      tmu
    } else if(cl.IsInt) {
      tint
    } else if(cl.IsString) {
      tstring
    } else if(cl.IsSeq) {
      tseq(type2BType(cl.asInstanceOf[SeqClass].parameter))
    } else {
      assert(false, "unexpected type: " + cl.FullName); null
    }
  }
  implicit def decl2DeclList(decl: Decl): List[Decl] = List(decl)
  implicit def function2RichFunction(f: Function) = RichFunction(f);

  case class RichFunction(f: Function) {
    def apply(args: List[Expr]) = {
      FunctionApp(functionName(f), args)
    }
  }
  
  // prelude definitions
  
  def ModuleType = NamedType("ModuleName");
  def ModuleName(cl: Class) = "module#" + cl.module.id;
  def TypeName = NamedType("TypeName");
  def FieldType(tp: BType) = IndexedType("Field", tp);
  def bassert(e: Expr, pos: Position, msg: String) = new Boogie.Assert(e, pos, msg)
  def bassert(e: Expr, pos: Position, msg: String, subsumption: Int) = new Boogie.Assert(e, pos, msg, subsumption)
  def bassume(e: Expr) = Boogie.Assume(e)
  def BLocal(id: String, tp: BType) = new Boogie.LocalVar(id, tp)
  def BLocal(x: Boogie.BVar) = Boogie.LocalVar(x)
  def BLocals(xs: List[Boogie.BVar]) = xs map BLocal
  def tArgSeq = NamedType("ArgSeq");
  def tref = NamedType("ref");
  def tbool = NamedType("bool");
  def treal = NamedType("real");
  def tmu  = NamedType("Mu");
  def tint = NamedType("int");
  def tstring = NamedType("string");
  def tseq(arg: BType) = IndexedType("Seq", arg)
  def theap = NamedType("HeapType");
  def tmask = NamedType("MaskType");
  def tpmask = NamedType("PMaskType");
  def tcredits = NamedType("CreditsType");
  def tperm = NamedType("PermissionComponent");
  def ZeroMask = VarExpr("ZeroMask");
  def ZeroPMask = VarExpr("ZeroPMask");
  def ZeroCredits = VarExpr("ZeroCredits");
  def HeapName = "Heap";
  def MaskName = "Mask";
  def SecMaskName = "SecMask";
  def CreditsName = "Credits";
  def GlobalNames = List(HeapName, MaskName, SecMaskName, CreditsName);
  def CanAssumeFunctionDefs = VarExpr("CanAssumeFunctionDefs");
  def FunctionContextHeight = VarExpr("FunctionContextHeight");
  def permissionFull = percentPermission(100);
  def permissionOnePercent = percentPermission(1);
  def percentPermission(e: Expr) = {
    Chalice.percentageSupport match {
      case 0 => int2real(e)*0.01
      case 1 => FunctionApp("Fractions", List(e))
    }
  }
  def int2real(e: Expr): Expr = FunctionApp("real", List(e))
  def forkK = "forkK";
  def channelK = "channelK";
  def monitorK = "monitorK";
  def predicateK = "predicateK";
  def CurrentModule = VarExpr("CurrentModule");
  def dtype(e: Expr) = FunctionApp("dtype", List(e))
  def functionName(f: Function) = "#" + f.FullName;
  def className(cl: Class) = Boogie.VarExpr(cl.id + "#t")
  def bnull = Boogie.Null();
  def bLockBottom = VarExpr("$LockBottom")
  def nonNull(e: Expr): Expr = e !=@ bnull
  def LastSeenHeap(sharedBit: Expr, heldBit: Expr) = FunctionApp("LastSeen$Heap", List(sharedBit, heldBit))
  def LastSeenMask(sharedBit: Expr, heldBit: Expr) = FunctionApp("LastSeen$Mask", List(sharedBit, heldBit))
  def LastSeenSecMask(sharedBit: Expr, heldBit: Expr) = FunctionApp("LastSeen$SecMask", List(sharedBit, heldBit))
  def LastSeenCredits(sharedBit: Expr, heldBit: Expr) = FunctionApp("LastSeen$Credits", List(sharedBit, heldBit))
  def AcquireHeap(heldBit: Expr) = FunctionApp("Acquire$Heap", List(heldBit))
  def AcquireMask(heldBit: Expr) = FunctionApp("Acquire$Mask", List(heldBit))
  def AcquireSecMask(heldBit: Expr) = FunctionApp("Acquire$SecMask", List(heldBit))
  def AcquireCredits(heldBit: Expr) = FunctionApp("Acquire$Credits", List(heldBit))
  def CallHeap(joinableBit: Expr) = FunctionApp("Call$Heap", List(joinableBit))
  def CallMask(joinableBit: Expr) = FunctionApp("Call$Mask", List(joinableBit))
  def CallSecMask(joinableBit: Expr) = FunctionApp("Call$SecMask", List(joinableBit))
  def CallCredits(joinableBit: Expr) = FunctionApp("Call$Credits", List(joinableBit))
  def CallArgs(joinableBit: Expr) = FunctionApp("Call$Args", List(joinableBit))
  def submask(m0: Expr, m1: Expr) = FunctionApp("submask", List(m0, m1))

  def wf(g: Globals) = FunctionApp("wf", List(g.heap, g.mask, g.secmask));
  def wf(h: Expr, m: Expr, sm: Expr) = FunctionApp("wf", List(h, m, sm));
  def IsGoodMask(m: Expr) = FunctionApp("IsGoodMask", List(m))
  def AreGoodMasks(m: Expr, sm: Expr) = IsGoodMask(m) // && IsGoodMask(sm) /** The second mask does currently not necessarily contain positive permissions, which means that we cannot assume IsGoodMask(sm). This might change in the future if we see a need for it */
  def IsGoodInhaleState(ih: Expr, h: Expr, m: Expr, sm: Expr) = FunctionApp("IsGoodInhaleState", List(ih,h,m,sm))
  def IsGoodExhaleState(eh: Expr, h: Expr, m: Expr, sm: Expr) = FunctionApp("IsGoodExhaleState", List(eh,h,m,sm))
  def IsGoodExhalePredicateState(eh: Expr, h: Expr, pm: Expr) = FunctionApp("IsGoodExhalePredicateState", List(eh,h,pm))
  def contributesToWaitLevel(e: Expr, h: Expr, c: Expr) =
    (0 < h.select(e, "held")) || h.select(e, "rdheld")  || (new Boogie.MapSelect(c, e) < 0)
  def NonEmptyMask(m: Expr) = ! FunctionApp("EmptyMask", List(m))
  def NonPredicateField(f: String) = FunctionApp("NonPredicateField", List(VarExpr(f)))
  def PredicateField(f: String) = FunctionApp("PredicateField", List(VarExpr(f)))
  def cast(a: Expr, b: Expr) = FunctionApp("cast", List(a, b))
  
  // output a dummy function assumption that serves as trigger for the function
  // definition axiom.
  def functionTrigger(receiver: Expr, predicate: Predicate): Stmt = {
    bassume(FunctionApp("#" + predicate.FullName+"#trigger", receiver :: Nil))
  }
  
  def emptyPartialHeap: Expr = Boogie.VarExpr("emptyPartialHeap")
  def heapFragment(dep: Expr): Expr = Boogie.FunctionApp("heapFragment", List(dep))
  def combine(l: Expr, r: Expr): Expr = {
    (l,r) match {
      case (VarExpr("emptyPartialHeap"), a) => a
      case (a, VarExpr("emptyPartialHeap")) => a
      case _ => Boogie.FunctionApp("combine", List(l, r))
    }
  }
  def tpartialheap = NamedType("PartialHeapType");
  
  def copyState(globals: Globals, et: ExpressionTranslator): List[Stmt] =
    copyState(globals, et.globals)
  def copyState(globals: Globals, globalsToCopyFrom: Globals): List[Stmt] = {
    (for ((a, b) <- globals.list zip globalsToCopyFrom.list) yield (a := b)) :::
    bassume(wf(globals)) :: Nil
  }
  def resetState(et: ExpressionTranslator): List[Stmt] = resetState(et.globals)
  def resetState(globals: Globals): List[Stmt] = {
    (globals.mask := ZeroMask) ::
    (globals.secmask := ZeroMask) ::
    (globals.credits := ZeroCredits) ::
    Havoc(globals.heap) ::
    Nil
  }
  
  // sequences

  def createEmptySeq = FunctionApp("Seq#Empty", List())
  def createSingletonSeq(e: Expr) = FunctionApp("Seq#Singleton", List(e)) 
  def createAppendSeq(a: Expr, b: Expr) = FunctionApp("Seq#Append", List(a, b)) 
  def createRange(min: Expr, max: Expr) = FunctionApp("Seq#Range", List(min, max))
  def SeqLength(s: Expr) = FunctionApp("Seq#Length", List(s))
  def SeqContains(s: Expr, elt: Expr) = FunctionApp("Seq#Contains", List(s, elt))
  def SeqIndex(s: Expr, idx: Expr) = FunctionApp("Seq#Index", List(s, idx))

  def Variable2BVar(v: Variable) = new Boogie.BVar(v.UniqueName, v.t.typ)
  def Variable2BVarWhere(v: Variable) = NewBVarWhere(v.UniqueName, v.t)
  def NewBVarWhere(id: String, tp: Type) = {
    new Boogie.BVar(id, tp.typ){
      override val where = TypeInformation(new Boogie.VarExpr(id), tp.typ) }
  }

  // scale an expression (such as the definition of a predicate) by a permission
  def scaleExpressionByPermission(expr: Expression, perm1: Permission, pos: Position): Expression = {
    val result = expr match {
      case pred@MemberAccess(o, p) if pred.isPredicate => Access(pred, perm1)
      case Access(e, perm2) => Access(e, multiplyPermission(perm1, perm2, pos))
      case AccessSeq(e, f, perm2) => AccessSeq(e, f, multiplyPermission(perm1, perm2, pos))
      case And(lhs, rhs) => And(scaleExpressionByPermission(lhs, perm1, pos), scaleExpressionByPermission(rhs, perm1, pos))
      case Implies(lhs, rhs) => Implies(lhs, scaleExpressionByPermission(rhs, perm1, pos))
      case _ if ! expr.isInstanceOf[PermissionExpr] => expr
      case _ => throw new InternalErrorException("Unexpected expression, unable to scale.");
    }
    result.pos = expr.pos;
    result
  }
  
  // multiply two permissions
  def multiplyPermission(perm1: Permission, perm2: Permission, pos: Position): Permission = {
    val result = (perm1,perm2) match {
      case (Full,p2) => p2
      case (p1,Full) => p1
      case (Epsilons(_),_) => throw new NotSupportedException(pos + ": Scaling epsilon permissions with non-full permissions is not possible.")
      case (_,Epsilons(_)) => throw new NotSupportedException(pos + ": Scaling epsilon permissions with non-full permissions is not possible.")
      case (p1,p2) => PermTimes(p1,p2)
    }
    result
  }

  def TypeInformation(e: Boogie.Expr, cl: Class): Boogie.Expr = {
    if (cl.IsRef) {
      (e ==@ Boogie.Null()) || (dtype(e) ==@ className(cl))
    } else if (cl.IsSeq && cl.parameters(0).IsRef) {
      val (v,ve) = Boogie.NewBVar("$i", tint, true);
      (0 <= ve && ve < SeqLength(e)
        ==> TypeInformation(SeqIndex(e,ve), cl.parameters(0))).forall(v);
    } else {
      true
    }
  }
  
  /** Generate an expression that represents the state a function can depend on
   *  (as determined by examining the functions preconditions).
   */
  def functionDependencies(pre: Expression, etran: ExpressionTranslator): Boogie.Expr = {
    desugar(pre) match {
      case pred@MemberAccess(e, p) if pred.isPredicate =>
        functionDependencies(Access(pred, Full), etran)
      case acc@Access(e, _) =>
        val memberName = if(e.isPredicate) e.predicate.FullName else e.f.FullName;
        heapFragment(new Boogie.MapSelect(etran.Heap, etran.Tr(e.e), memberName))
      case Implies(e0,e1) =>
        heapFragment(Boogie.Ite(etran.Tr(e0), functionDependencies(e1, etran), emptyPartialHeap))
      case And(e0,e1) =>
        combine(functionDependencies(e0, etran), functionDependencies(e1, etran))
      case IfThenElse(con, then, els) =>
        heapFragment(Boogie.Ite(etran.Tr(con), functionDependencies(then, etran), functionDependencies(els, etran)))
      case Unfolding(_, _) =>
        emptyPartialHeap // the predicate of the unfolding expression needs to have been mentioned already (framing check), so we can safely ignore it now
      case p: PermissionExpr => throw new InternalErrorException("unexpected permission expression")
      case e =>
        e visitOpt {_ match {
            case Unfolding(_, _) => false
            case _ : PermissionExpr => throw new InternalErrorException("unexpected permission expression")
            case _ => true }
        }
        emptyPartialHeap
    }
  }

  /** Generate the boolean condition that needs to be true in order to assume
   *  that a function with precondition pre has the same value in two different
   *  states. Essentially, everything that the function can depend on must be
   *  equal.
   *
   *  - conservative for Implies and IfThenElse
   *  - returns an expression of Boogie type bool
   */
  def functionDependenciesEqual(pre: Expression, etran1: ExpressionTranslator, etran2: ExpressionTranslator): Boogie.Expr = {
    desugar(pre) match {
      case pred@MemberAccess(e, p) if pred.isPredicate =>
        functionDependenciesEqual(Access(pred, Full), etran1, etran2)
      case Access(e, _) =>
        val memberName = if(e.isPredicate) e.predicate.FullName else e.f.FullName;
        etran1.Heap.select(etran1.Tr(e.e), memberName) ==@ etran2.Heap.select(etran2.Tr(e.e), memberName)
      case AccessSeq(s, Some(e), _) =>
        val name = if(e.isPredicate) e.predicate.FullName else e.f.FullName;
        val (iV, i) = Boogie.NewBVar("i", tint, true)
        (SeqLength(etran1.Tr(s)) ==@ SeqLength(etran2.Tr(s))) &&
        ((((0 <= i) && (i < SeqLength(etran1.Tr(s)))) ==>
          (etran1.Heap.select(SeqIndex(etran1.Tr(s), i), name) ==@ etran2.Heap.select(SeqIndex(etran2.Tr(s), i), name))).forall(iV))
      case Implies(e0,e1) =>
        Boogie.Ite(etran1.Tr(e0) || etran2.Tr(e0), functionDependenciesEqual(e1, etran1, etran2), true)
      case And(e0,e1) =>
        functionDependenciesEqual(e0, etran1, etran2) && functionDependenciesEqual(e1, etran1, etran2)
      case IfThenElse(con, then, els) =>
        functionDependenciesEqual(then, etran1, etran2) && functionDependenciesEqual(els, etran1, etran2)
      case Unfolding(_, _) =>
        Boogie.BoolLiteral(true) // the predicate of the unfolding expression needs to have been mentioned already (framing check), so we can safely ignore it now
      case _: PermissionExpr => throw new InternalErrorException("unexpected permission expression")
      case e =>
        e visitOpt {_ match {
            case Unfolding(_, _) => false
            case _ : PermissionExpr => throw new InternalErrorException("unexpected permission expression")
            case _ => true }
        }
        Boogie.BoolLiteral(true)
    }
  }
  
  def Preconditions(spec: List[Specification]): List[Expression] = {
    val result = spec flatMap ( s => s match {
      case Precondition(e) => List(e)
      case _ => Nil });
    if(Chalice.autoMagic) {
      automagic(result.foldLeft(BoolLiteral(true): Expression)({ (a, b) => And(a, b)}), Nil)._1 ::: result
    } else {
      result
    }
  }
  def Postconditions(spec: List[Specification]): List[Expression] = {
    val result = spec flatMap ( s => s match {
      case Postcondition(e) => List(e)
      case _ => Nil })
    if(Chalice.autoMagic) {
      automagic(result.foldLeft(BoolLiteral(true): Expression)({ (a, b) => And(a, b)}), Nil)._1 ::: result
    } else {
      result
    }
  }

  def automagic(expr: Expression, handled: List[Expression]): (/*assumptions*/ List[Expression], /*newHandled*/List[Expression]) = {
    def isHandled(e: Expression) = handled exists { ex => ex.equals(e) }
    expr match {
      case ma@MemberAccess(obj, f) =>
        val (assumptions, handled1) = automagic(obj, handled);
        if(isHandled(ma)) {
          (assumptions, handled1)
        } else {
          if(ma.isPredicate){
            // assumption: obj!=null
            (assumptions ::: Neq(obj, NullLiteral()) :: Nil, handled1 ::: List(ma))
          } else {
            // assumption: obj!=null && acc(obj, f)
            (assumptions ::: Neq(obj, NullLiteral()) :: Access(ma, Full) :: Nil, handled1 ::: List(ma))
          }
        }
      case Access(ma@MemberAccess(obj, f), perm) =>
        val (assumptions, handled1) = automagic(obj, handled ::: List(ma));
        perm match {
          case Full | Epsilon | Star | MethodEpsilon => (assumptions, handled1);
          case Frac(fraction) => val result = automagic(fraction, handled1); (assumptions ::: result._1, result._2)
          case Epsilons(epsilon) => val result = automagic(epsilon, handled1); (assumptions ::: result._1, result._2)
          case ChannelEpsilon(None) | PredicateEpsilon(None) | MonitorEpsilon(None) => (assumptions, handled1)
          case ChannelEpsilon(Some(e)) => val result = automagic(e, handled1); (assumptions ::: result._1, result._2)
          case PredicateEpsilon(Some(e)) => val result = automagic(e, handled1); (assumptions ::: result._1, result._2)
          case MonitorEpsilon(Some(e)) => val result = automagic(e, handled1); (assumptions ::: result._1, result._2)
          case ForkEpsilon(e) => val result = automagic(e, handled1); (assumptions ::: result._1, result._2)
          case IntPermTimes(e0, e1) =>
            val (assumptions1, handled2) = automagic(e0, handled1);
            val result = automagic(e1, handled2); 
            (assumptions ::: assumptions1 ::: result._1, result._2)
          case PermTimes(e0, e1) =>
            val (assumptions1, handled2) = automagic(e0, handled1);
            val result = automagic(e1, handled2); 
            (assumptions ::: assumptions1 ::: result._1, result._2)
          case PermPlus(e0, e1) =>
            val (assumptions1, handled2) = automagic(e0, handled1);
            val result = automagic(e1, handled2); 
            (assumptions ::: assumptions1 ::: result._1, result._2)
          case PermMinus(e0, e1) =>
            val (assumptions1, handled2) = automagic(e0, handled1);
            val result = automagic(e1, handled2); 
            (assumptions ::: assumptions1 ::: result._1, result._2)
        }
      case AccessAll(obj, perm) => 
        automagic(obj, handled)
      case Holds(e) =>
        automagic(e, handled)
      case RdHolds(e) =>
        automagic(e, handled)
      case a: Assigned =>
       (Nil, handled)
      case Old(e) =>
       (Nil, handled) // ??
      case IfThenElse(con, then, els) =>
        val (assumptions, handled1) = automagic(con, handled);
        val (assumptions2, handled2) = automagic(then, handled1);
        val result = automagic(els, handled2); 
        (assumptions ::: assumptions2 ::: result._1, result._2)
      case Not(e) =>
        automagic(e, handled)
      case func@FunctionApplication(obj, id, args) =>
        var assumption = Nil: List[Expression];
        var newHandled = handled;
        for(a <- obj :: args) {
          val (ass, hd) = automagic(a, handled);
          assumption = assumption ::: ass;
          newHandled = hd;
        }
        (assumption, newHandled)
      case uf@Unfolding(_, e) =>
        (Nil, handled)
      case bin: BinaryExpr =>
        val (assumptions, handled1) = automagic(bin.E0, handled);
        val result = automagic(bin.E1, handled1); 
        (assumptions ::: result._1, result._2)
      case EmptySeq(t) =>
        (Nil, handled)
      case ExplicitSeq(es) =>
        var assumption = Nil: List[Expression];
        var newHandled = handled;
        for(a <- es) {
          val (ass, hd) = automagic(a, handled);
          assumption = assumption ::: ass;
          newHandled = hd;
        }
        (assumption, newHandled)
      case Range(min, max) =>
        val (assumptions, handled1) = automagic(min, handled);
        val result = automagic(max, handled1); 
        (assumptions ::: result._1, result._2)
      case Length(e) =>
        automagic(e, handled)
      case Eval(h, e) =>
        (Nil, handled)
      case _ => (Nil, handled)
    }
  }

  def DefinitionOf(predicate: Predicate): Expression = {
    if(Chalice.autoMagic) {
      And(automagic(predicate.definition, Nil)._1.foldLeft(BoolLiteral(true): Expression)({ (a, b) => And(a, b)}), predicate.definition)
    } else {
      predicate.definition
    }
  }

  def LockChanges(spec: List[Specification]): List[Expression] = {
    spec flatMap ( s => s match {
      case LockChange(ee) => ee
      case _ => Nil })
  }

  def SubstRd(e: Expression): Expression = e match {
    case Access(e, p: Permission) if p != Star =>
      //val r = Access(e,MonitorEpsilon(None)); r.pos = e.pos; r.typ = BoolClass; r
      val r = Access(e,Epsilons(IntLiteral(1))); r.pos = e.pos; r.typ = BoolClass; r
    case Implies(e0,e1) =>
      val r = Implies(e0, SubstRd(e1)); r.pos = e.pos; r.typ = BoolClass; r
    case And(e0,e1) =>
      val r = And(SubstRd(e0), SubstRd(e1)); r.pos = e.pos; r.typ = BoolClass; r
    case _ => e
  }

  def UnfoldPredicatesWithReceiverThis(expr: Expression): Expression = {
    val func = (e:Expression) =>
      e match {
        case pred@MemberAccess(o, f) if pred.isPredicate && o.isInstanceOf[ThisExpr] =>
          Some(SubstThis(DefinitionOf(pred.predicate), o))
        case Access(pred@MemberAccess(o, f), p) if pred.isPredicate && o.isInstanceOf[ThisExpr] =>
          val definition = scaleExpressionByPermission(SubstThis(DefinitionOf(pred.predicate), o), p, e.pos)
          Some(definition)
        case func@FunctionApplication(obj: ThisExpr, name, args) if 2<=Chalice.defaults && func.f.definition.isDefined =>
          Some(SubstVars(func.f.definition.get, obj, func.f.ins, args))
        case _ => None
      }
    AST.transform(expr, func)
  }

  // needed to do a _simultaneous_ substitution!
  def SubstVars(expr: Expression, x:Expression, vs:List[Variable], es:List[Expression]): Expression =
    SubstVars(expr, Some(x), Map() ++ (vs zip es));
  def SubstVars(expr: Expression, vs:List[Variable], es:List[Expression]): Expression =
    SubstVars(expr, None, Map() ++ (vs zip es));
  def SubstVars(expr: Expression, t: Option[Expression], vs: Map[Variable, Expression]): Expression = expr.transform {
    case _: ThisExpr if t.isDefined => t
    case e: VariableExpr =>
      if (vs.contains(e.v)) Some(vs(e.v)) else None;
    case q: Quantification =>
      q.variables foreach { (v) => if (vs.contains(v)) throw new InternalErrorException("cannot substitute a variable bound in the quantifier")}
      None;
    case _ => None;
  }

  def SubstThis(expr: Expression, x: Expression): Expression = expr.transform {
    case _: ThisExpr => Some(x)
    case _ => None
  }

  def SubstResult(expr: Expression, x: Expression): Expression = expr.transform {
    case _: Result => Some(x)
    case _ => None
  }

  // De-sugar expression (idempotent)
  // * unroll wildcard pattern (for objects) in permission expression
  // * convert sequence quantification into type quantification
  // * perform simple permission expression optimizations (e.g. Frac(1)+Frac(1) = Frac(1+1) or Frac(100) = Full)
  // * simplify quantification over empty sequences
  def desugar(expr: Expression): Expression = expr transform {
    _ match {
      case Frac(IntLiteral(100)) => Some(Full)
      case PermTimes(Full, r) => Some(r)
      case PermTimes(l, Full) => Some(l)
      case PermPlus(lhs, rhs) =>
        val ll = desugar(lhs)
        val rr = desugar(rhs)
        (ll, rr) match {
          case (Frac(l), Frac(r)) => Some(Frac(Plus(l,r)))
          case _ => Some(PermPlus(ll.asInstanceOf[Permission], rr.asInstanceOf[Permission]))
        }
      case PermMinus(lhs, rhs) =>
        val ll = desugar(lhs)
        val rr = desugar(rhs)
        (ll, rr) match {
          case (Frac(l), Frac(r)) => Some(Frac(Minus(l,r)))
          case _ => Some(PermMinus(ll.asInstanceOf[Permission], rr.asInstanceOf[Permission]))
        }
      case PermTimes(lhs, rhs) =>
        val ll = desugar(lhs)
        val rr = desugar(rhs)
        (ll, rr) match {
          case (Frac(l), Frac(r)) => Some(Frac(Times(l,r)))
          case _ => Some(PermTimes(ll.asInstanceOf[Permission], rr.asInstanceOf[Permission]))
        }
      case AccessAll(obj, perm) =>
        Some(obj.typ.Fields.map({f =>
          val ma = MemberAccess(desugar(obj), f.id);
          ma.f = f; ma.pos = expr.pos; ma.typ = f.typ.typ;
          val acc = Access(ma, perm);
          acc.pos = expr.pos; acc.typ = acc.typ; acc
        }).foldLeft(BoolLiteral(true): Expression){(e1, e2) =>
          val and = And(e1, e2);
          and.pos = expr.pos; and.typ = BoolClass; and
        })
      case AccessSeq(s, None, perm) =>
        Some(s.typ.parameters(0).Fields.map({(f) =>
          val ma = MemberAccess(At(desugar(s), IntLiteral(0)), f.id);
          ma.f = f; ma.pos = expr.pos; ma.typ = f.typ.typ;
          val acc = AccessSeq(s, Some(ma), perm);
          acc.pos = expr.pos; acc.typ = acc.typ; acc
        }).foldLeft(BoolLiteral(true): Expression){(e1, e2) =>
          val and = And(e1, e2);
          and.pos = expr.pos; and.typ = BoolClass; and
        })
      case qe @ SeqQuantification(q, is, Range(min, max), e) =>
        val dmin = desugar(min);
        val dmax = desugar(max);
        val dis = qe.variables;
        val disx = dis map {v => new VariableExpr(v)};
        val de = desugar(e);

        val assumption = disx map {x =>
          And(AtMost(dmin, x), Less(x, dmax))
        } reduceLeft {(e0, e1) =>
          And(e0, e1)
        };
        assumption transform {e => e.pos = expr.pos; None};
        val body = q match {
          case Forall => Implies(assumption, de);
          case Exists => And(assumption, de);
        }
        body.pos = expr.pos;
        val result = TypeQuantification(q, is, new Type(IntClass), body, (dmin,dmax));
        result.variables = dis;
        Some(result);
      case qe @ SeqQuantification(Forall, is, ExplicitSeq(List()), e) => Some(BoolLiteral(true))
      case qe @ SeqQuantification(Exists, is, ExplicitSeq(List()), e) => Some(BoolLiteral(false))
      case qe @ SeqQuantification(Forall, is, EmptySeq(_), e) => Some(BoolLiteral(true))
      case qe @ SeqQuantification(Exists, is, EmptySeq(_), e) => Some(BoolLiteral(false))
      case qe @ SeqQuantification(q, is, seq, e) =>
        val dseq = desugar(seq);
        val min = IntLiteral(0);
        val max = Length(dseq);
        val dis = qe.variables map {v => new Variable(v.UniqueName, new Type(IntClass))};
        val disx = dis map {v => new VariableExpr(v)};
        val de = SubstVars(desugar(e), qe.variables, disx map {x => At(dseq, x)});

        val assumption = disx map {x =>
          And(AtMost(min, x), Less(x, max))
        } reduceLeft {(e0, e1) =>
          And(e0, e1)
        };
        assumption transform {e => e.pos = expr.pos; None};
        val body = q match {
          case Forall => Implies(assumption, de);
          case Exists => And(assumption, de);
        }
        body.pos = expr.pos;
        val result = new TypeQuantification(q, is, new Type(IntClass), body);
        result.variables = dis;
        Some(result);
      case _ => None;
    }
  }

  // tags statements to be preserved
  val keepTag = Boogie.Tag("keep")
  
  // Assume the only composite statement in Boogie is If
  def tag(l: List[Stmt], t: Boogie.Tag):List[Stmt] =
    for (s <- l) yield {
      s.tags = t :: s.tags;
      s match {
        case Boogie.If(_, thn, els) => tag(thn, t); tag(els, t);
        case _ => 
      }
      s
    }
  // Assume the only composite statement in Boogie is If
  def assert2assume(l: List[Stmt], b: Boolean):List[Stmt] =
    if (Chalice.noFreeAssume && b) l else
      l flatMap {
        case Boogie.If(guard, thn, els) => Boogie.If(guard, assert2assume(thn), assert2assume(els))
        case ba @ Boogie.Assert(e) =>
          if (ba.tags contains keepTag) ba else Comment(" assert " + ba.pos + ": " + ba.message) :: Boogie.Assume(e)
        case s => s
      }
  def assert2assume(l: List[Stmt]):List[Stmt] = assert2assume(l, false)
}

}
