//-----------------------------------------------------------------------------
//
// Copyright (C) Microsoft Corporation.  All Rights Reserved.
//
//-----------------------------------------------------------------------------
using System;
using System.Diagnostics.Contracts;
using System.Collections.Generic;
using Microsoft.Boogie;
using Microsoft.Boogie.VCExprAST;
using VC;
using System.Collections;
using System.IO;
using Microsoft.Boogie.GraphUtil;

namespace Microsoft.Boogie.Houdini {

  class ReadOnlyDictionary<K, V> {
    private Dictionary<K, V> dictionary;
    public ReadOnlyDictionary(Dictionary<K, V> dictionary) {
      this.dictionary = dictionary;
    }

    public Dictionary<K, V>.KeyCollection Keys {
      get { return this.dictionary.Keys; }
    }

    public bool TryGetValue(K k, out V v) {
      return this.dictionary.TryGetValue(k, out v);
    }

    public bool ContainsKey(K k) {
      return this.dictionary.ContainsKey(k);
    }
  }

  public abstract class HoudiniObserver {
    public virtual void UpdateStart(Program program, int numConstants) { }
    public virtual void UpdateIteration() { }
    public virtual void UpdateImplementation(Implementation implementation) { }
    public virtual void UpdateAssignment(Dictionary<Variable, bool> assignment) { }
    public virtual void UpdateOutcome(ProverInterface.Outcome outcome) { }
    public virtual void UpdateEnqueue(Implementation implementation) { }
    public virtual void UpdateDequeue() { }
    public virtual void UpdateConstant(string constantName) { }
    public virtual void UpdateEnd(bool isNormalEnd) { }
    public virtual void UpdateFlushStart() { }
    public virtual void UpdateFlushFinish() { }
    public virtual void SeeException(string msg) { }
  }

  public class IterationTimer<K> {
    private Dictionary<K, List<double>> times;

    public IterationTimer() {
      times = new Dictionary<K, List<double>>();
    }

    public void AddTime(K key, double timeMS) {
      List<double> oldList;
      times.TryGetValue(key, out oldList);
      if (oldList == null) {
        oldList = new List<double>();
      }
      else {
        times.Remove(key);
      }
      oldList.Add(timeMS);
      times.Add(key, oldList);
    }

    public void PrintTimes(TextWriter wr) {
      wr.WriteLine("Total procedures: {0}", times.Count);
      double total = 0;
      int totalIters = 0;
      foreach (KeyValuePair<K, List<double>> kv in times) {
        int curIter = 0;
        wr.WriteLine("Times for {0}:", kv.Key);
        foreach (double v in kv.Value) {
          wr.WriteLine("  ({0})\t{1}ms", curIter, v);
          total += v;
          curIter++;
        }
        totalIters += curIter;
      }
      total = total / 1000.0;
      wr.WriteLine("Total time: {0} (s)", total);
      wr.WriteLine("Avg: {0} (s/iter)", total / totalIters);
    }
  }

  public class HoudiniTimer : HoudiniObserver {
    private DateTime startT;
    private Implementation curImp;
    private IterationTimer<string> times;
    private TextWriter wr;

    public HoudiniTimer(TextWriter wr) {
      this.wr = wr;
      times = new IterationTimer<string>();
    }
    public override void UpdateIteration() {
      startT = DateTime.UtcNow;
    }
    public override void UpdateImplementation(Implementation implementation) {
      curImp = implementation;
    }
    public override void UpdateOutcome(ProverInterface.Outcome o) {
      Contract.Assert(curImp != null);
      DateTime endT = DateTime.UtcNow;
      times.AddTime(curImp.Name, (endT - startT).TotalMilliseconds); // assuming names are unique
    }
    public void PrintTimes() {
      wr.WriteLine("-----------------------------------------");
      wr.WriteLine("Times for each iteration for each procedure");
      wr.WriteLine("-----------------------------------------");
      times.PrintTimes(wr);
    }
  }

  public class HoudiniTextReporter : HoudiniObserver {
    private TextWriter wr;
    private int currentIteration = -1;

