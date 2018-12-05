using System.Collections.Generic;
using System.Numerics;
using Microsoft.Boogie;
using System.IO;
using System.Text;


using System;
using System.Diagnostics.Contracts;

namespace Microsoft.Dafny {



public class Parser {
	public const int _EOF = 0;
	public const int _ident = 1;
	public const int _digits = 2;
	public const int _arrayToken = 3;
	public const int _string = 4;
	public const int _colon = 5;
	public const int _lbrace = 6;
	public const int _rbrace = 7;
	public const int maxT = 123;

	const bool T = true;
	const bool x = false;
	const int minErrDist = 2;

	public Scanner/*!*/ scanner;
	public Errors/*!*/  errors;

	public Token/*!*/ t;    // last recognized token
	public Token/*!*/ la;   // lookahead token
	int errDist = minErrDist;

readonly Expression/*!*/ dummyExpr;
readonly AssignmentRhs/*!*/ dummyRhs;
readonly FrameExpression/*!*/ dummyFrameExpr;
readonly Statement/*!*/ dummyStmt;
readonly Attributes.Argument/*!*/ dummyAttrArg;
readonly ModuleDecl theModule;
readonly BuiltIns theBuiltIns;
int anonymousIds = 0;

struct MemberModifiers {
  public bool IsGhost;
  public bool IsStatic;
}
// helper routine for parsing call statements
///<summary>
/// Parses top-level things (modules, classes, datatypes, class members) from "filename"
/// and appends them in appropriate form to "module".
/// Returns the number of parsing errors encountered.
/// Note: first initialize the Scanner.
///</summary>
public static int Parse (string/*!*/ filename, ModuleDecl module, BuiltIns builtIns) /* throws System.IO.IOException */ {
  Contract.Requires(filename != null);
  Contract.Requires(module != null);
  string s;
  if (filename == "stdin.dfy") {
    s = Microsoft.Boogie.ParserHelper.Fill(System.Console.In, new List<string>());
    return Parse(s, filename, module, builtIns);
  } else {
    using (System.IO.StreamReader reader = new System.IO.StreamReader(filename)) {
      s = Microsoft.Boogie.ParserHelper.Fill(reader, new List<string>());
      return Parse(s, filename, module, builtIns);
    }
  }
}
///<summary>
/// Parses top-level things (modules, classes, datatypes, class members)
/// and appends them in appropriate form to "module".
/// Returns the number of parsing errors encountered.
/// Note: first initialize the Scanner.
///</summary>
public static int Parse (string/*!*/ s, string/*!*/ filename, ModuleDecl module, BuiltIns builtIns) {
  Contract.Requires(s != null);
  Contract.Requires(filename != null);
  Contract.Requires(module != null);
  Errors errors = new Errors();
  return Parse(s, filename, module, builtIns, errors);
}
///<summary>
/// Parses top-level things (modules, classes, datatypes, class members)
/// and appends them in appropriate form to "module".
/// Returns the number of parsing errors encountered.
/// Note: first initialize the Scanner with the given Errors sink.
///</summary>
public static int Parse (string/*!*/ s, string/*!*/ filename, ModuleDecl module, BuiltIns builtIns,
                         Errors/*!*/ errors) {
  Contract.Requires(s != null);
  Contract.Requires(filename != null);
  Contract.Requires(module != null);
  Contract.Requires(errors != null);
  byte[]/*!*/ buffer = cce.NonNull( UTF8Encoding.Default.GetBytes(s));
  MemoryStream ms = new MemoryStream(buffer,false);
  Scanner scanner = new Scanner(ms, errors, filename);
  Parser parser = new Parser(scanner, errors, module, builtIns);
  parser.Parse();
  return parser.errors.count;
}
public Parser(Scanner/*!*/ scanner, Errors/*!*/ errors, ModuleDecl module, BuiltIns builtIns) 
  : this(scanner, errors)  // the real work
{
  // initialize readonly fields
  dummyExpr = new LiteralExpr(Token.NoToken);
  dummyRhs = new ExprRhs(dummyExpr, null);
  dummyFrameExpr = new FrameExpression(dummyExpr.tok, dummyExpr, null);
  dummyStmt = new ReturnStmt(Token.NoToken, null);
  dummyAttrArg = new Attributes.Argument(Token.NoToken, "dummyAttrArg");
  theModule = module;
  theBuiltIns = builtIns;
}

bool IsAttribute() {
  Token x = scanner.Peek();
  return la.kind == _lbrace && x.kind == _colon;
}
/*--------------------------------------------------------------------------*/


	public Parser(Scanner/*!*/ scanner, Errors/*!*/ errors) {
		this.scanner = scanner;
		this.errors = errors;
		Token/*!*/ tok = new Token();
		tok.val = "";
		this.la = tok;
		this.t = new Token(); // just to satisfy its non-null constraint
	}

	void SynErr (int n) {
		if (errDist >= minErrDist) errors.SynErr(la.filename, la.line, la.col, n);
		errDist = 0;
	}

	public void SemErr (string/*!*/ msg) {
		Contract.Requires(msg != null);
		if (errDist >= minErrDist) errors.SemErr(t, msg);
		errDist = 0;
	}

	public void SemErr(IToken/*!*/ tok, string/*!*/ msg) {
	  Contract.Requires(tok != null);
	  Contract.Requires(msg != null);
	  errors.SemErr(tok, msg);
	}

	void Get () {
		for (;;) {
			t = la;
			la = scanner.Scan();
			if (la.kind <= maxT) { ++errDist; break; }

			la = t;
		}
	}

	void Expect (int n) {
		if (la.kind==n) Get(); else { SynErr(n); }
	}

	bool StartOf (int s) {
		return set[s, la.kind];
	}

	void ExpectWeak (int n, int follow) {
		if (la.kind == n) Get();
		else {
			SynErr(n);
			while (!StartOf(follow)) Get();
		}
	}


	bool WeakSeparator(int n, int syFol, int repFol) {
		int kind = la.kind;
		if (kind == n) {Get(); return true;}
		else if (StartOf(repFol)) {return false;}
		else {
			SynErr(n);
			while (!(set[syFol, kind] || set[repFol, kind] || set[0, kind])) {
				Get();
				kind = la.kind;
			}
			return StartOf(syFol);
		}
	}


	void Dafny() {
		ClassDecl/*!*/ c; DatatypeDecl/*!*/ dt; ArbitraryTypeDecl at; IteratorDecl iter;
		List<MemberDecl/*!*/> membersDefaultClass = new List<MemberDecl/*!*/>();
		ModuleDecl submodule;
		// to support multiple files, create a default module only if theModule is null
		DefaultModuleDecl defaultModule = (DefaultModuleDecl)((LiteralModuleDecl)theModule).ModuleDef;
		// theModule should be a DefaultModuleDecl (actually, the singular DefaultModuleDecl)
		Contract.Assert(defaultModule != null);
		bool isGhost;
		
		while (StartOf(1)) {
			isGhost = false; 
			if (la.kind == 8) {
				Get();
				isGhost = true; 
			}
			switch (la.kind) {
			case 9: case 11: {
				SubModuleDecl(defaultModule, isGhost, out submodule);
				defaultModule.TopLevelDecls.Add(submodule); 
				break;
			}
			case 18: {
				if (isGhost) { SemErr(t, "a class is not allowed to be declared as 'ghost'"); } 
				ClassDecl(defaultModule, out c);
				defaultModule.TopLevelDecls.Add(c); 
				break;
			}
			case 20: case 21: {
				if (isGhost) { SemErr(t, "a datatype/codatatype is not allowed to be declared as 'ghost'"); } 
				DatatypeDecl(defaultModule, out dt);
				defaultModule.TopLevelDecls.Add(dt); 
				break;
			}
			case 25: {
				if (isGhost) { SemErr(t, "a type is not allowed to be declared as 'ghost'"); } 
				ArbitraryTypeDecl(defaultModule, out at);
				defaultModule.TopLevelDecls.Add(at); 
				break;
			}
			case 29: {
				if (isGhost) { SemErr(t, "an iterator is not allowed to be declared as 'ghost'"); } 
				IteratorDecl(defaultModule, out iter);
				defaultModule.TopLevelDecls.Add(iter); 
				break;
			}
			case 8: case 19: case 23: case 34: case 35: case 53: case 54: case 55: {
				ClassMemberDecl(membersDefaultClass, isGhost, false);
				break;
			}
			default: SynErr(124); break;
			}
		}
		DefaultClassDecl defaultClass = null;
		foreach (TopLevelDecl topleveldecl in defaultModule.TopLevelDecls) {
		 defaultClass = topleveldecl as DefaultClassDecl;
		 if (defaultClass != null) {
		   defaultClass.Members.AddRange(membersDefaultClass);
		   break;
		 }
		}
		if (defaultClass == null) { // create the default class here, because it wasn't found
		 defaultClass = new DefaultClassDecl(defaultModule, membersDefaultClass);
		 defaultModule.TopLevelDecls.Add(defaultClass);
		} 
		Expect(0);
	}

	void SubModuleDecl(ModuleDefinition parent, bool isOverallModuleGhost, out ModuleDecl submodule) {
		ClassDecl/*!*/ c; DatatypeDecl/*!*/ dt; ArbitraryTypeDecl at; IteratorDecl iter;
		Attributes attrs = null;  IToken/*!*/ id; 
		List<MemberDecl/*!*/> namedModuleDefaultClassMembers = new List<MemberDecl>();;
		List<IToken> idRefined = null, idPath = null, idAssignment = null;
		bool isGhost = false;
		ModuleDefinition module;
		ModuleDecl sm;
		submodule = null; // appease compiler
		bool opened = false;
		
		if (la.kind == 9) {
			Get();
			while (la.kind == 6) {
				Attribute(ref attrs);
			}
			NoUSIdent(out id);
			if (la.kind == 10) {
				Get();
				QualifiedName(out idRefined);
			}
			module = new ModuleDefinition(id, id.val, isOverallModuleGhost, false, idRefined == null ? null : idRefined, attrs, false); 
			Expect(6);
			module.BodyStartTok = t; 
			while (StartOf(1)) {
				isGhost = false; 
				if (la.kind == 8) {
					Get();
					isGhost = true; 
				}
				switch (la.kind) {
				case 9: case 11: {
					SubModuleDecl(module, isGhost, out sm);
					module.TopLevelDecls.Add(sm); 
					break;
				}
				case 18: {
					if (isGhost) { SemErr(t, "a class is not allowed to be declared as 'ghost'"); } 
					ClassDecl(module, out c);
					module.TopLevelDecls.Add(c); 
					break;
				}
				case 20: case 21: {
					if (isGhost) { SemErr(t, "a datatype/codatatype is not allowed to be declared as 'ghost'"); } 
					DatatypeDecl(module, out dt);
					module.TopLevelDecls.Add(dt); 
					break;
				}
				case 25: {
					if (isGhost) { SemErr(t, "a type is not allowed to be declared as 'ghost'"); } 
					ArbitraryTypeDecl(module, out at);
					module.TopLevelDecls.Add(at); 
					break;
				}
				case 29: {
					if (isGhost) { SemErr(t, "an iterator is not allowed to be declared as 'ghost'"); } 
					IteratorDecl(module, out iter);
					module.TopLevelDecls.Add(iter); 
					break;
				}
				case 8: case 19: case 23: case 34: case 35: case 53: case 54: case 55: {
					ClassMemberDecl(namedModuleDefaultClassMembers, isGhost, false);
					break;
				}
				default: SynErr(125); break;
				}
			}
			Expect(7);
			module.BodyEndTok = t;
			module.TopLevelDecls.Add(new DefaultClassDecl(module, namedModuleDefaultClassMembers));
			submodule = new LiteralModuleDecl(module, parent); 
		} else if (la.kind == 11) {
			Get();
			if (la.kind == 12) {
				Get();
				opened = true;
			}
			NoUSIdent(out id);
			if (la.kind == 13) {
				Get();
				QualifiedName(out idPath);
				Expect(14);
				submodule = new AliasModuleDecl(idPath, id, parent, opened); 
			} else if (la.kind == 14) {
				Get();
				idPath = new List<IToken>(); idPath.Add(id); submodule = new AliasModuleDecl(idPath, id, parent, opened); 
			} else if (la.kind == 15) {
				Get();
				QualifiedName(out idPath);
				if (la.kind == 16) {
					Get();
					QualifiedName(out idAssignment);
				}
				Expect(14);
				submodule = new AbstractModuleDecl(idPath, id, parent, idAssignment, opened); 
			} else SynErr(126);
		} else SynErr(127);
	}

	void ClassDecl(ModuleDefinition/*!*/ module, out ClassDecl/*!*/ c) {
		Contract.Requires(module != null);
		Contract.Ensures(Contract.ValueAtReturn(out c) != null);
		IToken/*!*/ id;
		Attributes attrs = null;
		List<TypeParameter/*!*/> typeArgs = new List<TypeParameter/*!*/>();
		List<MemberDecl/*!*/> members = new List<MemberDecl/*!*/>();
		IToken bodyStart;
		
		while (!(la.kind == 0 || la.kind == 18)) {SynErr(128); Get();}
		Expect(18);
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 32) {
			GenericParameters(typeArgs);
		}
		Expect(6);
		bodyStart = t; 
		while (StartOf(2)) {
			ClassMemberDecl(members, false, true);
		}
		Expect(7);
		c = new ClassDecl(id, id.val, module, typeArgs, members, attrs);
		c.BodyStartTok = bodyStart;
		c.BodyEndTok = t;
		
	}