    public HoudiniTextReporter(TextWriter wr) {
      this.wr = wr;
    }
    public override void UpdateStart(Program program, int numConstants) {
      wr.WriteLine("Houdini started:" + program.ToString() + " #constants: " + numConstants.ToString());
      currentIteration = -1;
      wr.Flush();
    }
    public override void UpdateIteration() {
      currentIteration++;
      wr.WriteLine("---------------------------------------");
      wr.WriteLine("Houdini iteration #" + currentIteration);
      wr.Flush();
    }
    public override void UpdateImplementation(Implementation implementation) {
      wr.WriteLine("implementation under analysis :" + implementation.Name);
      wr.Flush();
    }
    public override void UpdateAssignment(Dictionary<Variable, bool> assignment) {
      bool firstTime = true;
      wr.Write("assignment under analysis : axiom (");
      foreach (KeyValuePair<Variable, bool> kv in assignment) {
        if (!firstTime) wr.Write(" && "); else firstTime = false;
        string valString; // ugliness to get it lower cased
        if (kv.Value) valString = "true"; else valString = "false";
        wr.Write(kv.Key + " == " + valString);
      }
      wr.WriteLine(");");
      wr.Flush();
    }
    public override void UpdateOutcome(ProverInterface.Outcome outcome) {
      wr.WriteLine("analysis outcome :" + outcome);
      wr.Flush();
    }
    public override void UpdateEnqueue(Implementation implementation) {
      wr.WriteLine("worklist enqueue :" + implementation.Name);
      wr.Flush();
    }
    public override void UpdateDequeue() {
      wr.WriteLine("worklist dequeue");
      wr.Flush();
    }
    public override void UpdateConstant(string constantName) {
      wr.WriteLine("constant disabled : " + constantName);
      wr.Flush();
    }
    public override void UpdateEnd(bool isNormalEnd) {
      wr.WriteLine("Houdini ended: " + (isNormalEnd ? "Normal" : "Abnormal"));
      wr.WriteLine("Number of iterations: " + (this.currentIteration + 1));
      wr.Flush();
    }
    public override void UpdateFlushStart() {
      wr.WriteLine("***************************************");
      wr.WriteLine("Flushing remaining implementations");
      wr.Flush();
    }
    public override void UpdateFlushFinish() {
      wr.WriteLine("***************************************");
      wr.WriteLine("Flushing finished");
      wr.Flush();
    }
    public override void SeeException(string msg) {
      wr.WriteLine("Caught exception: " + msg);
      wr.Flush();
    }

  }

  public abstract class ObservableHoudini {
    private List<HoudiniObserver> observers = new List<HoudiniObserver>();

    public void AddObserver(HoudiniObserver observer) {
      if (!observers.Contains(observer))
        observers.Add(observer);
    }
    private delegate void NotifyDelegate(HoudiniObserver observer);