	void DatatypeDecl(ModuleDefinition/*!*/ module, out DatatypeDecl/*!*/ dt) {
		Contract.Requires(module != null);
		Contract.Ensures(Contract.ValueAtReturn(out dt)!=null);
		IToken/*!*/ id;
		Attributes attrs = null;
		List<TypeParameter/*!*/> typeArgs = new List<TypeParameter/*!*/>();
		List<DatatypeCtor/*!*/> ctors = new List<DatatypeCtor/*!*/>();
		IToken bodyStart = Token.NoToken;  // dummy assignment
		bool co = false;
		
		while (!(la.kind == 0 || la.kind == 20 || la.kind == 21)) {SynErr(129); Get();}
		if (la.kind == 20) {
			Get();
		} else if (la.kind == 21) {
			Get();
			co = true; 
		} else SynErr(130);
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 32) {
			GenericParameters(typeArgs);
		}
		Expect(13);
		bodyStart = t; 
		DatatypeMemberDecl(ctors);
		while (la.kind == 22) {
			Get();
			DatatypeMemberDecl(ctors);
		}
		while (!(la.kind == 0 || la.kind == 14)) {SynErr(131); Get();}
		Expect(14);
		if (co) {
		 dt = new CoDatatypeDecl(id, id.val, module, typeArgs, ctors, attrs);
		} else {
		 dt = new IndDatatypeDecl(id, id.val, module, typeArgs, ctors, attrs);
		}
		dt.BodyStartTok = bodyStart;
		dt.BodyEndTok = t;
		
	}

	void ArbitraryTypeDecl(ModuleDefinition/*!*/ module, out ArbitraryTypeDecl at) {
		IToken/*!*/ id;
		Attributes attrs = null;
		var eqSupport = TypeParameter.EqualitySupportValue.Unspecified;
		
		Expect(25);
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 26) {
			Get();
			Expect(27);
			Expect(28);
			eqSupport = TypeParameter.EqualitySupportValue.Required; 
		}
		at = new ArbitraryTypeDecl(id, id.val, module, eqSupport, attrs); 
		while (!(la.kind == 0 || la.kind == 14)) {SynErr(132); Get();}
		Expect(14);
	}

	void IteratorDecl(ModuleDefinition module, out IteratorDecl/*!*/ iter) {
		Contract.Ensures(Contract.ValueAtReturn(out iter) != null);
		IToken/*!*/ id;
		Attributes attrs = null;
		List<TypeParameter/*!*/>/*!*/ typeArgs = new List<TypeParameter/*!*/>();
		IToken openParen;
		List<Formal/*!*/> ins = new List<Formal/*!*/>();
		List<Formal/*!*/> outs = new List<Formal/*!*/>();
		List<PatternExpression/*!*/> patterns = new List<PatternExpression/*!*/>(); // Yuyan
		List<FrameExpression/*!*/> reads = new List<FrameExpression/*!*/>();
		List<FrameExpression/*!*/> mod = new List<FrameExpression/*!*/>();
		List<Expression/*!*/> decreases = new List<Expression>();
		List<MaybeFreeExpression/*!*/> req = new List<MaybeFreeExpression/*!*/>();
		List<MaybeFreeExpression/*!*/> ens = new List<MaybeFreeExpression/*!*/>();
		List<MaybeFreeExpression/*!*/> yieldReq = new List<MaybeFreeExpression/*!*/>();
		List<MaybeFreeExpression/*!*/> yieldEns = new List<MaybeFreeExpression/*!*/>();
		List<Expression/*!*/> dec = new List<Expression/*!*/>();
		Attributes readsAttrs = null;
		Attributes modAttrs = null;
		Attributes decrAttrs = null;
		BlockStmt body = null;
		bool signatureOmitted = false;
		IToken bodyStart = Token.NoToken;
		IToken bodyEnd = Token.NoToken;
		
		while (!(la.kind == 0 || la.kind == 29)) {SynErr(133); Get();}
		Expect(29);
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 26 || la.kind == 32) {
			if (la.kind == 32) {
				GenericParameters(typeArgs);
			}
			Formals(true, true, ins, out openParen);
			if (la.kind == 30) {
				Get();
				Formals(false, true, outs, out openParen);
			}
		} else if (la.kind == 31) {
			Get();
			signatureOmitted = true; openParen = Token.NoToken; 
		} else SynErr(134);
		while (StartOf(3)) {
			IteratorSpec(reads, mod, decreases, req, ens, yieldReq, yieldEns, ref readsAttrs, ref modAttrs, ref decrAttrs);
		}
		if (la.kind == 6) {
			BlockStmt(out body, out bodyStart, out bodyEnd);
		}
		iter = new IteratorDecl(id, id.val, module, typeArgs, ins, outs, patterns,
		                       new Specification<FrameExpression>(reads, readsAttrs),
		                       new Specification<FrameExpression>(mod, modAttrs),
		                       new Specification<Expression>(decreases, decrAttrs),
		                       req, ens, yieldReq, yieldEns,
		                       body, attrs, signatureOmitted);
		iter.BodyStartTok = bodyStart;
		iter.BodyEndTok = bodyEnd;
		
	}

	void ClassMemberDecl(List<MemberDecl/*!*/>/*!*/ mm, bool isAlreadyGhost, bool allowConstructors) {
		Contract.Requires(cce.NonNullElements(mm));
		Method/*!*/ m;
		Function/*!*/ f;
		MemberModifiers mmod = new MemberModifiers();
		mmod.IsGhost = isAlreadyGhost;
		
		while (la.kind == 8 || la.kind == 19) {
			if (la.kind == 8) {
				Get();
				mmod.IsGhost = true; 
			} else {
				Get();
				mmod.IsStatic = true; 
			}
		}
		if (la.kind == 23) {
			FieldDecl(mmod, mm);
		} else if (la.kind == 53 || la.kind == 54 || la.kind == 55) {
			FunctionDecl(mmod, out f);
			mm.Add(f); 
		} else if (la.kind == 34 || la.kind == 35) {
			MethodDecl(mmod, allowConstructors, out m);
			mm.Add(m); 
		} else SynErr(135);
	}

	void Attribute(ref Attributes attrs) {
		Expect(6);
		AttributeBody(ref attrs);
		Expect(7);
	}

	void NoUSIdent(out IToken/*!*/ x) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null); 
		Expect(1);
		x = t; 
		if (x.val.StartsWith("_")) {
		 SemErr("cannot declare identifier beginning with underscore");
		}
		
	}

	void QualifiedName(out List<IToken> ids) {
		IToken id; ids = new List<IToken>(); 
		Ident(out id);
		ids.Add(id); 
		while (la.kind == 17) {
			Get();
			Ident(out id);
			ids.Add(id); 
		}
	}

	void Ident(out IToken/*!*/ x) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null); 
		Expect(1);
		x = t; 
	}

	void GenericParameters(List<TypeParameter/*!*/>/*!*/ typeArgs) {
		Contract.Requires(cce.NonNullElements(typeArgs));
		IToken/*!*/ id;
		TypeParameter.EqualitySupportValue eqSupport;
		
		Expect(32);
		NoUSIdent(out id);
		eqSupport = TypeParameter.EqualitySupportValue.Unspecified; 
		if (la.kind == 26) {
			Get();
			Expect(27);
			Expect(28);
			eqSupport = TypeParameter.EqualitySupportValue.Required; 
		}
		typeArgs.Add(new TypeParameter(id, id.val, eqSupport)); 
		while (la.kind == 24) {
			Get();
			NoUSIdent(out id);
			eqSupport = TypeParameter.EqualitySupportValue.Unspecified; 
			if (la.kind == 26) {
				Get();
				Expect(27);
				Expect(28);
				eqSupport = TypeParameter.EqualitySupportValue.Required; 
			}
			typeArgs.Add(new TypeParameter(id, id.val, eqSupport)); 
		}
		Expect(33);
	}

	void FieldDecl(MemberModifiers mmod, List<MemberDecl/*!*/>/*!*/ mm) {
		Contract.Requires(cce.NonNullElements(mm));
		Attributes attrs = null;
		IToken/*!*/ id;  Type/*!*/ ty;
		
		while (!(la.kind == 0 || la.kind == 23)) {SynErr(136); Get();}
		Expect(23);
		if (mmod.IsStatic) { SemErr(t, "fields cannot be declared 'static'"); }
		
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		IdentType(out id, out ty, false);
		mm.Add(new Field(id, id.val, mmod.IsGhost, ty, attrs)); 
		while (la.kind == 24) {
			Get();
			IdentType(out id, out ty, false);
			mm.Add(new Field(id, id.val, mmod.IsGhost, ty, attrs)); 
		}
		while (!(la.kind == 0 || la.kind == 14)) {SynErr(137); Get();}
		Expect(14);
	}

	void FunctionDecl(MemberModifiers mmod, out Function/*!*/ f) {
		Contract.Ensures(Contract.ValueAtReturn(out f)!=null);
		Attributes attrs = null;
		IToken/*!*/ id = Token.NoToken;  // to please compiler
		List<TypeParameter/*!*/> typeArgs = new List<TypeParameter/*!*/>();
		List<Formal/*!*/> formals = new List<Formal/*!*/>();
		List<PatternExpression/*!*/> patterns = new List<PatternExpression/*!*/>();
		Type/*!*/ returnType = new BoolType();
		List<Expression/*!*/> reqs = new List<Expression/*!*/>();
		List<Expression/*!*/> ens = new List<Expression/*!*/>();
		List<FrameExpression/*!*/> reads = new List<FrameExpression/*!*/>();
		List<Expression/*!*/> decreases;
		Expression body = null;
		bool isPredicate = false;  bool isCoPredicate = false;
		bool isFunctionMethod = false;
		IToken openParen = null;
		IToken bodyStart = Token.NoToken;
		IToken bodyEnd = Token.NoToken;
		bool signatureOmitted = false;
		
		if (la.kind == 53) {
			Get();
			if (la.kind == 34) {
				Get();
				isFunctionMethod = true; 
			}
			if (mmod.IsGhost) { SemErr(t, "functions cannot be declared 'ghost' (they are ghost by default)"); }
			
			while (la.kind == 6) {
				Attribute(ref attrs);
			}
			NoUSIdent(out id);
			if (la.kind == 26 || la.kind == 32) {
				if (la.kind == 32) {
					GenericParameters(typeArgs);
				}
				Formals(true, isFunctionMethod, formals, out openParen);
				Expect(5);
				Type(out returnType);
			} else if (la.kind == 31) {
				Get();
				signatureOmitted = true;
				openParen = Token.NoToken; 
			} else SynErr(138);
		} else if (la.kind == 54) {
			Get();
			isPredicate = true; 
			if (la.kind == 34) {
				Get();
				isFunctionMethod = true; 
			}
			if (mmod.IsGhost) { SemErr(t, "predicates cannot be declared 'ghost' (they are ghost by default)"); }
			
			while (la.kind == 6) {
				Attribute(ref attrs);
			}
			NoUSIdent(out id);
			if (StartOf(4)) {
				if (la.kind == 32) {
					GenericParameters(typeArgs);
				}
				if (la.kind == 26) {
					Formals(true, isFunctionMethod, formals, out openParen);
					if (la.kind == 5) {
						Get();
						SemErr(t, "predicates do not have an explicitly declared return type; it is always bool"); 
					}
				}
			} else if (la.kind == 31) {
				Get();
				signatureOmitted = true;
				openParen = Token.NoToken; 
			} else SynErr(139);
		} else if (la.kind == 55) {
			Get();
			isCoPredicate = true; 
			if (mmod.IsGhost) { SemErr(t, "copredicates cannot be declared 'ghost' (they are ghost by default)"); }
			
			while (la.kind == 6) {
				Attribute(ref attrs);
			}
			NoUSIdent(out id);
			if (StartOf(4)) {
				if (la.kind == 32) {
					GenericParameters(typeArgs);
				}
				if (la.kind == 26) {
					Formals(true, isFunctionMethod, formals, out openParen);
					if (la.kind == 5) {
						Get();
						SemErr(t, "copredicates do not have an explicitly declared return type; it is always bool"); 
					}
				}
			} else if (la.kind == 31) {
				Get();
				signatureOmitted = true;
				openParen = Token.NoToken; 
			} else SynErr(140);
		} else SynErr(141);
		decreases = isCoPredicate ? null : new List<Expression/*!*/>(); 
		while (StartOf(5)) {
			FunctionSpec(reqs, reads, ens, decreases, patterns);
		}
		if (la.kind == 6) {
			FunctionBody(out body, out bodyStart, out bodyEnd, patterns /* Yuyan */);
		}
		if (isPredicate) {
		  f = new Predicate(id, id.val, mmod.IsStatic, !isFunctionMethod, typeArgs, openParen, formals, patterns,
		                    reqs, reads, ens, new Specification<Expression>(decreases, null), body, Predicate.BodyOriginKind.OriginalOrInherited, attrs, signatureOmitted);
		} else if (isCoPredicate) {
		  f = new CoPredicate(id, id.val, mmod.IsStatic, typeArgs, openParen, formals, patterns,
		                    reqs, reads, ens, body, attrs, signatureOmitted);
		} else {
		  f = new Function(id, id.val, mmod.IsStatic, !isFunctionMethod, typeArgs, openParen, formals, patterns, returnType,
		                   reqs, reads, ens, new Specification<Expression>(decreases, null), body, attrs, signatureOmitted);
		}
		f.BodyStartTok = bodyStart;
		f.BodyEndTok = bodyEnd;
		
	}

	void MethodDecl(MemberModifiers mmod, bool allowConstructor, out Method/*!*/ m) {
		Contract.Ensures(Contract.ValueAtReturn(out m) !=null);
		IToken/*!*/ id;
		Attributes attrs = null;
		List<TypeParameter/*!*/>/*!*/ typeArgs = new List<TypeParameter/*!*/>();
		IToken openParen;
		List<Formal/*!*/> ins = new List<Formal/*!*/>();
		List<Formal/*!*/> outs = new List<Formal/*!*/>();
		List<PatternExpression/*!*/> patterns = new List<PatternExpression/*!*/>();
		List<MaybeFreeExpression/*!*/> req = new List<MaybeFreeExpression/*!*/>();
		List<FrameExpression/*!*/> mod = new List<FrameExpression/*!*/>();
		List<MaybeFreeExpression/*!*/> ens = new List<MaybeFreeExpression/*!*/>();
		List<Expression/*!*/> dec = new List<Expression/*!*/>();
		Attributes decAttrs = null;
		Attributes modAttrs = null;
		BlockStmt body = null;
		bool isConstructor = false;
		bool signatureOmitted = false;
		IToken bodyStart = Token.NoToken;
		IToken bodyEnd = Token.NoToken;
		
		while (!(la.kind == 0 || la.kind == 34 || la.kind == 35)) {SynErr(142); Get();}
		if (la.kind == 34) {
			Get();
		} else if (la.kind == 35) {
			Get();
			if (allowConstructor) {
			 isConstructor = true;
			} else {
			 SemErr(t, "constructors are only allowed in classes");
			}
			
		} else SynErr(143);
		if (isConstructor) {
		 if (mmod.IsGhost) {
		   SemErr(t, "constructors cannot be declared 'ghost'");
		 }
		 if (mmod.IsStatic) {
		   SemErr(t, "constructors cannot be declared 'static'");
		 }
		}
		
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 26 || la.kind == 32) {
			if (la.kind == 32) {
				GenericParameters(typeArgs);
			}
			Formals(true, !mmod.IsGhost, ins, out openParen);
			if (la.kind == 36) {
				Get();
				if (isConstructor) { SemErr(t, "constructors cannot have out-parameters"); } 
				Formals(false, !mmod.IsGhost, outs, out openParen);
			}
		} else if (la.kind == 31) {
			Get();
			signatureOmitted = true; openParen = Token.NoToken; 
		} else SynErr(144);
		while (StartOf(6)) {
			MethodSpec(req, mod, ens, dec, ref decAttrs, ref modAttrs, patterns);
		}
		if (la.kind == 6) {
			BlockStmt(out body, out bodyStart, out bodyEnd);
		}
		if (isConstructor) {
		 m = new Constructor(id, id.val, typeArgs, ins, patterns,
		                     req, new Specification<FrameExpression>(mod, modAttrs), ens, new Specification<Expression>(dec, decAttrs), body, attrs, signatureOmitted);
		} else {
		 m = new Method(id, id.val, mmod.IsStatic, mmod.IsGhost, typeArgs, ins, outs, patterns,
		                req, new Specification<FrameExpression>(mod, modAttrs), ens, new Specification<Expression>(dec, decAttrs), body, attrs, signatureOmitted);
		}
		m.BodyStartTok = bodyStart;
		m.BodyEndTok = bodyEnd;
		
	}

	void DatatypeMemberDecl(List<DatatypeCtor/*!*/>/*!*/ ctors) {
		Contract.Requires(cce.NonNullElements(ctors));
		Attributes attrs = null;
		IToken/*!*/ id;
		List<Formal/*!*/> formals = new List<Formal/*!*/>();
		
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		NoUSIdent(out id);
		if (la.kind == 26) {
			FormalsOptionalIds(formals);
		}
		ctors.Add(new DatatypeCtor(id, id.val, formals, attrs)); 
	}

	void FormalsOptionalIds(List<Formal/*!*/>/*!*/ formals) {
		Contract.Requires(cce.NonNullElements(formals)); IToken/*!*/ id;  Type/*!*/ ty;  string/*!*/ name;  bool isGhost; 
		Expect(26);
		if (StartOf(7)) {
			TypeIdentOptional(out id, out name, out ty, out isGhost);
			formals.Add(new Formal(id, name, ty, true, isGhost)); 
			while (la.kind == 24) {
				Get();
				TypeIdentOptional(out id, out name, out ty, out isGhost);
				formals.Add(new Formal(id, name, ty, true, isGhost)); 
			}
		}
		Expect(28);
	}

	void IdentType(out IToken/*!*/ id, out Type/*!*/ ty, bool allowWildcardId) {
		Contract.Ensures(Contract.ValueAtReturn(out id) != null); Contract.Ensures(Contract.ValueAtReturn(out ty) != null);
		WildIdent(out id, allowWildcardId);
		Expect(5);
		Type(out ty);
	}

	void GIdentType(bool allowGhostKeyword, out IToken/*!*/ id, out Type/*!*/ ty, out bool isGhost) {
		Contract.Ensures(Contract.ValueAtReturn(out id)!=null);
		Contract.Ensures(Contract.ValueAtReturn(out ty)!=null);
		isGhost = false;
		if (la.kind == 8) {
			Get();
			if (allowGhostKeyword) { isGhost = true; } else { SemErr(t, "formal cannot be declared 'ghost' in this context"); } 
		}
		IdentType(out id, out ty, true);
	}

	void WildIdent(out IToken/*!*/ x, bool allowWildcardId) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null); 
		Expect(1);
		x = t; 
		if (x.val.StartsWith("_")) {
		 if (allowWildcardId && x.val.Length == 1) {
		   t.val = "_v" + anonymousIds++;
		 } else {
		   SemErr("cannot declare identifier beginning with underscore");
		 }
		}
		
	}

	void Type(out Type/*!*/ ty) {
		Contract.Ensures(Contract.ValueAtReturn(out ty) != null); IToken/*!*/ tok; 
		TypeAndToken(out tok, out ty);
	}

	void LocalIdentTypeOptional(out VarDecl/*!*/ var, bool isGhost) {
		IToken/*!*/ id;  Type/*!*/ ty;  Type optType = null;
		
		WildIdent(out id, true);
		if (la.kind == 5) {
			Get();
			Type(out ty);
			optType = ty; 
		}
		var = new VarDecl(id, id.val, optType == null ? new InferredTypeProxy() : optType, isGhost); 
	}

	void IdentTypeOptional(out BoundVar/*!*/ var) {
		Contract.Ensures(Contract.ValueAtReturn(out var)!=null); IToken/*!*/ id;  Type/*!*/ ty;  Type optType = null;
		
		WildIdent(out id, true);
		if (la.kind == 5) {
			Get();
			Type(out ty);
			optType = ty; 
		}
		var = new BoundVar(id, id.val, optType == null ? new InferredTypeProxy() : optType); 
	}

	void TypeIdentOptional(out IToken/*!*/ id, out string/*!*/ identName, out Type/*!*/ ty, out bool isGhost) {
		Contract.Ensures(Contract.ValueAtReturn(out id)!=null);
		Contract.Ensures(Contract.ValueAtReturn(out ty)!=null);
		Contract.Ensures(Contract.ValueAtReturn(out identName)!=null);
		string name = null;  isGhost = false;
		if (la.kind == 8) {
			Get();
			isGhost = true; 
		}
		TypeAndToken(out id, out ty);
		if (la.kind == 5) {
			Get();
			UserDefinedType udt = ty as UserDefinedType;
			if (udt != null && udt.TypeArgs.Count == 0) {
			 name = udt.Name;
			} else {
			 SemErr(id, "invalid formal-parameter name in datatype constructor");
			}
			
			Type(out ty);
		}
		if (name != null) {
		 identName = name;
		} else {
		 identName = "#" + anonymousIds++;
		}
		
	}

	void TypeAndToken(out IToken/*!*/ tok, out Type/*!*/ ty) {
		Contract.Ensures(Contract.ValueAtReturn(out tok)!=null); Contract.Ensures(Contract.ValueAtReturn(out ty) != null); tok = Token.NoToken;  ty = new BoolType();  /*keep compiler happy*/
		List<Type/*!*/>/*!*/ gt;
		
		switch (la.kind) {
		case 44: {
			Get();
			tok = t; 
			break;
		}
		case 45: {
			Get();
			tok = t;  ty = new NatType(); 
			break;
		}
		case 46: {
			Get();
			tok = t;  ty = new IntType(); 
			break;
		}
		case 47: {
			Get();
			tok = t;  gt = new List<Type/*!*/>(); 
			GenericInstantiation(gt);
			if (gt.Count != 1) {
			 SemErr("set type expects exactly one type argument");
			}
			ty = new SetType(gt[0]);
			
			break;
		}
		case 48: {
			Get();
			tok = t;  gt = new List<Type/*!*/>(); 
			GenericInstantiation(gt);
			if (gt.Count != 1) {
			 SemErr("multiset type expects exactly one type argument");
			}
			ty = new MultiSetType(gt[0]);
			
			break;
		}
		case 49: {
			Get();
			tok = t;  gt = new List<Type/*!*/>(); 
			GenericInstantiation(gt);
			if (gt.Count != 1) {
			 SemErr("seq type expects exactly one type argument");
			}
			ty = new SeqType(gt[0]);
			
			break;
		}
		case 50: {
			Get();
			tok = t;  gt = new List<Type/*!*/>(); 
			GenericInstantiation(gt);
			if (gt.Count != 2) {
			 SemErr("map type expects exactly two type arguments");
			}
			else { ty = new MapType(gt[0], gt[1]); }
			
			break;
		}
		case 1: case 3: case 52: {
			ReferenceType(out tok, out ty);
			break;
		}
		case 51: {
			Get();
			tok = t;  ty = new RegionType(); 
			break;
		}
		default: SynErr(145); break;
		}
	}

	void Formals(bool incoming, bool allowGhostKeyword, List<Formal/*!*/>/*!*/ formals, out IToken openParen) {
		Contract.Requires(cce.NonNullElements(formals)); IToken/*!*/ id;  Type/*!*/ ty;  bool isGhost;
		Expect(26);
		openParen = t; 
		if (la.kind == 1 || la.kind == 8) {
			GIdentType(allowGhostKeyword, out id, out ty, out isGhost);
			formals.Add(new Formal(id, id.val, ty, incoming, isGhost)); 
			while (la.kind == 24) {
				Get();
				GIdentType(allowGhostKeyword, out id, out ty, out isGhost);
				formals.Add(new Formal(id, id.val, ty, incoming, isGhost)); 
			}
		}
		Expect(28);
	}

	void IteratorSpec(List<FrameExpression/*!*/>/*!*/ reads, List<FrameExpression/*!*/>/*!*/ mod, List<Expression/*!*/> decreases,
List<MaybeFreeExpression/*!*/>/*!*/ req, List<MaybeFreeExpression/*!*/>/*!*/ ens,
List<MaybeFreeExpression/*!*/>/*!*/ yieldReq, List<MaybeFreeExpression/*!*/>/*!*/ yieldEns,
ref Attributes readsAttrs, ref Attributes modAttrs, ref Attributes decrAttrs) {
		Expression/*!*/ e; FrameExpression/*!*/ fe; bool isFree = false; bool isYield = false; Attributes ensAttrs = null;
		
		while (!(StartOf(8))) {SynErr(146); Get();}
		if (la.kind == 42) {
			Get();
			while (IsAttribute()) {
				Attribute(ref readsAttrs);
			}
			if (StartOf(9)) {
				FrameExpression(out fe);
				reads.Add(fe); 
				while (la.kind == 24) {
					Get();
					FrameExpression(out fe);
					reads.Add(fe); 
				}
			}
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(147); Get();}
			Expect(14);
		} else if (la.kind == 37) {
			Get();
			while (IsAttribute()) {
				Attribute(ref modAttrs);
			}
			if (StartOf(9)) {
				FrameExpression(out fe);
				mod.Add(fe); 
				while (la.kind == 24) {
					Get();
					FrameExpression(out fe);
					mod.Add(fe); 
				}
			}
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(148); Get();}
			Expect(14);
		} else if (StartOf(10)) {
			if (la.kind == 38) {
				Get();
				isFree = true; 
			}
			if (la.kind == 43) {
				Get();
				isYield = true; 
			}
			if (la.kind == 39) {
				Get();
				Expression(out e, null);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(149); Get();}
				Expect(14);
				if (isYield) {
				 yieldReq.Add(new MaybeFreeExpression(e, isFree));
				} else {
				 req.Add(new MaybeFreeExpression(e, isFree));
				}
				
			} else if (la.kind == 40) {
				Get();
				while (IsAttribute()) {
					Attribute(ref ensAttrs);
				}
				Expression(out e, null);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(150); Get();}
				Expect(14);
				if (isYield) {
				yieldEns.Add(new MaybeFreeExpression(e, isFree, ensAttrs));
				} else {
				ens.Add(new MaybeFreeExpression(e, isFree, ensAttrs));
				}
				
			} else SynErr(151);
		} else if (la.kind == 41) {
			Get();
			while (IsAttribute()) {
				Attribute(ref decrAttrs);
			}
			DecreasesList(decreases, false);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(152); Get();}
			Expect(14);
		} else SynErr(153);
	}

	void BlockStmt(out BlockStmt/*!*/ block, out IToken bodyStart, out IToken bodyEnd) {
		Contract.Ensures(Contract.ValueAtReturn(out block) != null);
		List<Statement/*!*/> body = new List<Statement/*!*/>();
		
		Expect(6);
		bodyStart = t; 
		while (StartOf(11)) {
			Stmt(body);
		}
		Expect(7);
		bodyEnd = t;
		block = new BlockStmt(bodyStart, body); 
	}

	void MethodSpec(List<MaybeFreeExpression/*!*/>/*!*/ req, List<FrameExpression/*!*/>/*!*/ mod, List<MaybeFreeExpression/*!*/>/*!*/ ens,
List<Expression/*!*/>/*!*/ decreases, ref Attributes decAttrs, ref Attributes modAttrs, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(cce.NonNullElements(req)); Contract.Requires(cce.NonNullElements(mod)); Contract.Requires(cce.NonNullElements(ens)); Contract.Requires(cce.NonNullElements(decreases));
		Contract.Requires(cce.NonNullElements(patterns));
		Expression/*!*/ e; FrameExpression/*!*/ fe; bool isFree = false; Attributes ensAttrs = null;
		
		while (!(StartOf(12))) {SynErr(154); Get();}
		if (la.kind == 37) {
			Get();
			while (IsAttribute()) {
				Attribute(ref modAttrs);
			}
			if (StartOf(9)) {
				FrameExpression(out fe);
				mod.Add(fe); 
				while (la.kind == 24) {
					Get();
					FrameExpression(out fe);
					mod.Add(fe); 
				}
			}
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(155); Get();}
			Expect(14);
		} else if (la.kind == 38 || la.kind == 39 || la.kind == 40) {
			if (la.kind == 38) {
				Get();
				isFree = true; 
			}
			if (la.kind == 39) {
				Get();
				Expression(out e, patterns);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(156); Get();}
				Expect(14);
				req.Add(new MaybeFreeExpression(e, isFree)); 
			} else if (la.kind == 40) {
				Get();
				while (IsAttribute()) {
					Attribute(ref ensAttrs);
				}
				Expression(out e, null);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(157); Get();}
				Expect(14);
				ens.Add(new MaybeFreeExpression(e, isFree, ensAttrs)); 
			} else SynErr(158);
		} else if (la.kind == 41) {
			Get();
			while (IsAttribute()) {
				Attribute(ref decAttrs);
			}
			DecreasesList(decreases, true);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(159); Get();}
			Expect(14);
		} else SynErr(160);
	}

	void FrameExpression(out FrameExpression/*!*/ fe) {
		Contract.Ensures(Contract.ValueAtReturn(out fe) != null);
		Expression/*!*/ e;
		IToken/*!*/ id;
		string fieldName = null;  IToken feTok = null;
		fe = null;
		
		if (la.kind == 51) {
			Get();
			if (la.kind == 6) {
				RegionConstructExpr(t, out e, null);
				feTok = e.tok; fe = new FrameExpression(feTok, e, null);
			}
		} else if (StartOf(13)) {
			Expression(out e, null);
			feTok = e.tok; 
			if (la.kind == 57) {
				Get();
				Ident(out id);
				fieldName = id.val;  feTok = id; 
			}
			fe = new FrameExpression(feTok, e, fieldName); 
		} else if (la.kind == 57) {
			Get();
			Ident(out id);
			fieldName = id.val; 
			fe = new FrameExpression(id, new ImplicitThisExpr(id), fieldName); 
		} else SynErr(161);
	}

	void Expression(out Expression/*!*/ e, List<PatternExpression/*!*/> patterns) {
		EquivExpression(out e, patterns);
	}

	void DecreasesList(List<Expression/*!*/> decreases, bool allowWildcard) {
		Expression/*!*/ e; 
		PossiblyWildExpression(out e);
		if (!allowWildcard && e is WildcardExpr) {
		        SemErr(e.tok, "'decreases *' is only allowed on loops and tail-recursive methods");
		      } else {
		        decreases.Add(e);
		      }
		   
		while (la.kind == 24) {
			Get();
			PossiblyWildExpression(out e);
			if (!allowWildcard && e is WildcardExpr) {
			SemErr(e.tok, "'decreases *' is only allowed on loops and tail-recursive methods");
			} else {
			         decreases.Add(e);
			}
			
		}
	}

	void GenericInstantiation(List<Type/*!*/>/*!*/ gt) {
		Contract.Requires(cce.NonNullElements(gt)); Type/*!*/ ty; 
		Expect(32);
		Type(out ty);
		gt.Add(ty); 
		while (la.kind == 24) {
			Get();
			Type(out ty);
			gt.Add(ty); 
		}
		Expect(33);
	}

	void ReferenceType(out IToken/*!*/ tok, out Type/*!*/ ty) {
		Contract.Ensures(Contract.ValueAtReturn(out tok) != null); Contract.Ensures(Contract.ValueAtReturn(out ty) != null);
		tok = Token.NoToken;  ty = new BoolType();  /*keep compiler happy*/
		List<Type/*!*/>/*!*/ gt;
		List<IToken> path;
		
		if (la.kind == 52) {
			Get();
			tok = t;  ty = new ObjectType(); 
		} else if (la.kind == 3) {
			Get();
			tok = t;  gt = new List<Type/*!*/>(); 
			GenericInstantiation(gt);
			if (gt.Count != 1) {
			 SemErr("array type expects exactly one type argument");
			}
			int dims = 1;
			if (tok.val.Length != 5) {
			 dims = int.Parse(tok.val.Substring(5));
			}
			ty = theBuiltIns.ArrayType(tok, dims, gt[0], true);
			
		} else if (la.kind == 1) {
			Ident(out tok);
			gt = new List<Type/*!*/>();
			path = new List<IToken>(); 
			while (la.kind == 17) {
				path.Add(tok); 
				Get();
				Ident(out tok);
			}
			if (la.kind == 32) {
				GenericInstantiation(gt);
			}
			ty = new UserDefinedType(tok, tok.val, gt, path); 
		} else SynErr(162);
	}

	void FunctionSpec(List<Expression/*!*/>/*!*/ reqs, List<FrameExpression/*!*/>/*!*/ reads, List<Expression/*!*/>/*!*/ ens, List<Expression/*!*/> decreases, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(cce.NonNullElements(reqs));
		Contract.Requires(cce.NonNullElements(reads));
		Contract.Requires(decreases == null || cce.NonNullElements(decreases));
		Expression/*!*/ e;  FrameExpression/*!*/ fe; 
		if (la.kind == 39) {
			while (!(la.kind == 0 || la.kind == 39)) {SynErr(163); Get();}
			Get();
			Expression(out e, patterns);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(164); Get();}
			Expect(14);
			reqs.Add(e); 
		} else if (la.kind == 42) {
			Get();
			if (StartOf(14)) {
				PossiblyWildFrameExpression(out fe);
				reads.Add(fe); 
				while (la.kind == 24) {
					Get();
					PossiblyWildFrameExpression(out fe);
					reads.Add(fe); 
				}
			}
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(165); Get();}
			Expect(14);
		} else if (la.kind == 40) {
			Get();
			Expression(out e, null);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(166); Get();}
			Expect(14);
			ens.Add(e); 
		} else if (la.kind == 41) {
			Get();
			if (decreases == null) {
			 SemErr(t, "'decreases' clauses are meaningless for copredicates, so they are not allowed");
			 decreases = new List<Expression/*!*/>();
			}
			
			DecreasesList(decreases, false);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(167); Get();}
			Expect(14);
		} else SynErr(168);
	}

	void FunctionBody(out Expression/*!*/ e, out IToken bodyStart, out IToken bodyEnd, List<PatternExpression/*!*/>/*!*/ patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null); e = dummyExpr; 
		Expect(6);
		bodyStart = t; 
		Expression(out e, patterns);
		Expect(7);
		bodyEnd = t; 
	}

	void PossiblyWildFrameExpression(out FrameExpression/*!*/ fe) {
		Contract.Ensures(Contract.ValueAtReturn(out fe) != null); fe = dummyFrameExpr; 
		if (la.kind == 56) {
			Get();
			fe = new FrameExpression(t, new WildcardExpr(t), null); 
		} else if (StartOf(9)) {
			FrameExpression(out fe);
		} else SynErr(169);
	}

	void PossiblyWildExpression(out Expression/*!*/ e) {
		Contract.Ensures(Contract.ValueAtReturn(out e)!=null);
		e = dummyExpr; 
		if (la.kind == 56) {
			Get();
			e = new WildcardExpr(t); 
		} else if (StartOf(13)) {
			Expression(out e, null);
		} else SynErr(170);
	}

	void RegionConstructExpr(IToken/*!*/ regionToken, out Expression e, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(regionToken != null);
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		e = dummyExpr; 
		Expression/*!*/ region = dummyExpr;
		
		Expect(6);
		if (StartOf(13)) {
			Expression(out region, patterns);
		}
		Expect(7);
		e = new RegionConstructExpression(regionToken, region);
		
	}

	void Stmt(List<Statement/*!*/>/*!*/ ss) {
		Statement/*!*/ s;
		
		OneStmt(out s);
		ss.Add(s); 
	}

	void OneStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null); IToken/*!*/ x;  IToken/*!*/ id;  string label = null;
		s = dummyStmt;  /* to please the compiler */
		BlockStmt bs;
		IToken bodyStart, bodyEnd;
		int breakCount;
		
		while (!(StartOf(15))) {SynErr(171); Get();}
		switch (la.kind) {
		case 6: {
			BlockStmt(out bs, out bodyStart, out bodyEnd);
			s = bs; 
			break;
		}
		case 76: {
			AssertStmt(out s);
			break;
		}
		case 64: {
			AssumeStmt(out s);
			break;
		}
		case 77: {
			PrintStmt(out s);
			break;
		}
		case 1: case 2: case 22: case 26: case 106: case 107: case 108: case 109: case 110: case 111: case 112: {
			UpdateStmt(out s);
			break;
		}
		case 8: case 23: {
			VarDeclStatement(out s);
			break;
		}
		case 69: {
			IfStmt(out s);
			break;
		}
		case 73: {
			WhileStmt(out s);
			break;
		}
		case 75: {
			MatchStmt(out s);
			break;
		}
		case 78: {
			ParallelStmt(out s);
			break;
		}
		case 79: {
			CalcStmt(out s);
			break;
		}
		case 58: {
			Get();
			x = t; 
			NoUSIdent(out id);
			Expect(5);
			OneStmt(out s);
			s.Labels = new LList<Label>(new Label(x, id.val), s.Labels); 
			break;
		}
		case 59: {
			Get();
			x = t; breakCount = 1; label = null; 
			if (la.kind == 1) {
				NoUSIdent(out id);
				label = id.val; 
			} else if (la.kind == 14 || la.kind == 59) {
				while (la.kind == 59) {
					Get();
					breakCount++; 
				}
			} else SynErr(172);
			while (!(la.kind == 0 || la.kind == 14)) {SynErr(173); Get();}
			Expect(14);
			s = label != null ? new BreakStmt(x, label) : new BreakStmt(x, breakCount); 
			break;
		}
		case 43: case 62: {
			ReturnStmt(out s);
			break;
		}
		case 31: {
			SkeletonStmt(out s);
			Expect(14);
			break;
		}
		default: SynErr(174); break;
		}
	}

	void AssertStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null); IToken/*!*/ x;
		Expression e = null; Attributes attrs = null;
		
		Expect(76);
		x = t; 
		while (IsAttribute()) {
			Attribute(ref attrs);
		}
		if (StartOf(13)) {
			Expression(out e, null);
		} else if (la.kind == 31) {
			Get();
		} else SynErr(175);
		Expect(14);
		if (e == null) {
		 s = new SkeletonStatement(new AssertStmt(x, new LiteralExpr(x, true), attrs), true, false);
		} else {
		 s = new AssertStmt(x, e, attrs);
		}
		
	}

	void AssumeStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null); IToken/*!*/ x;
		Expression e = null; Attributes attrs = null;
		
		Expect(64);
		x = t; 
		while (IsAttribute()) {
			Attribute(ref attrs);
		}
		if (StartOf(13)) {
			Expression(out e, null);
		} else if (la.kind == 31) {
			Get();
		} else SynErr(176);
		if (e == null) {
		 s = new SkeletonStatement(new AssumeStmt(x, new LiteralExpr(x, true), attrs), true, false);
		} else {
		 s = new AssumeStmt(x, e, attrs);
		}
		
		Expect(14);
	}

	void PrintStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null); IToken/*!*/ x;  Attributes.Argument/*!*/ arg;
		List<Attributes.Argument/*!*/> args = new List<Attributes.Argument/*!*/>();
		
		Expect(77);
		x = t; 
		AttributeArg(out arg);
		args.Add(arg); 
		while (la.kind == 24) {
			Get();
			AttributeArg(out arg);
			args.Add(arg); 
		}
		Expect(14);
		s = new PrintStmt(x, args); 
	}

	void UpdateStmt(out Statement/*!*/ s) {
		List<Expression> lhss = new List<Expression>();
		List<AssignmentRhs> rhss = new List<AssignmentRhs>();
		Expression e;  AssignmentRhs r;
		Expression lhs0;
		IToken x;
		Attributes attrs = null;
		IToken suchThatAssume = null;
		Expression suchThat = null;
		
		Lhs(out e);
		x = e.tok; 
		if (la.kind == 6 || la.kind == 14) {
			while (la.kind == 6) {
				Attribute(ref attrs);
			}
			Expect(14);
			rhss.Add(new ExprRhs(e, attrs)); 
		} else if (la.kind == 24 || la.kind == 61 || la.kind == 63) {
			lhss.Add(e);  lhs0 = e; 
			while (la.kind == 24) {
				Get();
				Lhs(out e);
				lhss.Add(e); 
			}
			if (la.kind == 61) {
				Get();
				x = t; 
				Rhs(out r, lhs0);
				rhss.Add(r); 
				while (la.kind == 24) {
					Get();
					Rhs(out r, lhs0);
					rhss.Add(r); 
				}
			} else if (la.kind == 63) {
				Get();
				x = t; 
				if (la.kind == 64) {
					Get();
					suchThatAssume = t; 
				}
				Expression(out suchThat, null);
			} else SynErr(177);
			Expect(14);
		} else if (la.kind == 5) {
			Get();
			SemErr(t, "invalid statement (did you forget the 'label' keyword?)"); 
		} else SynErr(178);
		if (suchThat != null) {
		 s = new AssignSuchThatStmt(x, lhss, suchThat, suchThatAssume);
		} else {
		 if (lhss.Count == 0 && rhss.Count == 0) {
		   s = new BlockStmt(x, new List<Statement>()); // error, give empty statement
		 } else {
		   s = new UpdateStmt(x, lhss, rhss);
		 }
		}
		
	}

	void VarDeclStatement(out Statement/*!*/ s) {
		IToken x = null, assignTok = null;  bool isGhost = false;
		VarDecl/*!*/ d;
		AssignmentRhs r;  IdentifierExpr lhs0;
		List<VarDecl> lhss = new List<VarDecl>();
		List<AssignmentRhs> rhss = new List<AssignmentRhs>();
		IToken suchThatAssume = null;
		Expression suchThat = null;
		
		if (la.kind == 8) {
			Get();
			isGhost = true;  x = t; 
		}
		Expect(23);
		if (!isGhost) { x = t; } 
		LocalIdentTypeOptional(out d, isGhost);
		lhss.Add(d); 
		while (la.kind == 24) {
			Get();
			LocalIdentTypeOptional(out d, isGhost);
			lhss.Add(d); 
		}
		if (la.kind == 61 || la.kind == 63) {
			if (la.kind == 61) {
				Get();
				assignTok = t;
				lhs0 = new IdentifierExpr(lhss[0].Tok, lhss[0].Name);
				lhs0.Var = lhss[0];  lhs0.Type = lhss[0].OptionalType;  // resolve here
				
				Rhs(out r, lhs0);
				rhss.Add(r); 
				while (la.kind == 24) {
					Get();
					Rhs(out r, lhs0);
					rhss.Add(r); 
				}
			} else {
				Get();
				assignTok = t; 
				if (la.kind == 64) {
					Get();
					suchThatAssume = t; 
				}
				Expression(out suchThat, null);
			}
		}
		Expect(14);
		ConcreteUpdateStatement update;
		if (suchThat != null) {
		 var ies = new List<Expression>();
		 foreach (var lhs in lhss) {
		   ies.Add(new IdentifierExpr(lhs.Tok, lhs.Name));
		 }
		 update = new AssignSuchThatStmt(assignTok, ies, suchThat, suchThatAssume);
		} else if (rhss.Count == 0) {
		 update = null;
		} else {
		 var ies = new List<Expression>();
		 foreach (var lhs in lhss) {
		   ies.Add(new AutoGhostIdentifierExpr(lhs.Tok, lhs.Name));
		 }
		 update = new UpdateStmt(assignTok, ies, rhss);
		}
		s = new VarDeclStmt(x, lhss, update);
		
	}

	void IfStmt(out Statement/*!*/ ifStmt) {
		Contract.Ensures(Contract.ValueAtReturn(out ifStmt) != null); IToken/*!*/ x;
		Expression guard = null;  bool guardOmitted = false;
		BlockStmt/*!*/ thn;
		BlockStmt/*!*/ bs;
		Statement/*!*/ s;
		Statement els = null;
		IToken bodyStart, bodyEnd;
		List<GuardedAlternative> alternatives;
		ifStmt = dummyStmt;  // to please the compiler
		
		Expect(69);
		x = t; 
		if (la.kind == 26 || la.kind == 31) {
			if (la.kind == 26) {
				Guard(out guard);
			} else {
				Get();
				guardOmitted = true; 
			}
			BlockStmt(out thn, out bodyStart, out bodyEnd);
			if (la.kind == 70) {
				Get();
				if (la.kind == 69) {
					IfStmt(out s);
					els = s; 
				} else if (la.kind == 6) {
					BlockStmt(out bs, out bodyStart, out bodyEnd);
					els = bs; 
				} else SynErr(179);
			}
			if (guardOmitted) {
			 ifStmt = new SkeletonStatement(new IfStmt(x, guard, thn, els), true, false);
			} else {
			 ifStmt = new IfStmt(x, guard, thn, els);
			}
			
		} else if (la.kind == 6) {
			AlternativeBlock(out alternatives);
			ifStmt = new AlternativeStmt(x, alternatives); 
		} else SynErr(180);
	}

	void WhileStmt(out Statement/*!*/ stmt) {
		Contract.Ensures(Contract.ValueAtReturn(out stmt) != null); IToken/*!*/ x;
		Expression guard = null;  bool guardOmitted = false;
		List<MaybeFreeExpression/*!*/> invariants = new List<MaybeFreeExpression/*!*/>();
		List<Expression/*!*/> decreases = new List<Expression/*!*/>();
		Attributes decAttrs = null;
		Attributes modAttrs = null;
		List<FrameExpression/*!*/> mod = null;
		BlockStmt/*!*/ body = null;  bool bodyOmitted = false;
		IToken bodyStart = null, bodyEnd = null;
		List<GuardedAlternative> alternatives;
		stmt = dummyStmt;  // to please the compiler
		
		Expect(73);
		x = t; 
		if (la.kind == 26 || la.kind == 31) {
			if (la.kind == 26) {
				Guard(out guard);
				Contract.Assume(guard == null || cce.Owner.None(guard)); 
			} else {
				Get();
				guardOmitted = true; 
			}
			LoopSpec(out invariants, out decreases, out mod, ref decAttrs, ref modAttrs);
			if (la.kind == 6) {
				BlockStmt(out body, out bodyStart, out bodyEnd);
			} else if (la.kind == 31) {
				Get();
				bodyOmitted = true; 
			} else SynErr(181);
			if (guardOmitted || bodyOmitted) {
			 if (mod != null) {
			   SemErr(mod[0].E.tok, "'modifies' clauses are not allowed on refining loops");
			 }
			 if (body == null) {
			   body = new BlockStmt(x, new List<Statement>());
			 }
			 stmt = new WhileStmt(x, guard, invariants, new Specification<Expression>(decreases, decAttrs), new Specification<FrameExpression>(null, null), body);
			 stmt = new SkeletonStatement(stmt, guardOmitted, bodyOmitted);
			} else {
			 // The following statement protects against crashes in case of parsing errors
			 body = body ?? new BlockStmt(x, new List<Statement>());
			 stmt = new WhileStmt(x, guard, invariants, new Specification<Expression>(decreases, decAttrs), new Specification<FrameExpression>(mod, modAttrs), body);
			}
			
		} else if (StartOf(16)) {
			LoopSpec(out invariants, out decreases, out mod, ref decAttrs, ref modAttrs);
			AlternativeBlock(out alternatives);
			stmt = new AlternativeLoopStmt(x, invariants, new Specification<Expression>(decreases, decAttrs), new Specification<FrameExpression>(mod, modAttrs), alternatives); 
		} else SynErr(182);
	}

	void MatchStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null);
		Token x;  Expression/*!*/ e;  MatchCaseStmt/*!*/ c;
		List<MatchCaseStmt/*!*/> cases = new List<MatchCaseStmt/*!*/>(); 
		Expect(75);
		x = t; 
		Expression(out e, null);
		Expect(6);
		while (la.kind == 71) {
			CaseStatement(out c);
			cases.Add(c); 
		}
		Expect(7);
		s = new MatchStmt(x, e, cases); 
	}

	void ParallelStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null);
		IToken/*!*/ x;
		List<BoundVar/*!*/> bvars = null;
		Attributes attrs = null;
		Expression range = null;
		var ens = new List<MaybeFreeExpression/*!*/>();
		bool isFree;
		Expression/*!*/ e;
		BlockStmt/*!*/ block;
		IToken bodyStart, bodyEnd;
		
		Expect(78);
		x = t; 
		Expect(26);
		if (la.kind == 1) {
			List<BoundVar/*!*/> bvarsX;  Attributes attrsX;  Expression rangeX; 
			QuantifierDomain(out bvarsX, out attrsX, out rangeX);
			bvars = bvarsX; attrs = attrsX; range = rangeX;
			
		}
		if (bvars == null) { bvars = new List<BoundVar>(); }
		if (range == null) { range = new LiteralExpr(x, true); }
		
		Expect(28);
		while (la.kind == 38 || la.kind == 40) {
			isFree = false; 
			if (la.kind == 38) {
				Get();
				isFree = true; 
			}
			Expect(40);
			Expression(out e, null);
			Expect(14);
			ens.Add(new MaybeFreeExpression(e, isFree)); 
		}
		BlockStmt(out block, out bodyStart, out bodyEnd);
		s = new ParallelStmt(x, bvars, attrs, range, ens, block); 
	}

	void CalcStmt(out Statement/*!*/ s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null);
		Token x;
		BinaryExpr.Opcode op, calcOp = BinaryExpr.Opcode.Eq, resOp = BinaryExpr.Opcode.Eq;     
		var lines = new List<Expression/*!*/>();
		var hints = new List<BlockStmt/*!*/>(); 
		var customOps = new List<BinaryExpr.Opcode?>();
		BinaryExpr.Opcode? maybeOp;
		Expression/*!*/ e;
		BlockStmt/*!*/ h;
		IToken opTok;
		
		Expect(79);
		x = t; 
		if (StartOf(17)) {
			CalcOp(out opTok, out calcOp);
			maybeOp = Microsoft.Dafny.CalcStmt.ResultOp(calcOp, calcOp); // guard against non-trasitive calcOp (like !=)
			if (maybeOp == null) {
			 SemErr(opTok, "the main operator of a calculation must be transitive");
			}
			resOp = calcOp; 
			
		}
		Expect(6);
		if (StartOf(13)) {
			Expression(out e, null);
			lines.Add(e); 
			Expect(14);
			while (StartOf(18)) {
				Hint(out h);
				hints.Add(h); 
				if (StartOf(17)) {
					CalcOp(out opTok, out op);
					maybeOp = Microsoft.Dafny.CalcStmt.ResultOp(resOp, op);
					if (maybeOp == null) {
					 customOps.Add(null); // pretend the operator was not there to satisfy the precondition of the CalcStmt contructor
					 SemErr(opTok, "this operator cannot continue this calculation");
					} else {
					 customOps.Add(op);
					 resOp = (BinaryExpr.Opcode)maybeOp;                                                              
					}
					
				} else if (StartOf(13)) {
					customOps.Add(null); 
				} else SynErr(183);
				Expression(out e, null);
				lines.Add(e); 
				Expect(14);
			}
		}
		Expect(7);
		s = new CalcStmt(x, calcOp, lines, hints, customOps); 
	}

	void ReturnStmt(out Statement/*!*/ s) {
		IToken returnTok = null;
		List<AssignmentRhs> rhss = null;
		AssignmentRhs r;
		bool isYield = false;
		
		if (la.kind == 62) {
			Get();
			returnTok = t; 
		} else if (la.kind == 43) {
			Get();
			returnTok = t; isYield = true; 
		} else SynErr(184);
		if (StartOf(19)) {
			Rhs(out r, null);
			rhss = new List<AssignmentRhs>(); rhss.Add(r); 
			while (la.kind == 24) {
				Get();
				Rhs(out r, null);
				rhss.Add(r); 
			}
		}
		Expect(14);
		if (isYield) {
		 s = new YieldStmt(returnTok, rhss);
		} else {
		 s = new ReturnStmt(returnTok, rhss);
		}
		
	}

	void SkeletonStmt(out Statement s) {
		List<IToken> names = null;
		List<Expression> exprs = null;
		IToken tok, dotdotdot, whereTok;
		Expression e; 
		Expect(31);
		dotdotdot = t; 
		if (la.kind == 60) {
			Get();
			names = new List<IToken>(); exprs = new List<Expression>(); whereTok = t;
			Ident(out tok);
			names.Add(tok); 
			while (la.kind == 24) {
				Get();
				Ident(out tok);
				names.Add(tok); 
			}
			Expect(61);
			Expression(out e, null);
			exprs.Add(e); 
			while (la.kind == 24) {
				Get();
				Expression(out e, null);
				exprs.Add(e); 
			}
			if (exprs.Count != names.Count) {
			 SemErr(whereTok, exprs.Count < names.Count ? "not enough expressions" : "too many expressions");
			 names = null; exprs = null;
			}
			
		}
		s = new SkeletonStatement(dotdotdot, names, exprs); 
	}

	void Rhs(out AssignmentRhs r, Expression receiverForInitCall) {
		Contract.Ensures(Contract.ValueAtReturn<AssignmentRhs>(out r) != null);
		IToken/*!*/ x, newToken;  Expression/*!*/ e;
		List<Expression> ee = null;
		Type ty = null;
		CallStmt initCall = null;
		List<Expression> args;
		r = dummyRhs;  // to please compiler
		Attributes attrs = null;
		
		if (la.kind == 65) {
			Get();
			newToken = t; 
			TypeAndToken(out x, out ty);
			if (la.kind == 17 || la.kind == 26 || la.kind == 66) {
				if (la.kind == 66) {
					Get();
					ee = new List<Expression>(); 
					Expressions(ee, null);
					Expect(67);
					UserDefinedType tmp = theBuiltIns.ArrayType(x, ee.Count, new IntType(), true);
					
				} else if (la.kind == 17) {
					Get();
					Ident(out x);
					Expect(26);
					args = new List<Expression/*!*/>(); 
					if (StartOf(13)) {
						Expressions(args, null);
					}
					Expect(28);
					initCall = new CallStmt(x, new List<Expression>(), receiverForInitCall, x.val, args); 
				} else {
					Get();
					var udf = ty as UserDefinedType;
					if (udf != null && 0 < udf.Path.Count && udf.TypeArgs.Count == 0) {
					 // The parsed name had the form "A.B.Ctr", so treat "A.B" as the name of the type and "Ctr" as
					 // the name of the constructor that's being invoked.
					 x = udf.tok;
					 ty = new UserDefinedType(udf.Path[0], udf.Path[udf.Path.Count-1].val, new List<Type>(), udf.Path.GetRange(0,udf.Path.Count-1));
					} else {
					 SemErr(t, "expected '.'");
					 x = null;
					}
					args = new List<Expression/*!*/>(); 
					if (StartOf(13)) {
						Expressions(args, null);
					}
					Expect(28);
					if (x != null) {
					 initCall = new CallStmt(x, new List<Expression>(), receiverForInitCall, x.val, args);
					}
					
				}
			}
			if (ee != null) {
			 r = new TypeRhs(newToken, ty, ee);
			} else {
			 r = new TypeRhs(newToken, ty, initCall);
			}
			
		} else if (la.kind == 68) {
			Get();
			x = t; 
			Expression(out e, null);
			r = new ExprRhs(new UnaryExpr(x, UnaryExpr.Opcode.SetChoose, e)); 
		} else if (la.kind == 56) {
			Get();
			r = new HavocRhs(t); 
		} else if (StartOf(13)) {
			Expression(out e, null);
			r = new ExprRhs(e); 
		} else SynErr(185);
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		r.Attributes = attrs; 
	}

	void Lhs(out Expression e) {
		e = dummyExpr;  // the assignment is to please the compiler, the dummy value to satisfy contracts in the event of a parse error
		
		if (la.kind == 1) {
			DottedIdentifiersAndFunction(out e, null);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
		} else if (StartOf(20)) {
			ConstAtomExpression(out e, null);
			Suffix(ref e);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
		} else SynErr(186);
	}

	void Expressions(List<Expression/*!*/>/*!*/ args, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(cce.NonNullElements(args)); Expression/*!*/ e; 
		Expression(out e, patterns);
		args.Add(e); 
		while (la.kind == 24) {
			Get();
			Expression(out e, patterns);
			args.Add(e); 
		}
	}

	void Guard(out Expression e) {
		Expression/*!*/ ee;  e = null; 
		Expect(26);
		if (la.kind == 56) {
			Get();
			e = null; 
		} else if (StartOf(13)) {
			Expression(out ee, null);
			e = ee; 
		} else SynErr(187);
		Expect(28);
	}

	void AlternativeBlock(out List<GuardedAlternative> alternatives) {
		alternatives = new List<GuardedAlternative>();
		IToken x;
		Expression e;
		List<Statement> body;
		
		Expect(6);
		while (la.kind == 71) {
			Get();
			x = t; 
			Expression(out e, null);
			Expect(72);
			body = new List<Statement>(); 
			while (StartOf(11)) {
				Stmt(body);
			}
			alternatives.Add(new GuardedAlternative(x, e, body)); 
		}
		Expect(7);
	}

	void LoopSpec(out List<MaybeFreeExpression/*!*/> invariants, out List<Expression/*!*/> decreases, out List<FrameExpression/*!*/> mod, ref Attributes decAttrs, ref Attributes modAttrs) {
		FrameExpression/*!*/ fe; 
		invariants = new List<MaybeFreeExpression/*!*/>();
		MaybeFreeExpression invariant = null;
		decreases = new List<Expression/*!*/>();
		mod = null;
		
		while (StartOf(21)) {
			if (la.kind == 38 || la.kind == 74) {
				Invariant(out invariant);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(188); Get();}
				Expect(14);
				invariants.Add(invariant); 
			} else if (la.kind == 41) {
				while (!(la.kind == 0 || la.kind == 41)) {SynErr(189); Get();}
				Get();
				while (IsAttribute()) {
					Attribute(ref decAttrs);
				}
				DecreasesList(decreases, true);
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(190); Get();}
				Expect(14);
			} else {
				while (!(la.kind == 0 || la.kind == 37)) {SynErr(191); Get();}
				Get();
				while (IsAttribute()) {
					Attribute(ref modAttrs);
				}
				mod = mod ?? new List<FrameExpression>(); 
				if (StartOf(9)) {
					FrameExpression(out fe);
					mod.Add(fe); 
					while (la.kind == 24) {
						Get();
						FrameExpression(out fe);
						mod.Add(fe); 
					}
				}
				while (!(la.kind == 0 || la.kind == 14)) {SynErr(192); Get();}
				Expect(14);
			}
		}
	}

	void Invariant(out MaybeFreeExpression/*!*/ invariant) {
		bool isFree = false; Expression/*!*/ e; List<string> ids = new List<string>(); invariant = null; Attributes attrs = null; 
		while (!(la.kind == 0 || la.kind == 38 || la.kind == 74)) {SynErr(193); Get();}
		if (la.kind == 38) {
			Get();
			isFree = true; 
		}
		Expect(74);
		while (IsAttribute()) {
			Attribute(ref attrs);
		}
		Expression(out e, null);
		invariant = new MaybeFreeExpression(e, isFree, attrs); 
	}

	void CaseStatement(out MatchCaseStmt/*!*/ c) {
		Contract.Ensures(Contract.ValueAtReturn(out c) != null);
		IToken/*!*/ x, id;
		List<BoundVar/*!*/> arguments = new List<BoundVar/*!*/>();
		BoundVar/*!*/ bv;
		List<Statement/*!*/> body = new List<Statement/*!*/>();
		
		Expect(71);
		x = t; 
		Ident(out id);
		if (la.kind == 26) {
			Get();
			IdentTypeOptional(out bv);
			arguments.Add(bv); 
			while (la.kind == 24) {
				Get();
				IdentTypeOptional(out bv);
				arguments.Add(bv); 
			}
			Expect(28);
		}
		Expect(72);
		while (StartOf(11)) {
			Stmt(body);
		}
		c = new MatchCaseStmt(x, id.val, arguments, body); 
	}

	void AttributeArg(out Attributes.Argument/*!*/ arg) {
		Contract.Ensures(Contract.ValueAtReturn(out arg) != null); Expression/*!*/ e;  arg = dummyAttrArg; 
		if (la.kind == 4) {
			Get();
			arg = new Attributes.Argument(t, t.val.Substring(1, t.val.Length-2)); 
		} else if (StartOf(13)) {
			Expression(out e, null);
			arg = new Attributes.Argument(t, e); 
		} else SynErr(194);
	}

	void QuantifierDomain(out List<BoundVar/*!*/> bvars, out Attributes attrs, out Expression range) {
		bvars = new List<BoundVar/*!*/>();
		BoundVar/*!*/ bv;
		attrs = null;
		range = null;
		
		IdentTypeOptional(out bv);
		bvars.Add(bv); 
		while (la.kind == 24) {
			Get();
			IdentTypeOptional(out bv);
			bvars.Add(bv); 
		}
		while (la.kind == 6) {
			Attribute(ref attrs);
		}
		if (la.kind == 22) {
			Get();
			Expression(out range, null);
		}
	}

	void CalcOp(out IToken x, out BinaryExpr.Opcode/*!*/ op) {
		Contract.Ensures(Microsoft.Dafny.CalcStmt.ValidOp(Contract.ValueAtReturn(out op)));
		op = BinaryExpr.Opcode.Eq; // Returns Eq if parsing fails because it is compatible with any other operator 
		x = null;
		
		switch (la.kind) {
		case 27: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Eq; 
			break;
		}
		case 32: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Lt; 
			break;
		}
		case 33: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Gt; 
			break;
		}
		case 80: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Le; 
			break;
		}
		case 81: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Ge; 
			break;
		}
		case 82: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Neq; 
			break;
		}
		case 83: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Neq; 
			break;
		}
		case 84: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Le; 
			break;
		}
		case 85: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Ge; 
			break;
		}
		case 87: case 88: {
			EquivOp();
			x = t;  op = BinaryExpr.Opcode.Iff; 
			break;
		}
		case 89: case 90: {
			ImpliesOp();
			x = t;  op = BinaryExpr.Opcode.Imp; 
			break;
		}
		case 86: {
			Get();
			x = t;  op = BinaryExpr.Opcode.SepConj; 
			break;
		}
		default: SynErr(195); break;
		}
	}

	void Hint(out BlockStmt s) {
		Contract.Ensures(Contract.ValueAtReturn(out s) != null); // returns an empty block statement if the hint is empty
		var subhints = new List<Statement/*!*/>(); 
		IToken bodyStart, bodyEnd;
		BlockStmt/*!*/ block;
		Statement/*!*/ calc;
		Token x = la;
		
		while (la.kind == 6 || la.kind == 79) {
			if (la.kind == 6) {
				BlockStmt(out block, out bodyStart, out bodyEnd);
				subhints.Add(block); 
			} else {
				CalcStmt(out calc);
				subhints.Add(calc); 
			}
		}
		s = new BlockStmt(x, subhints); // if the hint is empty x is the first token of the next line, but it doesn't matter cause the block statement is just used as a container 
		
	}

	void EquivOp() {
		if (la.kind == 87) {
			Get();
		} else if (la.kind == 88) {
			Get();
		} else SynErr(196);
	}

	void ImpliesOp() {
		if (la.kind == 89) {
			Get();
		} else if (la.kind == 90) {
			Get();
		} else SynErr(197);
	}

	void EquivExpression(out Expression/*!*/ e0, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e0) != null); IToken/*!*/ x;  Expression/*!*/ e1; 
		ImpliesExpression(out e0, patterns );
		while (la.kind == 87 || la.kind == 88) {
			EquivOp();
			x = t; 
			ImpliesExpression(out e1, patterns);
			e0 = new BinaryExpr(x, BinaryExpr.Opcode.Iff, e0, e1); 
		}
	}

	void ImpliesExpression(out Expression/*!*/ e0, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e0) != null); IToken/*!*/ x;  Expression/*!*/ e1; 
		LogicalExpression(out e0, patterns);
		if (la.kind == 89 || la.kind == 90) {
			ImpliesOp();
			x = t; 
			ImpliesExpression(out e1, patterns);
			e0 = new BinaryExpr(x, BinaryExpr.Opcode.Imp, e0, e1); 
		}
	}

	void LogicalExpression(out Expression/*!*/ e0, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e0) != null); IToken/*!*/ x;  Expression/*!*/ e1; 
		RelationalExpression(out e0, patterns);
		if (StartOf(22)) {
			if (la.kind == 91 || la.kind == 92) {
				AndOp();
				x = t; 
				RelationalExpression(out e1, patterns);
				e0 = new BinaryExpr(x, BinaryExpr.Opcode.And, e0, e1); 
				while (la.kind == 91 || la.kind == 92) {
					AndOp();
					x = t; 
					RelationalExpression(out e1, patterns);
					e0 = new BinaryExpr(x, BinaryExpr.Opcode.And, e0, e1); 
				}
			} else {
				OrOp();
				x = t; 
				RelationalExpression(out e1, patterns);
				e0 = new BinaryExpr(x, BinaryExpr.Opcode.Or, e0, e1); 
				while (la.kind == 93 || la.kind == 94) {
					OrOp();
					x = t; 
					RelationalExpression(out e1, patterns);
					e0 = new BinaryExpr(x, BinaryExpr.Opcode.Or, e0, e1); 
				}
			}
		}
	}

	void RelationalExpression(out Expression/*!*/ e, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		IToken x, firstOpTok = null;  Expression e0, e1, acc = null;  BinaryExpr.Opcode op;
		List<Expression> chain = null;
		List<BinaryExpr.Opcode> ops = null;
		int kind = 0;  // 0 ("uncommitted") indicates chain of ==, possibly with one !=
		              // 1 ("ascending")   indicates chain of ==, <, <=, possibly with one !=
		              // 2 ("descending")  indicates chain of ==, >, >=, possibly with one !=
		              // 3 ("illegal")     indicates illegal chain
		              // 4 ("disjoint")    indicates chain of disjoint set operators
		bool hasSeenNeq = false;
		
		Term(out e0, patterns);
		e = e0; 
		if (StartOf(23)) {
			RelOp(out x, out op);
			firstOpTok = x; 
			Term(out e1, patterns);
			e = new BinaryExpr(x, op, e0, e1);
			  if (op == BinaryExpr.Opcode.Disjoint)
			    acc = new BinaryExpr(x, BinaryExpr.Opcode.Add, e0, e1); // accumulate first two operands.
			
			while (StartOf(23)) {
				if (chain == null) {
				 chain = new List<Expression>();
				 ops = new List<BinaryExpr.Opcode>();
				 chain.Add(e0);  ops.Add(op);  chain.Add(e1);
				 switch (op) {
				   case BinaryExpr.Opcode.Eq:
				     kind = 0;  break;
				   case BinaryExpr.Opcode.Neq:
				     kind = 0;  hasSeenNeq = true;  break;
				   case BinaryExpr.Opcode.Lt:
				   case BinaryExpr.Opcode.Le:
				     kind = 1;  break;
				   case BinaryExpr.Opcode.Gt:
				   case BinaryExpr.Opcode.Ge:
				     kind = 2;  break;
				   case BinaryExpr.Opcode.Disjoint:
				     kind = 4;  break;
				   default:
				     kind = 3;  break;
				 }
				}
				e0 = e1;
				
				RelOp(out x, out op);
				switch (op) {
				 case BinaryExpr.Opcode.Eq:
				   if (kind != 0 && kind != 1 && kind != 2) { SemErr(x, "chaining not allowed from the previous operator"); }
				   break;
				 case BinaryExpr.Opcode.Neq:
				   if (hasSeenNeq) { SemErr(x, "a chain cannot have more than one != operator"); }
				   if (kind != 0 && kind != 1 && kind != 2) { SemErr(x, "this operator cannot continue this chain"); }
				   hasSeenNeq = true;  break;
				 case BinaryExpr.Opcode.Lt:
				 case BinaryExpr.Opcode.Le:
				   if (kind == 0) { kind = 1; }
				   else if (kind != 1) { SemErr(x, "this operator chain cannot continue with an ascending operator"); }
				   break;
				 case BinaryExpr.Opcode.Gt:
				 case BinaryExpr.Opcode.Ge:
				   if (kind == 0) { kind = 2; }
				   else if (kind != 2) { SemErr(x, "this operator chain cannot continue with a descending operator"); }
				   break;
				 case BinaryExpr.Opcode.Disjoint:
				   if (kind != 4) { SemErr(x, "can only chain disjoint (!!) with itself."); kind = 3; }
				   break;
				 default:
				   SemErr(x, "this operator cannot be part of a chain");
				   kind = 3;  break;
				}
				
				Term(out e1, patterns);
				ops.Add(op); chain.Add(e1);
				if (op == BinaryExpr.Opcode.Disjoint) {
				 e = new BinaryExpr(x, BinaryExpr.Opcode.And, e, new BinaryExpr(x, op, acc, e1));
				 acc = new BinaryExpr(x, BinaryExpr.Opcode.Add, acc, e1); //e0 has already been added.
				}
				else
				 e = new BinaryExpr(x, BinaryExpr.Opcode.And, e, new BinaryExpr(x, op, e0, e1));
				
			}
		}
		if (chain != null) {
		 e = new ChainingExpression(firstOpTok, chain, ops, e);
		}
		
	}

	void AndOp() {
		if (la.kind == 91) {
			Get();
		} else if (la.kind == 92) {
			Get();
		} else SynErr(198);
	}

	void OrOp() {
		if (la.kind == 93) {
			Get();
		} else if (la.kind == 94) {
			Get();
		} else SynErr(199);
	}

	void Term(out Expression/*!*/ e0, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e0) != null); IToken/*!*/ x;  Expression/*!*/ e1;  BinaryExpr.Opcode op; 
		Factor(out e0, patterns);
		while (la.kind == 98 || la.kind == 99) {
			AddOp(out x, out op);
			Factor(out e1, patterns);
			e0 = new BinaryExpr(x, op, e0, e1); 
		}
	}

	void RelOp(out IToken/*!*/ x, out BinaryExpr.Opcode op) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null);
		x = Token.NoToken;  op = BinaryExpr.Opcode.Add/*(dummy)*/;
		IToken y;
		
		switch (la.kind) {
		case 27: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Eq; 
			break;
		}
		case 32: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Lt; 
			break;
		}
		case 33: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Gt; 
			break;
		}
		case 80: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Le; 
			break;
		}
		case 81: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Ge; 
			break;
		}
		case 82: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Neq; 
			break;
		}
		case 95: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Disjoint; 
			break;
		}
		case 96: {
			Get();
			x = t;  op = BinaryExpr.Opcode.In; 
			break;
		}
		case 97: {
			Get();
			x = t;  y = Token.NoToken; 
			if (la.kind == 96) {
				Get();
				y = t; 
			}
			if (y == Token.NoToken) {
			 SemErr(x, "invalid RelOp");
			} else if (y.pos != x.pos + 1) {
			 SemErr(x, "invalid RelOp (perhaps you intended \"!in\" with no intervening whitespace?)");
			} else {
			 x.val = "!in";
			 op = BinaryExpr.Opcode.NotIn;
			}
			
			break;
		}
		case 83: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Neq; 
			break;
		}
		case 84: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Le; 
			break;
		}
		case 85: {
			Get();
			x = t;  op = BinaryExpr.Opcode.Ge; 
			break;
		}
		case 86: {
			Get();
			x = t;  op = BinaryExpr.Opcode.SepConj; 
			break;
		}
		default: SynErr(200); break;
		}
	}

	void Factor(out Expression/*!*/ e0,  List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e0) != null); IToken/*!*/ x;  Expression/*!*/ e1;  BinaryExpr.Opcode op; 
		UnaryExpression(out e0, patterns);
		while (la.kind == 56 || la.kind == 100 || la.kind == 101) {
			MulOp(out x, out op);
			UnaryExpression(out e1, patterns);
			e0 = new BinaryExpr(x, op, e0, e1); 
		}
	}

	void AddOp(out IToken/*!*/ x, out BinaryExpr.Opcode op) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null); x = Token.NoToken;  op=BinaryExpr.Opcode.Add/*(dummy)*/; 
		if (la.kind == 98) {
			Get();
			x = t;  op = BinaryExpr.Opcode.Add; 
		} else if (la.kind == 99) {
			Get();
			x = t;  op = BinaryExpr.Opcode.Sub; 
		} else SynErr(201);
	}

	void UnaryExpression(out Expression/*!*/ e,  List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null); IToken/*!*/ x;  e = dummyExpr; 
		switch (la.kind) {
		case 99: {
			Get();
			x = t; 
			UnaryExpression(out e, patterns);
			e = new BinaryExpr(x, BinaryExpr.Opcode.Sub, new LiteralExpr(x, 0), e); 
			break;
		}
		case 97: case 105: {
			NegOp();
			x = t; 
			UnaryExpression(out e, patterns);
			e = new UnaryExpr(x, UnaryExpr.Opcode.Not, e); 
			break;
		}
		case 23: case 47: case 58: case 64: case 69: case 75: case 76: case 117: case 118: case 119: case 120: {
			EndlessExpression(out e, patterns);
			break;
		}
		case 1: {
			DottedIdentifiersAndFunction(out e, patterns);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
			break;
		}
		case 6: case 66: {
			DisplayExpr(out e);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
			break;
		}
		case 48: {
			MultiSetExpr(out e);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
			break;
		}
		case 50: {
			Get();
			x = t; 
			if (la.kind == 66) {
				MapDisplayExpr(x, out e);
				while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
					Suffix(ref e);
				}
			} else if (la.kind == 1) {
				MapComprehensionExpr(x, out e);
			} else if (StartOf(24)) {
				SemErr("map must be followed by literal in brackets or comprehension."); 
			} else SynErr(202);
			break;
		}
		case 51: {
			Get();
			x = t; 
			RegionConstructExpr(x, out e, patterns);
			break;
		}
		case 102: {
			Get();
			x = t; 
			RegionFilterExpr(x, out e, patterns);
			break;
		}
		case 103: {
			Get();
			x = t; 
			FootprintExpr(x, out e, patterns);
			break;
		}
		case 104: {
			Get();
			x = t; 
			BuiltInFunctionExpr(x, out e);
			break;
		}
		case 2: case 22: case 26: case 106: case 107: case 108: case 109: case 110: case 111: case 112: {
			ConstAtomExpression(out e, patterns);
			while (la.kind == 1 || la.kind == 17 || la.kind == 66) {
				Suffix(ref e);
			}
			break;
		}
		default: SynErr(203); break;
		}
	}

	void MulOp(out IToken/*!*/ x, out BinaryExpr.Opcode op) {
		Contract.Ensures(Contract.ValueAtReturn(out x) != null); x = Token.NoToken;  op = BinaryExpr.Opcode.Add/*(dummy)*/; 
		if (la.kind == 56) {
			Get();
			x = t;  op = BinaryExpr.Opcode.Mul; 
		} else if (la.kind == 100) {
			Get();
			x = t;  op = BinaryExpr.Opcode.Div; 
		} else if (la.kind == 101) {
			Get();
			x = t;  op = BinaryExpr.Opcode.Mod; 
		} else SynErr(204);
	}

	void NegOp() {
		if (la.kind == 97) {
			Get();
		} else if (la.kind == 105) {
			Get();
		} else SynErr(205);
	}

	void EndlessExpression(out Expression e, List<PatternExpression/*!*/> patterns) {
		IToken/*!*/ x;
		Expression e0, e1;
		e = dummyExpr;
		
		switch (la.kind) {
		case 69: {
			Get();
			x = t; 
			Expression(out e, patterns);
			Expect(115);
			Expression(out e0, patterns);
			Expect(70);
			Expression(out e1, patterns);
			e = new ITEExpr(x, e, e0, e1); 
			break;
		}
		case 75: {
			MatchExpression(out e, patterns);
			break;
		}
		case 117: case 118: case 119: case 120: {
			QuantifierGuts(out e);
			break;
		}
		case 47: {
			ComprehensionExpr(out e);
			break;
		}
		case 76: {
			Get();
			x = t; 
			Expression(out e0, patterns);
			Expect(14);
			Expression(out e1, patterns);
			e = new AssertExpr(x, e0, e1); 
			break;
		}
		case 64: {
			Get();
			x = t; 
			Expression(out e0, patterns);
			Expect(14);
			Expression(out e1, patterns);
			e = new AssumeExpr(x, e0, e1); 
			break;
		}
		case 23: {
			LetExpr(out e, patterns);
			break;
		}
		case 58: {
			NamedExpr(out e, patterns);
			break;
		}
		default: SynErr(206); break;
		}
	}

	void DottedIdentifiersAndFunction(out Expression e, List<PatternExpression/*!*/> patterns) {
		IToken id;  IToken openParen = null;
		List<Expression> args = null;
		List<IToken> idents = new List<IToken>();
		
		Ident(out id);
		idents.Add(id); 
		while (la.kind == 17) {
			Get();
			if (la.kind == 1) {
				Ident(out id);
				idents.Add(id); 
			} else if (la.kind == 56) {
				Get();
				idents.Add(t); 
			} else SynErr(207);
		}
		if (la.kind == 26) {
			Get();
			openParen = t;  args = new List<Expression>(); 
			if (StartOf(13)) {
				Expressions(args, patterns);
			}
			Expect(28);
		}
		e = new IdentifierSequence(idents, openParen, args); 
	}

	void Suffix(ref Expression/*!*/ e) {
		Contract.Requires(e != null); Contract.Ensures(e!=null); IToken/*!*/ id, x;  List<Expression/*!*/>/*!*/ args;
		Expression e0 = null;  Expression e1 = null;  Expression/*!*/ ee;  bool anyDots = false;
		List<Expression> multipleIndices = null;
		bool func = false;
		
		if (la.kind == 17) {
			Get();
			if (la.kind == 56) {
				Get();
				if (!func) { e = new ExprDotName(t, e, t.val); } 
			}
		} else if (la.kind == 1) {
			Ident(out id);
			if (la.kind == 26) {
				Get();
				IToken openParen = t;  args = new List<Expression/*!*/>();  func = true; 
				if (StartOf(13)) {
					Expressions(args, null);
				}
				Expect(28);
				e = new FunctionCallExpr(id, id.val, e, openParen, args); 
			}
			if (!func) { e = new ExprDotName(id, e, id.val); } 
		} else if (la.kind == 66) {
			Get();
			x = t; 
			if (StartOf(13)) {
				Expression(out ee, null);
				e0 = ee; 
				if (la.kind == 116) {
					Get();
					anyDots = true; 
					if (StartOf(13)) {
						Expression(out ee, null);
						e1 = ee; 
					}
				} else if (la.kind == 61) {
					Get();
					Expression(out ee, null);
					e1 = ee; 
				} else if (la.kind == 24 || la.kind == 67) {
					while (la.kind == 24) {
						Get();
						Expression(out ee, null);
						if (multipleIndices == null) {
						multipleIndices = new List<Expression>();
						multipleIndices.Add(e0);
						}
						multipleIndices.Add(ee);
						
					}
				} else SynErr(208);
			} else if (la.kind == 116) {
				Get();
				anyDots = true; 
				if (StartOf(13)) {
					Expression(out ee, null);
					e1 = ee; 
				}
			} else SynErr(209);
			if (multipleIndices != null) {
			e = new MultiSelectExpr(x, e, multipleIndices);
			// make sure an array class with this dimensionality exists
			UserDefinedType tmp = theBuiltIns.ArrayType(x, multipleIndices.Count, new IntType(), true);
			} else {
			if (!anyDots && e0 == null) {
			/* a parsing error occurred */
			e0 = dummyExpr;
			}
			Contract.Assert(anyDots || e0 != null);
			if (anyDots) {
			//Contract.Assert(e0 != null || e1 != null);
			e = new SeqSelectExpr(x, false, e, e0, e1);
			} else if (e1 == null) {
			Contract.Assert(e0 != null);
			e = new SeqSelectExpr(x, true, e, e0, null);
			} else {
			Contract.Assert(e0 != null);
			e = new SeqUpdateExpr(x, e, e0, e1);
			}
			}
			
			Expect(67);
		} else SynErr(210);
	}

	void DisplayExpr(out Expression e) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		IToken/*!*/ x = null;  List<Expression/*!*/>/*!*/ elements;
		e = dummyExpr;
		
		if (la.kind == 6) {
			Get();
			x = t;  elements = new List<Expression/*!*/>(); 
			if (StartOf(13)) {
				Expressions(elements, null);
			}
			e = new SetDisplayExpr(x, elements);
			Expect(7);
		} else if (la.kind == 66) {
			Get();
			x = t;  elements = new List<Expression/*!*/>(); 
			if (StartOf(13)) {
				Expressions(elements, null);
			}
			e = new SeqDisplayExpr(x, elements); 
			Expect(67);
		} else SynErr(211);
	}

	void MultiSetExpr(out Expression e) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		IToken/*!*/ x = null;  List<Expression/*!*/>/*!*/ elements;
		e = dummyExpr;
		
		Expect(48);
		x = t; 
		if (la.kind == 6) {
			Get();
			elements = new List<Expression/*!*/>(); 
			if (StartOf(13)) {
				Expressions(elements, null);
			}
			e = new MultiSetDisplayExpr(x, elements);
			Expect(7);
		} else if (la.kind == 26) {
			Get();
			x = t;  elements = new List<Expression/*!*/>(); 
			Expression(out e, null);
			e = new MultiSetFormingExpr(x, e); 
			Expect(28);
		} else if (StartOf(24)) {
			SemErr("multiset must be followed by multiset literal or expression to coerce in parentheses."); 
		} else SynErr(212);
	}

	void MapDisplayExpr(IToken/*!*/ mapToken, out Expression e) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		List<ExpressionPair/*!*/>/*!*/ elements= new List<ExpressionPair/*!*/>() ;
		e = dummyExpr;
		
		Expect(66);
		if (StartOf(13)) {
			MapLiteralExpressions(out elements);
		}
		e = new MapDisplayExpr(mapToken, elements);
		Expect(67);
	}

	void MapComprehensionExpr(IToken/*!*/ mapToken, out Expression e) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		BoundVar/*!*/ bv;
		List<BoundVar/*!*/> bvars = new List<BoundVar/*!*/>();
		Expression range = null;
		Expression body;
		
		IdentTypeOptional(out bv);
		bvars.Add(bv); 
		if (la.kind == 22) {
			Get();
			Expression(out range, null);
		}
		QSep();
		Expression(out body, null);
		e = new MapComprehension(mapToken, bvars, range ?? new LiteralExpr(mapToken, true), body);
		
	}

	void RegionFilterExpr(IToken/*!*/ filterToken, out Expression e, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(filterToken != null);
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		e = dummyExpr; 
		Expression/*!*/ regionExpr = dummyExpr;
		List<Expression/*!*/> filterList = new List<Expression/*!*/>();
		IToken id;
		
		Expect(6);
		Expression(out e, patterns);
		regionExpr = e; 
		Expect(24);
		Expect(113);
		Ident(out id);
		e = new ClassExpression(id); filterList.Add(e); 
		if (la.kind == 24) {
			Get();
			Expect(114);
			Ident(out id);
			e = new FieldExpression(id); filterList.Add(e); 
		}
		Expect(7);
		e = new RegionFilterExpression(t, regionExpr, filterList);
		
	}

	void FootprintExpr(IToken /*!*/ ftpToken, out Expression e, List<PatternExpression/*!*/> patterns) {
		Contract.Requires(ftpToken != null);
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		e = dummyExpr;
		
		Expect(26);
		Expression(out e, patterns);
		Expect(28);
		e = new FootprintExpression(t, e);
		
	}

	void BuiltInFunctionExpr(IToken/*!*/ funcToken, out Expression e) {
		Contract.Requires(funcToken != null); 
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		e = dummyExpr;
		List<Expression/*!*/> args = new List<Expression/*!*/>();
		
		Expect(26);
		if (StartOf(13)) {
			Expression(out e, null);
			args.Add(e); 
			if (la.kind == 24) {
				Get();
				Expression(out e, null);
				args.Add(e); 
			}
		}
		Expect(28);
		e = new BuiltInFunctionExpression(funcToken, args);
		
	}

	void ConstAtomExpression(out Expression/*!*/ e, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null);
		IToken/*!*/ x;  BigInteger n;
		e = dummyExpr;
		
		switch (la.kind) {
		case 106: {
			Get();
			e = new LiteralExpr(t, false); 
			break;
		}
		case 107: {
			Get();
			e = new LiteralExpr(t, true); 
			break;
		}
		case 108: {
			Get();
			e = new LiteralExpr(t); 
			break;
		}
		case 2: {
			Nat(out n);
			e = new LiteralExpr(t, n); 
			break;
		}
		case 109: {
			Get();
			e = new ThisExpr(t); 
			break;
		}
		case 110: {
			Get();
			x = t; 
			Expect(26);
			Expression(out e, patterns);
			Expect(28);
			e = new FreshExpr(x, e); 
			break;
		}
		case 111: {
			Get();
			x = t; 
			Expect(26);
			Expression(out e, patterns);
			Expect(28);
			e = new OldExpr(x, e); 
			break;
		}
		case 22: {
			Get();
			x = t; 
			Expression(out e, patterns);
			e = new UnaryExpr(x, UnaryExpr.Opcode.Length, e); 
			Expect(22);
			break;
		}
		case 26: {
			Get();
			x = t; 
			Expression(out e, patterns);
			e = new ParensExpression(x, e); 
			Expect(28);
			break;
		}
		case 112: {
			Get();
			x = t; 
			Expression(out e, patterns);
			e = new PatternExpression(x, e); 
			patterns.Add((PatternExpression)e); 
			break;
		}
		default: SynErr(213); break;
		}
	}

	void Nat(out BigInteger n) {
		Expect(2);
		try {
		 n = BigInteger.Parse(t.val);
		} catch (System.FormatException) {
		 SemErr("incorrectly formatted number");
		 n = BigInteger.Zero;
		}
		
	}

	void MapLiteralExpressions(out List<ExpressionPair> elements) {
		Expression/*!*/ d, r;
		elements = new List<ExpressionPair/*!*/>(); 
		Expression(out d, null);
		Expect(61);
		Expression(out r, null);
		elements.Add(new ExpressionPair(d,r)); 
		while (la.kind == 24) {
			Get();
			Expression(out d, null);
			Expect(61);
			Expression(out r, null);
			elements.Add(new ExpressionPair(d,r)); 
		}
	}

	void QSep() {
		if (la.kind == 121) {
			Get();
		} else if (la.kind == 122) {
			Get();
		} else SynErr(214);
	}

	void MatchExpression(out Expression/*!*/ e, List<PatternExpression/*!*/> patterns) {
		Contract.Ensures(Contract.ValueAtReturn(out e) != null); IToken/*!*/ x;  MatchCaseExpr/*!*/ c;
		List<MatchCaseExpr/*!*/> cases = new List<MatchCaseExpr/*!*/>();
		
		Expect(75);
		x = t; 
		Expression(out e, patterns);
		while (la.kind == 71) {
			CaseExpression(out c);
			cases.Add(c); 
		}
		e = new MatchExpr(x, e, cases); 
	}

	void QuantifierGuts(out Expression/*!*/ q) {
		Contract.Ensures(Contract.ValueAtReturn(out q) != null); IToken/*!*/ x = Token.NoToken;
		bool univ = false;
		List<BoundVar/*!*/> bvars;
		Attributes attrs;
		Expression range;
		Expression/*!*/ body;
		
		if (la.kind == 117 || la.kind == 118) {
			Forall();
			x = t;  univ = true; 
		} else if (la.kind == 119 || la.kind == 120) {
			Exists();
			x = t; 
		} else SynErr(215);
		QuantifierDomain(out bvars, out attrs, out range);
		QSep();
		Expression(out body, null);
		if (univ) {
		 q = new ForallExpr(x, bvars, range, body, attrs);
		} else {
		 q = new ExistsExpr(x, bvars, range, body, attrs);
		}
		
	}

	void ComprehensionExpr(out Expression/*!*/ q) {
		Contract.Ensures(Contract.ValueAtReturn(out q) != null);
		IToken/*!*/ x = Token.NoToken;
		BoundVar/*!*/ bv;
		List<BoundVar/*!*/> bvars = new List<BoundVar/*!*/>();
		Expression/*!*/ range;
		Expression body = null;
		
		Expect(47);
		x = t; 
		IdentTypeOptional(out bv);
		bvars.Add(bv); 
		while (la.kind == 24) {
			Get();
			IdentTypeOptional(out bv);
			bvars.Add(bv); 
		}
		Expect(22);
		Expression(out range, null);
		if (la.kind == 121 || la.kind == 122) {
			QSep();
			Expression(out body, null);
		}
		if (body == null && bvars.Count != 1) { SemErr(t, "a set comprehension with more than one bound variable must have a term expression"); }
		q = new SetComprehension(x, bvars, range, body);
		
	}

	void LetExpr(out Expression e, List<PatternExpression/*!*/> patterns) {
		IToken/*!*/ x;
		e = dummyExpr;
		BoundVar d;
		List<BoundVar> letVars;  List<Expression> letRHSs;
		
		Expect(23);
		x = t;
		letVars = new List<BoundVar>();
		letRHSs = new List<Expression>(); 
		IdentTypeOptional(out d);
		letVars.Add(d); 
		while (la.kind == 24) {
			Get();
			IdentTypeOptional(out d);
			letVars.Add(d); 
		}
		Expect(61);
		Expression(out e, patterns);
		letRHSs.Add(e); 
		while (la.kind == 24) {
			Get();
			Expression(out e, patterns);
			letRHSs.Add(e); 
		}
		Expect(14);
		Expression(out e, patterns);
		e = new LetExpr(x, letVars, letRHSs, e); 
	}

	void NamedExpr(out Expression e, List<PatternExpression/*!*/> patterns) {
		IToken/*!*/ x, d;
		e = dummyExpr;
		Expression expr;
		
		Expect(58);
		x = t; 
		NoUSIdent(out d);
		Expect(5);
		Expression(out e, patterns);
		expr = e;
		 e = new NamedExpr(x, d.val, expr); 
	}

	void CaseExpression(out MatchCaseExpr/*!*/ c) {
		Contract.Ensures(Contract.ValueAtReturn(out c) != null); IToken/*!*/ x, id;
		List<BoundVar/*!*/> arguments = new List<BoundVar/*!*/>();
		BoundVar/*!*/ bv;
		Expression/*!*/ body;
		
		Expect(71);
		x = t; 
		Ident(out id);
		if (la.kind == 26) {
			Get();
			IdentTypeOptional(out bv);
			arguments.Add(bv); 
			while (la.kind == 24) {
				Get();
				IdentTypeOptional(out bv);
				arguments.Add(bv); 
			}
			Expect(28);
		}
		Expect(72);
		Expression(out body, null);
		c = new MatchCaseExpr(x, id.val, arguments, body); 
	}

	void Forall() {
		if (la.kind == 117) {
			Get();
		} else if (la.kind == 118) {
			Get();
		} else SynErr(216);
	}

	void Exists() {
		if (la.kind == 119) {
			Get();
		} else if (la.kind == 120) {
			Get();
		} else SynErr(217);
	}

	void AttributeBody(ref Attributes attrs) {
		string aName;
		List<Attributes.Argument/*!*/> aArgs = new List<Attributes.Argument/*!*/>();
		Attributes.Argument/*!*/ aArg;
		
		Expect(5);
		Expect(1);
		aName = t.val; 
		if (StartOf(25)) {
			AttributeArg(out aArg);
			aArgs.Add(aArg); 
			while (la.kind == 24) {
				Get();
				AttributeArg(out aArg);
				aArgs.Add(aArg); 
			}
		}
		attrs = new Attributes(aName, aArgs, attrs); 
	}



	public void Parse() {
		la = new Token();
		la.val = "";
		Get();
		Dafny();
		Expect(0);

		Expect(0);
	}

	static readonly bool[,]/*!*/ set = {
		{T,T,T,x, x,x,T,x, T,x,x,x, x,x,T,x, x,x,T,x, T,T,T,T, x,x,T,x, x,T,x,T, x,x,T,T, x,T,T,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, T,x,x,x, x,T,x,x, x,T,T,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, T,T,x,T, x,x,x,x, x,x,T,T, T,T,x,T, x,T,x,x, x,T,x,x, x,x,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, T,x,x,x, x,x,x,x, x,x,x,T, x,x,x,T, x,x,x,x, x,x,x,x, x,x,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{T,x,x,x, x,x,T,T, T,T,x,T, x,x,x,x, x,x,T,T, T,T,x,T, x,T,T,x, x,T,x,x, T,x,T,T, x,x,x,T, T,T,T,x, x,x,x,x, x,x,x,x, x,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,T,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,x,T, x,x,x,x, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,T,T,T, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, x,T,T,x, x,x,x,x, T,x,T,x, x,T,x,x, x,x,x,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, T,x,x,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,T,x, x,x,T,x, T,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,T, x,x,x,x, x,x,x,x, x,x,x,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, T,x,x,x, x,T,x,x, x,T,x,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x},
		{T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, x,x,T,x, x,x,x,x, T,x,T,x, x,T,x,x, x,x,x,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x},
		{x,T,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, T,T,T,x, x,x,x,x, T,x,T,x, x,T,x,x, x,x,x,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x},
		{T,T,T,x, x,x,T,x, T,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,T, x,x,x,x, x,x,x,x, x,x,x,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, T,x,x,x, x,T,x,x, x,T,x,T, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,x, x,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, x,x,x,x, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,T,T,T, T,T,T,T, T,T,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,T, x,x,x,x, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, x,x,T,x, x,x,x,x, T,x,T,x, x,T,x,x, x,x,x,T, T,x,x,T, T,T,T,T, T,T,T,T, T,T,T,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x},
		{x,T,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, T,x,T,x, x,x,x,x, T,T,T,x, T,T,x,x, x,x,x,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x},
		{x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,x, x,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,T,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, x,x,x,x, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,T,T,T, T,T,T,x, x,x,x,x, x,x,x,T, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,T,x,x, x,x,T,T, x,x,x,x, x,x,T,x, x,T,x,x, x,x,T,x, T,x,x,T, T,x,x,x, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,T,x,x, x,T,x,x, x,x,T,T, x,x,T,T, T,x,x,x, x,x,x,x, T,T,T,T, T,T,T,T, T,T,T,T, T,T,T,T, T,T,T,T, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,x,x, x,T,T,x, x},
		{x,T,T,x, T,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,T,T, x,x,T,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,T, T,x,T,T, x,x,x,x, x,x,T,x, x,x,x,x, T,x,T,x, x,T,x,x, x,x,x,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,x,T, x,x,T,T, T,T,T,T, T,T,T,T, T,x,x,x, x,T,T,T, T,x,x,x, x}

	};
} // end Parser


public class Errors {
	public int count = 0;                                    // number of errors detected
	public System.IO.TextWriter/*!*/ errorStream = Console.Out;   // error messages go to this stream
	public string errMsgFormat = "{0}({1},{2}): error: {3}"; // 0=filename, 1=line, 2=column, 3=text
	public string warningMsgFormat = "{0}({1},{2}): warning: {3}"; // 0=filename, 1=line, 2=column, 3=text

	public void SynErr(string filename, int line, int col, int n) {
		SynErr(filename, line, col, GetSyntaxErrorString(n));
	}

	public virtual void SynErr(string filename, int line, int col, string/*!*/ msg) {
		Contract.Requires(msg != null);
		errorStream.WriteLine(errMsgFormat, filename, line, col, msg);
		count++;
	}

	string GetSyntaxErrorString(int n) {
		string s;
		switch (n) {
			case 0: s = "EOF expected"; break;
			case 1: s = "ident expected"; break;
			case 2: s = "digits expected"; break;
			case 3: s = "arrayToken expected"; break;
			case 4: s = "string expected"; break;
			case 5: s = "colon expected"; break;
			case 6: s = "lbrace expected"; break;
			case 7: s = "rbrace expected"; break;
			case 8: s = "\"ghost\" expected"; break;
			case 9: s = "\"module\" expected"; break;
			case 10: s = "\"refines\" expected"; break;
			case 11: s = "\"import\" expected"; break;
			case 12: s = "\"opened\" expected"; break;
			case 13: s = "\"=\" expected"; break;
			case 14: s = "\";\" expected"; break;
			case 15: s = "\"as\" expected"; break;
			case 16: s = "\"default\" expected"; break;
			case 17: s = "\".\" expected"; break;
			case 18: s = "\"class\" expected"; break;
			case 19: s = "\"static\" expected"; break;
			case 20: s = "\"datatype\" expected"; break;
			case 21: s = "\"codatatype\" expected"; break;
			case 22: s = "\"|\" expected"; break;
			case 23: s = "\"var\" expected"; break;
			case 24: s = "\",\" expected"; break;
			case 25: s = "\"type\" expected"; break;
			case 26: s = "\"(\" expected"; break;
			case 27: s = "\"==\" expected"; break;
			case 28: s = "\")\" expected"; break;
			case 29: s = "\"iterator\" expected"; break;
			case 30: s = "\"yields\" expected"; break;
			case 31: s = "\"...\" expected"; break;
			case 32: s = "\"<\" expected"; break;
			case 33: s = "\">\" expected"; break;
			case 34: s = "\"method\" expected"; break;
			case 35: s = "\"constructor\" expected"; break;
			case 36: s = "\"returns\" expected"; break;
			case 37: s = "\"modifies\" expected"; break;
			case 38: s = "\"free\" expected"; break;
			case 39: s = "\"requires\" expected"; break;
			case 40: s = "\"ensures\" expected"; break;
			case 41: s = "\"decreases\" expected"; break;
			case 42: s = "\"reads\" expected"; break;
			case 43: s = "\"yield\" expected"; break;
			case 44: s = "\"bool\" expected"; break;
			case 45: s = "\"nat\" expected"; break;
			case 46: s = "\"int\" expected"; break;
			case 47: s = "\"set\" expected"; break;
			case 48: s = "\"multiset\" expected"; break;
			case 49: s = "\"seq\" expected"; break;
			case 50: s = "\"map\" expected"; break;
			case 51: s = "\"region\" expected"; break;
			case 52: s = "\"object\" expected"; break;
			case 53: s = "\"function\" expected"; break;
			case 54: s = "\"predicate\" expected"; break;
			case 55: s = "\"copredicate\" expected"; break;
			case 56: s = "\"*\" expected"; break;
			case 57: s = "\"`\" expected"; break;
			case 58: s = "\"label\" expected"; break;
			case 59: s = "\"break\" expected"; break;
			case 60: s = "\"where\" expected"; break;
			case 61: s = "\":=\" expected"; break;
			case 62: s = "\"return\" expected"; break;
			case 63: s = "\":|\" expected"; break;
			case 64: s = "\"assume\" expected"; break;
			case 65: s = "\"new\" expected"; break;
			case 66: s = "\"[\" expected"; break;
			case 67: s = "\"]\" expected"; break;
			case 68: s = "\"choose\" expected"; break;
			case 69: s = "\"if\" expected"; break;
			case 70: s = "\"else\" expected"; break;
			case 71: s = "\"case\" expected"; break;
			case 72: s = "\"=>\" expected"; break;
			case 73: s = "\"while\" expected"; break;
			case 74: s = "\"invariant\" expected"; break;
			case 75: s = "\"match\" expected"; break;
			case 76: s = "\"assert\" expected"; break;
			case 77: s = "\"print\" expected"; break;
			case 78: s = "\"parallel\" expected"; break;
			case 79: s = "\"calc\" expected"; break;
			case 80: s = "\"<=\" expected"; break;
			case 81: s = "\">=\" expected"; break;
			case 82: s = "\"!=\" expected"; break;
			case 83: s = "\"\\u2260\" expected"; break;
			case 84: s = "\"\\u2264\" expected"; break;
			case 85: s = "\"\\u2265\" expected"; break;
			case 86: s = "\"&*&\" expected"; break;
			case 87: s = "\"<==>\" expected"; break;
			case 88: s = "\"\\u21d4\" expected"; break;
			case 89: s = "\"==>\" expected"; break;
			case 90: s = "\"\\u21d2\" expected"; break;
			case 91: s = "\"&&\" expected"; break;
			case 92: s = "\"\\u2227\" expected"; break;
			case 93: s = "\"||\" expected"; break;
			case 94: s = "\"\\u2228\" expected"; break;
			case 95: s = "\"!!\" expected"; break;
			case 96: s = "\"in\" expected"; break;
			case 97: s = "\"!\" expected"; break;
			case 98: s = "\"+\" expected"; break;
			case 99: s = "\"-\" expected"; break;
			case 100: s = "\"/\" expected"; break;
			case 101: s = "\"%\" expected"; break;
			case 102: s = "\"filter\" expected"; break;
			case 103: s = "\"footprint\" expected"; break;
			case 104: s = "\"IsRecursiveType\" expected"; break;
			case 105: s = "\"\\u00ac\" expected"; break;
			case 106: s = "\"false\" expected"; break;
			case 107: s = "\"true\" expected"; break;
			case 108: s = "\"null\" expected"; break;
			case 109: s = "\"this\" expected"; break;
			case 110: s = "\"fresh\" expected"; break;
			case 111: s = "\"old\" expected"; break;
			case 112: s = "\"?\" expected"; break;
			case 113: s = "\"class:\" expected"; break;
			case 114: s = "\"field:\" expected"; break;
			case 115: s = "\"then\" expected"; break;
			case 116: s = "\"..\" expected"; break;
			case 117: s = "\"forall\" expected"; break;
			case 118: s = "\"\\u2200\" expected"; break;
			case 119: s = "\"exists\" expected"; break;
			case 120: s = "\"\\u2203\" expected"; break;
			case 121: s = "\"::\" expected"; break;
			case 122: s = "\"\\u2022\" expected"; break;
			case 123: s = "??? expected"; break;
			case 124: s = "invalid Dafny"; break;
			case 125: s = "invalid SubModuleDecl"; break;
			case 126: s = "invalid SubModuleDecl"; break;
			case 127: s = "invalid SubModuleDecl"; break;
			case 128: s = "this symbol not expected in ClassDecl"; break;
			case 129: s = "this symbol not expected in DatatypeDecl"; break;
			case 130: s = "invalid DatatypeDecl"; break;
			case 131: s = "this symbol not expected in DatatypeDecl"; break;
			case 132: s = "this symbol not expected in ArbitraryTypeDecl"; break;
			case 133: s = "this symbol not expected in IteratorDecl"; break;
			case 134: s = "invalid IteratorDecl"; break;
			case 135: s = "invalid ClassMemberDecl"; break;
			case 136: s = "this symbol not expected in FieldDecl"; break;
			case 137: s = "this symbol not expected in FieldDecl"; break;
			case 138: s = "invalid FunctionDecl"; break;
			case 139: s = "invalid FunctionDecl"; break;
			case 140: s = "invalid FunctionDecl"; break;
			case 141: s = "invalid FunctionDecl"; break;
			case 142: s = "this symbol not expected in MethodDecl"; break;
			case 143: s = "invalid MethodDecl"; break;
			case 144: s = "invalid MethodDecl"; break;
			case 145: s = "invalid TypeAndToken"; break;
			case 146: s = "this symbol not expected in IteratorSpec"; break;
			case 147: s = "this symbol not expected in IteratorSpec"; break;
			case 148: s = "this symbol not expected in IteratorSpec"; break;
			case 149: s = "this symbol not expected in IteratorSpec"; break;
			case 150: s = "this symbol not expected in IteratorSpec"; break;
			case 151: s = "invalid IteratorSpec"; break;
			case 152: s = "this symbol not expected in IteratorSpec"; break;
			case 153: s = "invalid IteratorSpec"; break;
			case 154: s = "this symbol not expected in MethodSpec"; break;
			case 155: s = "this symbol not expected in MethodSpec"; break;
			case 156: s = "this symbol not expected in MethodSpec"; break;
			case 157: s = "this symbol not expected in MethodSpec"; break;
			case 158: s = "invalid MethodSpec"; break;
			case 159: s = "this symbol not expected in MethodSpec"; break;
			case 160: s = "invalid MethodSpec"; break;
			case 161: s = "invalid FrameExpression"; break;
			case 162: s = "invalid ReferenceType"; break;
			case 163: s = "this symbol not expected in FunctionSpec"; break;
			case 164: s = "this symbol not expected in FunctionSpec"; break;
			case 165: s = "this symbol not expected in FunctionSpec"; break;
			case 166: s = "this symbol not expected in FunctionSpec"; break;
			case 167: s = "this symbol not expected in FunctionSpec"; break;
			case 168: s = "invalid FunctionSpec"; break;
			case 169: s = "invalid PossiblyWildFrameExpression"; break;
			case 170: s = "invalid PossiblyWildExpression"; break;
			case 171: s = "this symbol not expected in OneStmt"; break;
			case 172: s = "invalid OneStmt"; break;
			case 173: s = "this symbol not expected in OneStmt"; break;
			case 174: s = "invalid OneStmt"; break;
			case 175: s = "invalid AssertStmt"; break;
			case 176: s = "invalid AssumeStmt"; break;
			case 177: s = "invalid UpdateStmt"; break;
			case 178: s = "invalid UpdateStmt"; break;
			case 179: s = "invalid IfStmt"; break;
			case 180: s = "invalid IfStmt"; break;
			case 181: s = "invalid WhileStmt"; break;
			case 182: s = "invalid WhileStmt"; break;
			case 183: s = "invalid CalcStmt"; break;
			case 184: s = "invalid ReturnStmt"; break;
			case 185: s = "invalid Rhs"; break;
			case 186: s = "invalid Lhs"; break;
			case 187: s = "invalid Guard"; break;
			case 188: s = "this symbol not expected in LoopSpec"; break;
			case 189: s = "this symbol not expected in LoopSpec"; break;
			case 190: s = "this symbol not expected in LoopSpec"; break;
			case 191: s = "this symbol not expected in LoopSpec"; break;
			case 192: s = "this symbol not expected in LoopSpec"; break;
			case 193: s = "this symbol not expected in Invariant"; break;
			case 194: s = "invalid AttributeArg"; break;
			case 195: s = "invalid CalcOp"; break;
			case 196: s = "invalid EquivOp"; break;
			case 197: s = "invalid ImpliesOp"; break;
			case 198: s = "invalid AndOp"; break;
			case 199: s = "invalid OrOp"; break;
			case 200: s = "invalid RelOp"; break;
			case 201: s = "invalid AddOp"; break;
			case 202: s = "invalid UnaryExpression"; break;
			case 203: s = "invalid UnaryExpression"; break;
			case 204: s = "invalid MulOp"; break;
			case 205: s = "invalid NegOp"; break;
			case 206: s = "invalid EndlessExpression"; break;
			case 207: s = "invalid DottedIdentifiersAndFunction"; break;
			case 208: s = "invalid Suffix"; break;
			case 209: s = "invalid Suffix"; break;
			case 210: s = "invalid Suffix"; break;
			case 211: s = "invalid DisplayExpr"; break;
			case 212: s = "invalid MultiSetExpr"; break;
			case 213: s = "invalid ConstAtomExpression"; break;
			case 214: s = "invalid QSep"; break;
			case 215: s = "invalid QuantifierGuts"; break;
			case 216: s = "invalid Forall"; break;
			case 217: s = "invalid Exists"; break;

			default: s = "error " + n; break;
		}
		return s;
	}

	public void SemErr(IToken/*!*/ tok, string/*!*/ msg) {  // semantic errors
		Contract.Requires(tok != null);
		Contract.Requires(msg != null);
		SemErr(tok.filename, tok.line, tok.col, msg);
	}

	public virtual void SemErr(string filename, int line, int col, string/*!*/ msg) {
		Contract.Requires(msg != null);
		errorStream.WriteLine(errMsgFormat, filename, line, col, msg);
		count++;
	}

	public virtual void Warning(string filename, int line, int col, string msg) {
		Contract.Requires(msg != null);
		errorStream.WriteLine(warningMsgFormat, filename, line, col, msg);
	}
} // Errors


public class FatalError: Exception {
	public FatalError(string m): base(m) {}
}


}