    private void Notify(NotifyDelegate notifyDelegate) {
      foreach (HoudiniObserver observer in observers) {
        notifyDelegate(observer);
      }
    }
    protected void NotifyStart(Program program, int numConstants) {
      NotifyDelegate notifyDelegate = (NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateStart(program, numConstants); };
      Notify(notifyDelegate);
    }
    protected void NotifyIteration() {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateIteration(); });
    }
    protected void NotifyImplementation(Implementation implementation) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateImplementation(implementation); });
    }
    protected void NotifyAssignment(Dictionary<Variable, bool> assignment) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateAssignment(assignment); });
    }
    protected void NotifyOutcome(ProverInterface.Outcome outcome) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateOutcome(outcome); });
    }
    protected void NotifyEnqueue(Implementation implementation) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateEnqueue(implementation); });
    }
    protected void NotifyDequeue() {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateDequeue(); });
    }
    protected void NotifyConstant(string constantName) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateConstant(constantName); });
    }
    protected void NotifyEnd(bool isNormalEnd) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateEnd(isNormalEnd); });
    }
    protected void NotifyFlushStart() {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateFlushStart(); });
    }
    protected void NotifyFlushFinish() {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.UpdateFlushFinish(); });
    }

    protected void NotifyException(string msg) {
      Notify((NotifyDelegate)delegate(HoudiniObserver r) { r.SeeException(msg); });
    }
  }

  public class InlineRequiresVisitor : StandardVisitor {
    public override CmdSeq VisitCmdSeq(CmdSeq cmdSeq) {
      Contract.Requires(cmdSeq != null);
      Contract.Ensures(Contract.Result<CmdSeq>() != null);
      CmdSeq newCmdSeq = new CmdSeq();
      for (int i = 0, n = cmdSeq.Length; i < n; i++) {
        Cmd cmd = cmdSeq[i];
        CallCmd callCmd = cmd as CallCmd;
        if (callCmd != null) {
          newCmdSeq.AddRange(InlineRequiresForCallCmd(callCmd));
        }
        else {
          newCmdSeq.Add((Cmd)this.Visit(cmd));
        }
      }
      return newCmdSeq;
    }
    private CmdSeq InlineRequiresForCallCmd(CallCmd node) {
      Hashtable substMap = new Hashtable();
      for (int i = 0; i < node.Proc.InParams.Length; i++) {
        substMap.Add(node.Proc.InParams[i], node.Ins[i]);
      }
      Substitution substitution = Substituter.SubstitutionFromHashtable(substMap);
      CmdSeq cmds = new CmdSeq();
      foreach (Requires requires in node.Proc.Requires) {
        if (requires.Free) continue;
        Requires requiresCopy = new Requires(false, Substituter.Apply(substitution, requires.Condition));
        cmds.Add(new AssertRequiresCmd(node, requiresCopy));
      }
      cmds.Add(node);
      return cmds;
    }
  }

  public class FreeRequiresVisitor : StandardVisitor {
    public override Requires VisitRequires(Requires requires) {
      if (requires.Free)
        return base.VisitRequires(requires);
      else 
        return new Requires(true, requires.Condition);
    }

    public override Cmd VisitAssertRequiresCmd(AssertRequiresCmd node) {
      Contract.Requires(node != null);
      Contract.Ensures(Contract.Result<Cmd>() != null);
      node.Requires = base.VisitRequires(node.Requires);
      node.Expr = this.VisitExpr(node.Expr);
      return node;
    }
  }

  public class InlineEnsuresVisitor : StandardVisitor {
    public override Ensures VisitEnsures(Ensures ensures) {
      ensures.Attributes = new QKeyValue(Token.NoToken, "assume", new List<object>(), ensures.Attributes);
      return base.VisitEnsures(ensures);
    }
  }

  public class Houdini : ObservableHoudini {
    private Program program;
    private HashSet<Variable> houdiniConstants;
    private ReadOnlyDictionary<Implementation, HoudiniSession> houdiniSessions;
    private VCGen vcgen;
    private ProverInterface proverInterface;
    private Graph<Implementation> callGraph;
    private HashSet<Implementation> vcgenFailures;
    private HoudiniState currentHoudiniState;

    public HoudiniState CurrentHoudiniState { get { return currentHoudiniState; } }

    public Houdini(Program program) {
      this.program = program;

      if (CommandLineOptions.Clo.Trace)
        Console.WriteLine("Collecting existential constants...");
      this.houdiniConstants = CollectExistentialConstants();
      
      if (CommandLineOptions.Clo.Trace)
        Console.WriteLine("Building call graph...");
      this.callGraph = BuildCallGraph();
      if (CommandLineOptions.Clo.Trace)
        Console.WriteLine("Number of implementations = {0}", callGraph.Nodes.Count);

      Inline();
      
      this.vcgen = new VCGen(program, CommandLineOptions.Clo.SimplifyLogFilePath, CommandLineOptions.Clo.SimplifyLogFileAppend);
      this.proverInterface = ProverInterface.CreateProver(program, CommandLineOptions.Clo.SimplifyLogFilePath, CommandLineOptions.Clo.SimplifyLogFileAppend, CommandLineOptions.Clo.ProverKillTime);

      vcgenFailures = new HashSet<Implementation>();
      Dictionary<Implementation, HoudiniSession> houdiniSessions = new Dictionary<Implementation, HoudiniSession>();
      if (CommandLineOptions.Clo.Trace)
        Console.WriteLine("Beginning VC generation for Houdini...");
      foreach (Implementation impl in callGraph.Nodes) {
        try {
          if (CommandLineOptions.Clo.Trace)
            Console.WriteLine("Generating VC for {0}", impl.Name);
          HoudiniSession session = new HoudiniSession(this, vcgen, proverInterface, program, impl);
          houdiniSessions.Add(impl, session);
        }
        catch (VCGenException) {
          if (CommandLineOptions.Clo.Trace)
            Console.WriteLine("VC generation failed");
          vcgenFailures.Add(impl);
        }
      }
      this.houdiniSessions = new ReadOnlyDictionary<Implementation, HoudiniSession>(houdiniSessions);
      currentHoudiniState = new HoudiniState(BuildWorkList(program), BuildAssignment(houdiniConstants));
    }

    private void Inline() {
      if (CommandLineOptions.Clo.InlineDepth < 0)
        return;

      foreach (Implementation impl in callGraph.Nodes) {
        InlineRequiresVisitor inlineRequiresVisitor = new InlineRequiresVisitor();
        inlineRequiresVisitor.Visit(impl);
      }

      foreach (Implementation impl in callGraph.Nodes) {
        FreeRequiresVisitor freeRequiresVisitor = new FreeRequiresVisitor();
        freeRequiresVisitor.Visit(impl);
      }

      foreach (Implementation impl in callGraph.Nodes) {
        InlineEnsuresVisitor inlineEnsuresVisitor = new InlineEnsuresVisitor();
        inlineEnsuresVisitor.Visit(impl);
      }

      foreach (Implementation impl in callGraph.Nodes) {
        impl.OriginalBlocks = impl.Blocks;
        impl.OriginalLocVars = impl.LocVars;
      }
      foreach (Implementation impl in callGraph.Nodes) {
        CommandLineOptions.Inlining savedOption = CommandLineOptions.Clo.ProcedureInlining;
        CommandLineOptions.Clo.ProcedureInlining = CommandLineOptions.Inlining.Spec;
        Inliner.ProcessImplementationForHoudini(program, impl);
        CommandLineOptions.Clo.ProcedureInlining = savedOption;
      }
      foreach (Implementation impl in callGraph.Nodes) {
        impl.OriginalBlocks = null;
        impl.OriginalLocVars = null;
      }

      Graph<Implementation> oldCallGraph = callGraph;
      callGraph = new Graph<Implementation>();
      foreach (Implementation impl in oldCallGraph.Nodes) {
        callGraph.AddSource(impl);
      }
      foreach (Tuple<Implementation, Implementation> edge in oldCallGraph.Edges) {
        callGraph.AddEdge(edge.Item1, edge.Item2);
      }
      int count = CommandLineOptions.Clo.InlineDepth;
      while (count > 0) {
        foreach (Implementation impl in oldCallGraph.Nodes) {
          List<Implementation> newNodes = new List<Implementation>();
          foreach (Implementation succ in callGraph.Successors(impl)) {
            newNodes.AddRange(oldCallGraph.Successors(succ));
          }
          foreach (Implementation newNode in newNodes) {
            callGraph.AddEdge(impl, newNode);
          }
        }
        count--;
      }
    }

    private HashSet<Variable> CollectExistentialConstants() {
      HashSet<Variable> existentialConstants = new HashSet<Variable>();
      foreach (Declaration decl in program.TopLevelDeclarations) {
        Constant constant = decl as Constant;
        if (constant != null) {
          bool result = false;
          if (constant.CheckBooleanAttribute("existential", ref result)) {
            if (result == true)
              existentialConstants.Add(constant);
          }
        }
      }
      return existentialConstants;
    }

    private Graph<Implementation> BuildCallGraph() {
      Graph<Implementation> callGraph = new Graph<Implementation>();
      Dictionary<Procedure, HashSet<Implementation>> procToImpls = new Dictionary<Procedure, HashSet<Implementation>>();
      foreach (Declaration decl in program.TopLevelDeclarations) {
        Procedure proc = decl as Procedure;
        if (proc == null) continue;
        procToImpls[proc] = new HashSet<Implementation>();
      } 
      foreach (Declaration decl in program.TopLevelDeclarations) {
        Implementation impl = decl as Implementation;
        if (impl == null || impl.SkipVerification) continue;
        callGraph.AddSource(impl);
        procToImpls[impl.Proc].Add(impl);
      }
      foreach (Declaration decl in program.TopLevelDeclarations) {
        Implementation impl = decl as Implementation;
        if (impl == null || impl.SkipVerification) continue;
        foreach (Block b in impl.Blocks) {
          foreach (Cmd c in b.Cmds) {
            CallCmd cc = c as CallCmd;
            if (cc == null) continue;
            foreach (Implementation callee in procToImpls[cc.Proc]) {
              callGraph.AddEdge(impl, callee);
            }
          }
        }
      }
      return callGraph;
    }
    
    private WorkQueue BuildWorkList(Program program) {
      // adding implementations to the workqueue from the bottom of the call graph upwards
      WorkQueue queue = new WorkQueue();
      StronglyConnectedComponents<Implementation> sccs =
        new StronglyConnectedComponents<Implementation>(callGraph.Nodes,
          new Adjacency<Implementation>(callGraph.Predecessors),
          new Adjacency<Implementation>(callGraph.Successors));
      sccs.Compute();
      foreach (SCC<Implementation> scc in sccs) {
        foreach (Implementation impl in scc) {
          if (vcgenFailures.Contains(impl)) continue;
          queue.Enqueue(impl);
        }
      }
      return queue;
      
      /*
      Queue<Implementation> queue = new Queue<Implementation>();
      foreach (Declaration decl in program.TopLevelDeclarations) {
        Implementation impl = decl as Implementation;
        if (impl == null || impl.SkipVerification) continue;
        queue.Enqueue(impl);
      }
      return queue;
       */
    }

    public bool MatchCandidate(Expr boogieExpr, out Variable candidateConstant) {
      candidateConstant = null;
      NAryExpr e = boogieExpr as NAryExpr;
      if (e != null && e.Fun is BinaryOperator && ((BinaryOperator)e.Fun).Op == BinaryOperator.Opcode.Imp) {
        Expr antecedent = e.Args[0];
        Expr consequent = e.Args[1];

        IdentifierExpr id = antecedent as IdentifierExpr;
        if (id != null && id.Decl is Constant && houdiniConstants.Contains((Constant)id.Decl)) {
          candidateConstant = id.Decl;
          return true;
        }

        if (MatchCandidate(consequent, out candidateConstant))
          return true;
      }
      return false;
    }

    private Dictionary<Variable, bool> BuildAssignment(HashSet<Variable> constants) {
      Dictionary<Variable, bool> initial = new Dictionary<Variable, bool>();
      foreach (var constant in constants)
        initial.Add(constant, true);
      return initial;
    }

    private bool IsOutcomeNotHoudini(ProverInterface.Outcome outcome, List<Counterexample> errors) {
      switch (outcome) {
        case ProverInterface.Outcome.Valid:
          return false;
        case ProverInterface.Outcome.Invalid:
          Contract.Assume(errors != null);
          foreach (Counterexample error in errors) {
            if (ExtractRefutedAnnotation(error) == null)
              return true;
          }
          return false;
        default:
          return true;
      }
    }

    // Record most current non-candidate errors found
    // Return true if there was at least one non-candidate error
    private bool UpdateHoudiniOutcome(HoudiniOutcome houdiniOutcome,
                                      Implementation implementation,
                                      ProverInterface.Outcome outcome,
                                      List<Counterexample> errors) {
      string implName = implementation.Name;
      houdiniOutcome.implementationOutcomes.Remove(implName);
      List<Counterexample> nonCandidateErrors = new List<Counterexample>();

      if (outcome == ProverInterface.Outcome.Invalid) {
          foreach (Counterexample error in errors) {
            if (ExtractRefutedAnnotation(error) == null)
              nonCandidateErrors.Add(error);
          }
      }
      houdiniOutcome.implementationOutcomes.Add(implName, new VCGenOutcome(outcome, nonCandidateErrors));
      return nonCandidateErrors.Count > 0;
    }

    private void FlushWorkList() {
      this.NotifyFlushStart();
      while (currentHoudiniState.WorkQueue.Count > 0) {
        this.NotifyIteration();

        currentHoudiniState.Implementation = currentHoudiniState.WorkQueue.Peek();
        this.NotifyImplementation(currentHoudiniState.Implementation);

        HoudiniSession session;
        houdiniSessions.TryGetValue(currentHoudiniState.Implementation, out session);
        List<Counterexample> errors;
        ProverInterface.Outcome outcome = TryCatchVerify(session, out errors);
        UpdateHoudiniOutcome(currentHoudiniState.Outcome, currentHoudiniState.Implementation, outcome, errors);
        this.NotifyOutcome(outcome);

        currentHoudiniState.WorkQueue.Dequeue();
        this.NotifyDequeue();
      }
      this.NotifyFlushFinish();
    }

    private void UpdateAssignment(RefutedAnnotation refAnnot) {
      if (CommandLineOptions.Clo.Trace) {
        Console.WriteLine("Removing " + refAnnot.Constant);
        using (var cexWriter = new System.IO.StreamWriter("houdiniCexTrace.bpl", true))
            cexWriter.WriteLine("Removing " + refAnnot.Constant);
      }
      currentHoudiniState.Assignment.Remove(refAnnot.Constant);
      currentHoudiniState.Assignment.Add(refAnnot.Constant, false);
      this.NotifyConstant(refAnnot.Constant.Name);
    }

    private void AddRelatedToWorkList(RefutedAnnotation refutedAnnotation) {
      Contract.Assume(currentHoudiniState.Implementation != null);
      foreach (Implementation implementation in FindImplementationsToEnqueue(refutedAnnotation, currentHoudiniState.Implementation)) {
        if (!currentHoudiniState.isBlackListed(implementation.Name)) {
          currentHoudiniState.WorkQueue.Enqueue(implementation);
          this.NotifyEnqueue(implementation);
        }
      }
    }

    // Updates the worklist and current assignment
    // @return true if the current function is dequeued
    private bool UpdateAssignmentWorkList(ProverInterface.Outcome outcome,
                                          List<Counterexample> errors) {
      Contract.Assume(currentHoudiniState.Implementation != null);
      bool dequeue = true;

      switch (outcome) {
        case ProverInterface.Outcome.Valid:
          //yeah, dequeue
          break;
        case ProverInterface.Outcome.Invalid:
          Contract.Assume(errors != null);
          foreach (Counterexample error in errors) {
            RefutedAnnotation refutedAnnotation = ExtractRefutedAnnotation(error);
            if (refutedAnnotation != null) { // some candidate annotation removed
              AddRelatedToWorkList(refutedAnnotation);
              UpdateAssignment(refutedAnnotation);
              dequeue = false;
              #region Extra debugging output
              if (CommandLineOptions.Clo.Trace)
              {
                  using (var cexWriter = new System.IO.StreamWriter("houdiniCexTrace.bpl", true))
                  {
                      cexWriter.WriteLine("Counter example for " + refutedAnnotation.Constant);
                      cexWriter.Write(error.ToString());
                      cexWriter.WriteLine();
                      using (var writer = new Microsoft.Boogie.TokenTextWriter(cexWriter))
                          foreach (Microsoft.Boogie.Block blk in error.Trace)
                              blk.Emit(writer, 15);
                      //cexWriter.WriteLine(); 
                  }
              }
              #endregion

            }
          }
          break;
        default:
          currentHoudiniState.addToBlackList(currentHoudiniState.Implementation.Name);
          break;
      }
      
      return dequeue;
    }

    public class WorkQueue {
      private Queue<Implementation> queue;
      private HashSet<Implementation> set;
      public WorkQueue() {
        queue = new Queue<Implementation>();
        set = new HashSet<Implementation>();
      }
      public void Enqueue(Implementation impl) {
        if (set.Contains(impl))
          return;
        queue.Enqueue(impl);
        set.Add(impl);
      }
      public Implementation Dequeue() {
        Implementation impl = queue.Dequeue();
        set.Remove(impl);
        return impl;
      }
      public Implementation Peek() {
        return queue.Peek();
      }
      public int Count {
        get { return queue.Count; }
      }
      public bool Contains(Implementation impl) {
        return set.Contains(impl);
      }
    }

    public class HoudiniState {
      private WorkQueue _workQueue;
      private HashSet<string> blackList;
      private Dictionary<Variable, bool> _assignment;
      private Implementation _implementation;
      private HoudiniOutcome _outcome;

      public HoudiniState(WorkQueue workQueue, Dictionary<Variable, bool> currentAssignment) {
        this._workQueue = workQueue;
        this._assignment = currentAssignment;
        this._implementation = null;
        this._outcome = new HoudiniOutcome();
        this.blackList = new HashSet<string>();
      }

      public WorkQueue WorkQueue {
        get { return this._workQueue; }
      }
      public Dictionary<Variable, bool> Assignment {
        get { return this._assignment; }
      }
      public Implementation Implementation {
        get { return this._implementation; }
        set { this._implementation = value; }
      }
      public HoudiniOutcome Outcome {
        get { return this._outcome; }
      }
      public bool isBlackListed(string funcName) {
        return blackList.Contains(funcName);
      }
      public void addToBlackList(string funcName) {
        blackList.Add(funcName);
      }
    }

    public HoudiniOutcome PerformHoudiniInference() {
      this.NotifyStart(program, houdiniConstants.Count);
      foreach (Implementation impl in vcgenFailures) {
        currentHoudiniState.addToBlackList(impl.Name);
      }

      while (currentHoudiniState.WorkQueue.Count > 0) {
        this.NotifyIteration();

        currentHoudiniState.Implementation = currentHoudiniState.WorkQueue.Peek();
        this.NotifyImplementation(currentHoudiniState.Implementation);

        HoudiniSession session;
        this.houdiniSessions.TryGetValue(currentHoudiniState.Implementation, out session);
        HoudiniVerifyCurrent(session);
      }
      this.NotifyEnd(true);
      Dictionary<string, bool> assignment = new Dictionary<string, bool>();
      foreach (var x in currentHoudiniState.Assignment)
        assignment[x.Key.Name] = x.Value;
      currentHoudiniState.Outcome.assignment = assignment;
      vcgen.Close();
      proverInterface.Close();
      return currentHoudiniState.Outcome;
    }

    private List<Implementation> FindImplementationsToEnqueue(RefutedAnnotation refutedAnnotation, Implementation currentImplementation) {
      HoudiniSession session;
      List<Implementation> implementations = new List<Implementation>();
      switch (refutedAnnotation.Kind) {
        case RefutedAnnotationKind.REQUIRES:
          foreach (Implementation callee in callGraph.Successors(currentImplementation)) {
            houdiniSessions.TryGetValue(callee, out session);
            Contract.Assume(callee.Proc != null);
            if (callee.Proc.Equals(refutedAnnotation.CalleeProc) && session.InUnsatCore(refutedAnnotation.Constant)) 
              implementations.Add(callee);
          }
          break;
        case RefutedAnnotationKind.ENSURES:
          foreach (Implementation caller in callGraph.Predecessors(currentImplementation)) {
            houdiniSessions.TryGetValue(caller, out session);
            if (session.InUnsatCore(refutedAnnotation.Constant))
              implementations.Add(caller);
          }
          break;
        case RefutedAnnotationKind.ASSERT: //the implementation is already in queue
          break;
        default:
          throw new Exception("Unknown Refuted annotation kind:" + refutedAnnotation.Kind);
      }
      return implementations;
    }

    private enum RefutedAnnotationKind { REQUIRES, ENSURES, ASSERT };

    private class RefutedAnnotation {
      private Variable _constant;
      private RefutedAnnotationKind _kind;
      private Procedure _callee;

      private RefutedAnnotation(Variable constant, RefutedAnnotationKind kind, Procedure callee) {
        this._constant = constant;
        this._kind = kind;
        this._callee = callee;
      }
      public RefutedAnnotationKind Kind {
        get { return this._kind; }
      }
      public Variable Constant {
        get { return this._constant; }
      }
      public Procedure CalleeProc {
        get { return this._callee; }
      }
      public static RefutedAnnotation BuildRefutedRequires(Variable constant, Procedure callee) {
        return new RefutedAnnotation(constant, RefutedAnnotationKind.REQUIRES, callee);
      }
      public static RefutedAnnotation BuildRefutedEnsures(Variable constant) {
        return new RefutedAnnotation(constant, RefutedAnnotationKind.ENSURES, null);
      }
      public static RefutedAnnotation BuildRefutedAssert(Variable constant) {
        return new RefutedAnnotation(constant, RefutedAnnotationKind.ASSERT, null);
      }
    }

    private void PrintRefutedCall(CallCounterexample err, XmlSink xmlOut) {
      Expr cond = err.FailingRequires.Condition;
      Variable houdiniConst;
      if (MatchCandidate(cond, out houdiniConst)) {
        xmlOut.WriteError("precondition violation", err.FailingCall.tok, err.FailingRequires.tok, err.Trace);
      }
    }

    private void PrintRefutedReturn(ReturnCounterexample err, XmlSink xmlOut) {
      Expr cond = err.FailingEnsures.Condition;
      Variable houdiniConst;
      if (MatchCandidate(cond, out houdiniConst)) {
        xmlOut.WriteError("postcondition violation", err.FailingReturn.tok, err.FailingEnsures.tok, err.Trace);
      }
    }

    private void PrintRefutedAssert(AssertCounterexample err, XmlSink xmlOut) {
      Expr cond = err.FailingAssert.OrigExpr;
      Variable houdiniConst;
      if (MatchCandidate(cond, out houdiniConst)) {
        xmlOut.WriteError("postcondition violation", err.FailingAssert.tok, err.FailingAssert.tok, err.Trace);
      }
    }

    private void DebugRefutedCandidates(Implementation curFunc, List<Counterexample> errors) {
      XmlSink xmlRefuted = CommandLineOptions.Clo.XmlRefuted;
      if (xmlRefuted != null && errors != null) {
        DateTime start = DateTime.UtcNow;
        xmlRefuted.WriteStartMethod(curFunc.ToString(), start);

        foreach (Counterexample error in errors) {
          CallCounterexample ce = error as CallCounterexample;
          if (ce != null) PrintRefutedCall(ce, xmlRefuted);
          ReturnCounterexample re = error as ReturnCounterexample;
          if (re != null) PrintRefutedReturn(re, xmlRefuted);
          AssertCounterexample ae = error as AssertCounterexample;
          if (ae != null) PrintRefutedAssert(ae, xmlRefuted);
        }

        DateTime end = DateTime.UtcNow;
        xmlRefuted.WriteEndMethod("errors", end, end.Subtract(start));
      }
    }

    private RefutedAnnotation ExtractRefutedAnnotation(Counterexample error) {
      Variable houdiniConstant;
      CallCounterexample callCounterexample = error as CallCounterexample;
      if (callCounterexample != null) {
        Procedure failingProcedure = callCounterexample.FailingCall.Proc;
        Requires failingRequires = callCounterexample.FailingRequires;
        if (MatchCandidate(failingRequires.Condition, out houdiniConstant)) {
          Contract.Assert(houdiniConstant != null);
          return RefutedAnnotation.BuildRefutedRequires(houdiniConstant, failingProcedure);
        }
      }
      ReturnCounterexample returnCounterexample = error as ReturnCounterexample;
      if (returnCounterexample != null) {
        Ensures failingEnsures = returnCounterexample.FailingEnsures;
        if (MatchCandidate(failingEnsures.Condition, out houdiniConstant)) {
          Contract.Assert(houdiniConstant != null);
          return RefutedAnnotation.BuildRefutedEnsures(houdiniConstant);
        }
      }
      AssertCounterexample assertCounterexample = error as AssertCounterexample;
      if (assertCounterexample != null) {
        AssertCmd failingAssert = assertCounterexample.FailingAssert;
        if (MatchCandidate(failingAssert.OrigExpr, out houdiniConstant)) {
          Contract.Assert(houdiniConstant != null);
          return RefutedAnnotation.BuildRefutedAssert(houdiniConstant);
        }
      }

      return null;
    }

    private ProverInterface.Outcome TryCatchVerify(HoudiniSession session, out List<Counterexample> errors) {
      ProverInterface.Outcome outcome;
      try {
        outcome = session.Verify(proverInterface, currentHoudiniState.Assignment, out errors);
      }
      catch (UnexpectedProverOutputException upo) {
        Contract.Assume(upo != null);
        errors = null;
        outcome = ProverInterface.Outcome.Undetermined;
      }
      return outcome;
    }

    private void HoudiniVerifyCurrent(HoudiniSession session) {
      while (true) {
        this.NotifyAssignment(currentHoudiniState.Assignment);

        //check the VC with the current assignment
        List<Counterexample> errors;
        ProverInterface.Outcome outcome = TryCatchVerify(session, out errors);
        this.NotifyOutcome(outcome);

        DebugRefutedCandidates(currentHoudiniState.Implementation, errors);

        if (UpdateHoudiniOutcome(currentHoudiniState.Outcome, currentHoudiniState.Implementation, outcome, errors)) { // abort
          currentHoudiniState.WorkQueue.Dequeue();
          this.NotifyDequeue();
          FlushWorkList();
          return;
        }
        else if (UpdateAssignmentWorkList(outcome, errors)) {
          if (CommandLineOptions.Clo.UseUnsatCoreForContractInfer && outcome == ProverInterface.Outcome.Valid)
            session.UpdateUnsatCore(proverInterface, currentHoudiniState.Assignment);
          currentHoudiniState.WorkQueue.Dequeue();
          this.NotifyDequeue();
          return;
        }
      } 
    }
  }

  public class VCGenOutcome {
    public VCGen.Outcome outcome;
    public List<Counterexample> errors;
    public VCGenOutcome(ProverInterface.Outcome outcome, List<Counterexample> errors) {
      this.outcome = ConditionGeneration.ProverInterfaceOutcomeToConditionGenerationOutcome(outcome);
      this.errors = errors;
    }
  }

  public class HoudiniOutcome {
    // final assignment
    public Dictionary<string, bool> assignment = new Dictionary<string, bool>();
    // boogie errors
    public Dictionary<string, VCGenOutcome> implementationOutcomes = new Dictionary<string, VCGenOutcome>();

    // statistics 

    private int CountResults(VCGen.Outcome outcome) {
      int outcomeCount = 0;
      foreach (VCGenOutcome verifyOutcome in implementationOutcomes.Values) {
        if (verifyOutcome.outcome == outcome)
          outcomeCount++;
      }
      return outcomeCount;
    }

    private List<string> ListOutcomeMatches(VCGen.Outcome outcome) {
      List<string> result = new List<string>();
      foreach (KeyValuePair<string, VCGenOutcome> kvpair in implementationOutcomes) {
        if (kvpair.Value.outcome == outcome)
          result.Add(kvpair.Key);
      }
      return result;
    }

    public int ErrorCount {
      get {
        return CountResults(VCGen.Outcome.Errors);
      }
    }
    public int Verified {
      get {
        return CountResults(VCGen.Outcome.Correct);
      }
    }
    public int Inconclusives {
      get {
        return CountResults(VCGen.Outcome.Inconclusive);
      }
    }
    public int TimeOuts {
      get {
        return CountResults(VCGen.Outcome.TimedOut);
      }
    }
    public List<string> ListOfTimeouts {
      get {
        return ListOutcomeMatches(VCGen.Outcome.TimedOut);
      }
    }
    public List<string> ListOfInconclusives {
      get {
        return ListOutcomeMatches(VCGen.Outcome.Inconclusive);
      }
    }
    public List<string> ListOfErrors {
      get {
        return ListOutcomeMatches(VCGen.Outcome.Errors);
      }
    }
  }

}
