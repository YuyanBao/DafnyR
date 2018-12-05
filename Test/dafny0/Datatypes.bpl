// Dafny program verifier version 2.2.30705.1126, Copyright (c) 2003-2012, Microsoft.
// Command Line Options: C:\Projects\Boogie\Test\dafny0\Datatypes.dfy /print C:\Projects\Boogie\Test\dafny0\Datatypes.bpl /smoke /compile 0

const $$Language$Dafny: bool;

axiom $$Language$Dafny;

type ref;

const null: ref;

type Set T = [T]bool;

function Set#Empty<T>() : Set T;

axiom (forall<T> o: T :: { Set#Empty()[o] } !Set#Empty()[o]);

function Set#Singleton<T>(T) : Set T;

axiom (forall<T> r: T :: { Set#Singleton(r) } Set#Singleton(r)[r]);

axiom (forall<T> r: T, o: T :: { Set#Singleton(r)[o] } Set#Singleton(r)[o] <==> r == o);

function Set#UnionOne<T>(Set T, T) : Set T;

axiom (forall<T> a: Set T, x: T, o: T :: { Set#UnionOne(a, x)[o] } Set#UnionOne(a, x)[o] <==> o == x || a[o]);

axiom (forall<T> a: Set T, x: T :: { Set#UnionOne(a, x) } Set#UnionOne(a, x)[x]);

axiom (forall<T> a: Set T, x: T, y: T :: { Set#UnionOne(a, x), a[y] } a[y] ==> Set#UnionOne(a, x)[y]);

function Set#Union<T>(Set T, Set T) : Set T;

axiom (forall<T> a: Set T, b: Set T, o: T :: { Set#Union(a, b)[o] } Set#Union(a, b)[o] <==> a[o] || b[o]);

axiom (forall<T> a: Set T, b: Set T, y: T :: { Set#Union(a, b), a[y] } a[y] ==> Set#Union(a, b)[y]);

axiom (forall<T> a: Set T, b: Set T, y: T :: { Set#Union(a, b), b[y] } b[y] ==> Set#Union(a, b)[y]);

axiom (forall<T> a: Set T, b: Set T :: { Set#Union(a, b) } Set#Disjoint(a, b) ==> Set#Difference(Set#Union(a, b), a) == b && Set#Difference(Set#Union(a, b), b) == a);

function Set#Intersection<T>(Set T, Set T) : Set T;

axiom (forall<T> a: Set T, b: Set T, o: T :: { Set#Intersection(a, b)[o] } Set#Intersection(a, b)[o] <==> a[o] && b[o]);

axiom (forall<T> a: Set T, b: Set T :: { Set#Union(Set#Union(a, b), b) } Set#Union(Set#Union(a, b), b) == Set#Union(a, b));

axiom (forall<T> a: Set T, b: Set T :: { Set#Union(a, Set#Union(a, b)) } Set#Union(a, Set#Union(a, b)) == Set#Union(a, b));

axiom (forall<T> a: Set T, b: Set T :: { Set#Intersection(Set#Intersection(a, b), b) } Set#Intersection(Set#Intersection(a, b), b) == Set#Intersection(a, b));

axiom (forall<T> a: Set T, b: Set T :: { Set#Intersection(a, Set#Intersection(a, b)) } Set#Intersection(a, Set#Intersection(a, b)) == Set#Intersection(a, b));

function Set#Difference<T>(Set T, Set T) : Set T;

axiom (forall<T> a: Set T, b: Set T, o: T :: { Set#Difference(a, b)[o] } Set#Difference(a, b)[o] <==> a[o] && !b[o]);

axiom (forall<T> a: Set T, b: Set T, y: T :: { Set#Difference(a, b), b[y] } b[y] ==> !Set#Difference(a, b)[y]);

function Set#Subset<T>(Set T, Set T) : bool;

axiom (forall<T> a: Set T, b: Set T :: { Set#Subset(a, b) } Set#Subset(a, b) <==> (forall o: T :: { a[o] } { b[o] } a[o] ==> b[o]));

function Set#Equal<T>(Set T, Set T) : bool;

axiom (forall<T> a: Set T, b: Set T :: { Set#Equal(a, b) } Set#Equal(a, b) <==> (forall o: T :: { a[o] } { b[o] } a[o] <==> b[o]));

axiom (forall<T> a: Set T, b: Set T :: { Set#Equal(a, b) } Set#Equal(a, b) ==> a == b);

function Set#Disjoint<T>(Set T, Set T) : bool;

axiom (forall<T> a: Set T, b: Set T :: { Set#Disjoint(a, b) } Set#Disjoint(a, b) <==> (forall o: T :: { a[o] } { b[o] } !a[o] || !b[o]));

function Set#Choose<T>(Set T, TickType) : T;

axiom (forall<T> a: Set T, tick: TickType :: { Set#Choose(a, tick) } a != Set#Empty() ==> a[Set#Choose(a, tick)]);

function Math#min(a: int, b: int) : int;

axiom (forall a: int, b: int :: { Math#min(a, b) } a <= b <==> Math#min(a, b) == a);

axiom (forall a: int, b: int :: { Math#min(a, b) } b <= a <==> Math#min(a, b) == b);

axiom (forall a: int, b: int :: { Math#min(a, b) } Math#min(a, b) == a || Math#min(a, b) == b);

function Math#clip(a: int) : int;

axiom (forall a: int :: { Math#clip(a) } 0 <= a ==> Math#clip(a) == a);

axiom (forall a: int :: { Math#clip(a) } a < 0 ==> Math#clip(a) == 0);

type MultiSet T = [T]int;

function $IsGoodMultiSet<T>(ms: MultiSet T) : bool;

axiom (forall<T> ms: MultiSet T :: { $IsGoodMultiSet(ms) } $IsGoodMultiSet(ms) <==> (forall o: T :: { ms[o] } 0 <= ms[o]));

function MultiSet#Empty<T>() : MultiSet T;

axiom (forall<T> o: T :: { MultiSet#Empty()[o] } MultiSet#Empty()[o] == 0);

function MultiSet#Singleton<T>(T) : MultiSet T;

axiom (forall<T> r: T, o: T :: { MultiSet#Singleton(r)[o] } (MultiSet#Singleton(r)[o] == 1 <==> r == o) && (MultiSet#Singleton(r)[o] == 0 <==> r != o));

axiom (forall<T> r: T :: { MultiSet#Singleton(r) } MultiSet#Singleton(r) == MultiSet#UnionOne(MultiSet#Empty(), r));

function MultiSet#UnionOne<T>(MultiSet T, T) : MultiSet T;

axiom (forall<T> a: MultiSet T, x: T, o: T :: { MultiSet#UnionOne(a, x)[o] } 0 < MultiSet#UnionOne(a, x)[o] <==> o == x || 0 < a[o]);

axiom (forall<T> a: MultiSet T, x: T :: { MultiSet#UnionOne(a, x) } MultiSet#UnionOne(a, x)[x] == a[x] + 1);

axiom (forall<T> a: MultiSet T, x: T, y: T :: { MultiSet#UnionOne(a, x), a[y] } 0 < a[y] ==> 0 < MultiSet#UnionOne(a, x)[y]);

axiom (forall<T> a: MultiSet T, x: T, y: T :: { MultiSet#UnionOne(a, x), a[y] } x != y ==> a[y] == MultiSet#UnionOne(a, x)[y]);

function MultiSet#Union<T>(MultiSet T, MultiSet T) : MultiSet T;

axiom (forall<T> a: MultiSet T, b: MultiSet T, o: T :: { MultiSet#Union(a, b)[o] } MultiSet#Union(a, b)[o] == a[o] + b[o]);

axiom (forall<T> a: MultiSet T, b: MultiSet T, y: T :: { MultiSet#Union(a, b), a[y] } 0 < a[y] ==> 0 < MultiSet#Union(a, b)[y]);

axiom (forall<T> a: MultiSet T, b: MultiSet T, y: T :: { MultiSet#Union(a, b), b[y] } 0 < b[y] ==> 0 < MultiSet#Union(a, b)[y]);

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Union(a, b) } MultiSet#Difference(MultiSet#Union(a, b), a) == b && MultiSet#Difference(MultiSet#Union(a, b), b) == a);

function MultiSet#Intersection<T>(MultiSet T, MultiSet T) : MultiSet T;

axiom (forall<T> a: MultiSet T, b: MultiSet T, o: T :: { MultiSet#Intersection(a, b)[o] } MultiSet#Intersection(a, b)[o] == Math#min(a[o], b[o]));

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Intersection(MultiSet#Intersection(a, b), b) } MultiSet#Intersection(MultiSet#Intersection(a, b), b) == MultiSet#Intersection(a, b));

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Intersection(a, MultiSet#Intersection(a, b)) } MultiSet#Intersection(a, MultiSet#Intersection(a, b)) == MultiSet#Intersection(a, b));

function MultiSet#Difference<T>(MultiSet T, MultiSet T) : MultiSet T;

axiom (forall<T> a: MultiSet T, b: MultiSet T, o: T :: { MultiSet#Difference(a, b)[o] } MultiSet#Difference(a, b)[o] == Math#clip(a[o] - b[o]));

axiom (forall<T> a: MultiSet T, b: MultiSet T, y: T :: { MultiSet#Difference(a, b), b[y], a[y] } a[y] <= b[y] ==> MultiSet#Difference(a, b)[y] == 0);

function MultiSet#Subset<T>(MultiSet T, MultiSet T) : bool;

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Subset(a, b) } MultiSet#Subset(a, b) <==> (forall o: T :: { a[o] } { b[o] } a[o] <= b[o]));

function MultiSet#Equal<T>(MultiSet T, MultiSet T) : bool;

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Equal(a, b) } MultiSet#Equal(a, b) <==> (forall o: T :: { a[o] } { b[o] } a[o] == b[o]));

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Equal(a, b) } MultiSet#Equal(a, b) ==> a == b);

function MultiSet#Disjoint<T>(MultiSet T, MultiSet T) : bool;

axiom (forall<T> a: MultiSet T, b: MultiSet T :: { MultiSet#Disjoint(a, b) } MultiSet#Disjoint(a, b) <==> (forall o: T :: { a[o] } { b[o] } a[o] == 0 || b[o] == 0));

function MultiSet#FromSet<T>(Set T) : MultiSet T;

axiom (forall<T> s: Set T, a: T :: { MultiSet#FromSet(s)[a] } (MultiSet#FromSet(s)[a] == 0 <==> !s[a]) && (MultiSet#FromSet(s)[a] == 1 <==> s[a]));

function MultiSet#FromSeq<T>(Seq T) : MultiSet T;

axiom (forall<T> s: Seq T :: { MultiSet#FromSeq(s) } $IsGoodMultiSet(MultiSet#FromSeq(s)));

axiom (forall<T> s: Seq T, v: T :: { MultiSet#FromSeq(Seq#Build(s, v)) } MultiSet#FromSeq(Seq#Build(s, v)) == MultiSet#UnionOne(MultiSet#FromSeq(s), v));

axiom (forall<T>  :: MultiSet#FromSeq(Seq#Empty(): Seq T) == MultiSet#Empty(): MultiSet T);

axiom (forall<T> a: Seq T, b: Seq T :: { MultiSet#FromSeq(Seq#Append(a, b)) } MultiSet#FromSeq(Seq#Append(a, b)) == MultiSet#Union(MultiSet#FromSeq(a), MultiSet#FromSeq(b)));

axiom (forall<T> s: Seq T, i: int, v: T, x: T :: { MultiSet#FromSeq(Seq#Update(s, i, v))[x] } 0 <= i && i < Seq#Length(s) ==> MultiSet#FromSeq(Seq#Update(s, i, v))[x] == MultiSet#Union(MultiSet#Difference(MultiSet#FromSeq(s), MultiSet#Singleton(Seq#Index(s, i))), MultiSet#Singleton(v))[x]);

axiom (forall<T> s: Seq T, x: T :: { MultiSet#FromSeq(s)[x] } (exists i: int :: { Seq#Index(s, i) } 0 <= i && i < Seq#Length(s) && x == Seq#Index(s, i)) <==> 0 < MultiSet#FromSeq(s)[x]);

type Seq _;

function Seq#Length<T>(Seq T) : int;

axiom (forall<T> s: Seq T :: { Seq#Length(s) } 0 <= Seq#Length(s));

function Seq#Empty<T>() : Seq T;

axiom (forall<T>  :: Seq#Length(Seq#Empty(): Seq T) == 0);

axiom (forall<T> s: Seq T :: { Seq#Length(s) } Seq#Length(s) == 0 ==> s == Seq#Empty());

function Seq#Singleton<T>(T) : Seq T;

axiom (forall<T> t: T :: { Seq#Length(Seq#Singleton(t)) } Seq#Length(Seq#Singleton(t)) == 1);

function Seq#Build<T>(s: Seq T, val: T) : Seq T;

axiom (forall<T> s: Seq T, v: T :: { Seq#Length(Seq#Build(s, v)) } Seq#Length(Seq#Build(s, v)) == 1 + Seq#Length(s));

axiom (forall<T> s: Seq T, i: int, v: T :: { Seq#Index(Seq#Build(s, v), i) } (i == Seq#Length(s) ==> Seq#Index(Seq#Build(s, v), i) == v) && (i != Seq#Length(s) ==> Seq#Index(Seq#Build(s, v), i) == Seq#Index(s, i)));

function Seq#Append<T>(Seq T, Seq T) : Seq T;

axiom (forall<T> s0: Seq T, s1: Seq T :: { Seq#Length(Seq#Append(s0, s1)) } Seq#Length(Seq#Append(s0, s1)) == Seq#Length(s0) + Seq#Length(s1));

function Seq#Index<T>(Seq T, int) : T;

axiom (forall<T> t: T :: { Seq#Index(Seq#Singleton(t), 0) } Seq#Index(Seq#Singleton(t), 0) == t);

axiom (forall<T> s0: Seq T, s1: Seq T, n: int :: { Seq#Index(Seq#Append(s0, s1), n) } (n < Seq#Length(s0) ==> Seq#Index(Seq#Append(s0, s1), n) == Seq#Index(s0, n)) && (Seq#Length(s0) <= n ==> Seq#Index(Seq#Append(s0, s1), n) == Seq#Index(s1, n - Seq#Length(s0))));

function Seq#Update<T>(Seq T, int, T) : Seq T;

axiom (forall<T> s: Seq T, i: int, v: T :: { Seq#Length(Seq#Update(s, i, v)) } 0 <= i && i < Seq#Length(s) ==> Seq#Length(Seq#Update(s, i, v)) == Seq#Length(s));

axiom (forall<T> s: Seq T, i: int, v: T, n: int :: { Seq#Index(Seq#Update(s, i, v), n) } 0 <= n && n < Seq#Length(s) ==> (i == n ==> Seq#Index(Seq#Update(s, i, v), n) == v) && (i != n ==> Seq#Index(Seq#Update(s, i, v), n) == Seq#Index(s, n)));

function Seq#Contains<T>(Seq T, T) : bool;

axiom (forall<T> s: Seq T, x: T :: { Seq#Contains(s, x) } Seq#Contains(s, x) <==> (exists i: int :: { Seq#Index(s, i) } 0 <= i && i < Seq#Length(s) && Seq#Index(s, i) == x));

axiom (forall x: ref :: { Seq#Contains(Seq#Empty(), x) } !Seq#Contains(Seq#Empty(), x));

axiom (forall<T> s0: Seq T, s1: Seq T, x: T :: { Seq#Contains(Seq#Append(s0, s1), x) } Seq#Contains(Seq#Append(s0, s1), x) <==> Seq#Contains(s0, x) || Seq#Contains(s1, x));

axiom (forall<T> s: Seq T, v: T, x: T :: { Seq#Contains(Seq#Build(s, v), x) } Seq#Contains(Seq#Build(s, v), x) <==> v == x || Seq#Contains(s, x));

axiom (forall<T> s: Seq T, n: int, x: T :: { Seq#Contains(Seq#Take(s, n), x) } Seq#Contains(Seq#Take(s, n), x) <==> (exists i: int :: { Seq#Index(s, i) } 0 <= i && i < n && i < Seq#Length(s) && Seq#Index(s, i) == x));

axiom (forall<T> s: Seq T, n: int, x: T :: { Seq#Contains(Seq#Drop(s, n), x) } Seq#Contains(Seq#Drop(s, n), x) <==> (exists i: int :: { Seq#Index(s, i) } 0 <= n && n <= i && i < Seq#Length(s) && Seq#Index(s, i) == x));

function Seq#Equal<T>(Seq T, Seq T) : bool;

axiom (forall<T> s0: Seq T, s1: Seq T :: { Seq#Equal(s0, s1) } Seq#Equal(s0, s1) <==> Seq#Length(s0) == Seq#Length(s1) && (forall j: int :: { Seq#Index(s0, j) } { Seq#Index(s1, j) } 0 <= j && j < Seq#Length(s0) ==> Seq#Index(s0, j) == Seq#Index(s1, j)));

axiom (forall<T> a: Seq T, b: Seq T :: { Seq#Equal(a, b) } Seq#Equal(a, b) ==> a == b);

function Seq#SameUntil<T>(Seq T, Seq T, int) : bool;

axiom (forall<T> s0: Seq T, s1: Seq T, n: int :: { Seq#SameUntil(s0, s1, n) } Seq#SameUntil(s0, s1, n) <==> (forall j: int :: { Seq#Index(s0, j) } { Seq#Index(s1, j) } 0 <= j && j < n ==> Seq#Index(s0, j) == Seq#Index(s1, j)));

function Seq#Take<T>(s: Seq T, howMany: int) : Seq T;

axiom (forall<T> s: Seq T, n: int :: { Seq#Length(Seq#Take(s, n)) } 0 <= n ==> (n <= Seq#Length(s) ==> Seq#Length(Seq#Take(s, n)) == n) && (Seq#Length(s) < n ==> Seq#Length(Seq#Take(s, n)) == Seq#Length(s)));

axiom (forall<T> s: Seq T, n: int, j: int :: {:weight 25} { Seq#Index(Seq#Take(s, n), j) } 0 <= j && j < n && j < Seq#Length(s) ==> Seq#Index(Seq#Take(s, n), j) == Seq#Index(s, j));

function Seq#Drop<T>(s: Seq T, howMany: int) : Seq T;

axiom (forall<T> s: Seq T, n: int :: { Seq#Length(Seq#Drop(s, n)) } 0 <= n ==> (n <= Seq#Length(s) ==> Seq#Length(Seq#Drop(s, n)) == Seq#Length(s) - n) && (Seq#Length(s) < n ==> Seq#Length(Seq#Drop(s, n)) == 0));

axiom (forall<T> s: Seq T, n: int, j: int :: {:weight 25} { Seq#Index(Seq#Drop(s, n), j) } 0 <= n && 0 <= j && j < Seq#Length(s) - n ==> Seq#Index(Seq#Drop(s, n), j) == Seq#Index(s, j + n));

axiom (forall<T> s: Seq T, t: Seq T :: { Seq#Append(s, t) } Seq#Take(Seq#Append(s, t), Seq#Length(s)) == s && Seq#Drop(Seq#Append(s, t), Seq#Length(s)) == t);

function Seq#FromArray(h: HeapType, a: ref) : Seq BoxType;

axiom (forall h: HeapType, a: ref :: { Seq#Length(Seq#FromArray(h, a)) } Seq#Length(Seq#FromArray(h, a)) == _System.array.Length(a));

axiom (forall h: HeapType, a: ref :: { Seq#FromArray(h, a): Seq BoxType } (forall i: int :: 0 <= i && i < Seq#Length(Seq#FromArray(h, a)) ==> Seq#Index(Seq#FromArray(h, a), i) == read(h, a, IndexField(i))));

axiom (forall<alpha> h: HeapType, o: ref, f: Field alpha, v: alpha, a: ref :: { Seq#FromArray(update(h, o, f, v), a) } o != a ==> Seq#FromArray(update(h, o, f, v), a) == Seq#FromArray(h, a));

axiom (forall h: HeapType, i: int, v: BoxType, a: ref :: { Seq#FromArray(update(h, a, IndexField(i), v), a) } 0 <= i && i < _System.array.Length(a) ==> Seq#FromArray(update(h, a, IndexField(i), v), a) == Seq#Update(Seq#FromArray(h, a), i, v));

axiom (forall<T> s: Seq T, i: int, v: T, n: int :: { Seq#Take(Seq#Update(s, i, v), n) } 0 <= i && i < n && n <= Seq#Length(s) ==> Seq#Take(Seq#Update(s, i, v), n) == Seq#Update(Seq#Take(s, n), i, v));

axiom (forall<T> s: Seq T, i: int, v: T, n: int :: { Seq#Take(Seq#Update(s, i, v), n) } n <= i && i < Seq#Length(s) ==> Seq#Take(Seq#Update(s, i, v), n) == Seq#Take(s, n));

axiom (forall<T> s: Seq T, i: int, v: T, n: int :: { Seq#Drop(Seq#Update(s, i, v), n) } 0 <= n && n <= i && i < Seq#Length(s) ==> Seq#Drop(Seq#Update(s, i, v), n) == Seq#Update(Seq#Drop(s, n), i - n, v));

axiom (forall<T> s: Seq T, i: int, v: T, n: int :: { Seq#Drop(Seq#Update(s, i, v), n) } 0 <= i && i < n && n < Seq#Length(s) ==> Seq#Drop(Seq#Update(s, i, v), n) == Seq#Drop(s, n));

axiom (forall h: HeapType, a: ref, n0: int, n1: int :: { Seq#Take(Seq#FromArray(h, a), n0), Seq#Take(Seq#FromArray(h, a), n1) } n0 + 1 == n1 && 0 <= n0 && n1 <= _System.array.Length(a) ==> Seq#Take(Seq#FromArray(h, a), n1) == Seq#Build(Seq#Take(Seq#FromArray(h, a), n0), read(h, a, IndexField(n0): Field BoxType)));

axiom (forall<T> s: Seq T, v: T, n: int :: { Seq#Drop(Seq#Build(s, v), n) } 0 <= n && n <= Seq#Length(s) ==> Seq#Drop(Seq#Build(s, v), n) == Seq#Build(Seq#Drop(s, n), v));

axiom Seq#Take(Seq#Empty(): Seq BoxType, 0) == Seq#Empty();

axiom Seq#Drop(Seq#Empty(): Seq BoxType, 0) == Seq#Empty();

type Map _ _;

function Map#Domain<U,V>(Map U V) : [U]bool;

function Map#Elements<U,V>(Map U V) : [U]V;

function Map#Empty<U,V>() : Map U V;

axiom (forall<U,V> u: U :: { Map#Domain(Map#Empty(): Map U V)[u] } !Map#Domain(Map#Empty(): Map U V)[u]);

function Map#Glue<U,V>([U]bool, [U]V) : Map U V;

axiom (forall<U,V> a: [U]bool, b: [U]V :: { Map#Domain(Map#Glue(a, b)) } Map#Domain(Map#Glue(a, b)) == a);

axiom (forall<U,V> a: [U]bool, b: [U]V :: { Map#Elements(Map#Glue(a, b)) } Map#Elements(Map#Glue(a, b)) == b);

function Map#Build<U,V>(Map U V, U, V) : Map U V;

axiom (forall<U,V> m: Map U V, u: U, u': U, v: V :: { Map#Domain(Map#Build(m, u, v))[u'] } { Map#Elements(Map#Build(m, u, v))[u'] } (u' == u ==> Map#Domain(Map#Build(m, u, v))[u'] && Map#Elements(Map#Build(m, u, v))[u'] == v) && (u' != u ==> Map#Domain(Map#Build(m, u, v))[u'] == Map#Domain(m)[u'] && Map#Elements(Map#Build(m, u, v))[u'] == Map#Elements(m)[u']));

function Map#Equal<U,V>(Map U V, Map U V) : bool;

axiom (forall<U,V> m: Map U V, m': Map U V :: { Map#Equal(m, m') } Map#Equal(m, m') <==> (forall u: U :: Map#Domain(m)[u] == Map#Domain(m')[u]) && (forall u: U :: Map#Domain(m)[u] ==> Map#Elements(m)[u] == Map#Elements(m')[u]));

axiom (forall<U,V> m: Map U V, m': Map U V :: { Map#Equal(m, m') } Map#Equal(m, m') ==> m == m');

function Map#Disjoint<U,V>(Map U V, Map U V) : bool;

axiom (forall<U,V> m: Map U V, m': Map U V :: { Map#Disjoint(m, m') } Map#Disjoint(m, m') <==> (forall o: U :: { Map#Domain(m)[o] } { Map#Domain(m')[o] } !Map#Domain(m)[o] || !Map#Domain(m')[o]));

type BoxType;

function $Box<T>(T) : BoxType;

function $Unbox<T>(BoxType) : T;

axiom (forall<T> x: T :: { $Box(x) } $Unbox($Box(x)) == x);

axiom (forall b: BoxType :: { $Unbox(b): int } $Box($Unbox(b): int) == b);

axiom (forall b: BoxType :: { $Unbox(b): ref } $Box($Unbox(b): ref) == b);

axiom (forall b: BoxType :: { $Unbox(b): Set BoxType } $Box($Unbox(b): Set BoxType) == b);

axiom (forall b: BoxType :: { $Unbox(b): Seq BoxType } $Box($Unbox(b): Seq BoxType) == b);

axiom (forall b: BoxType :: { $Unbox(b): Map BoxType BoxType } $Box($Unbox(b): Map BoxType BoxType) == b);

axiom (forall b: BoxType :: { $Unbox(b): DatatypeType } $Box($Unbox(b): DatatypeType) == b);

function $IsCanonicalBoolBox(BoxType) : bool;

axiom $IsCanonicalBoolBox($Box(false)) && $IsCanonicalBoolBox($Box(true));

axiom (forall b: BoxType :: { $Unbox(b): bool } $IsCanonicalBoolBox(b) ==> $Box($Unbox(b): bool) == b);

type ClassName;

const unique class._System.int: ClassName;

const unique class._System.bool: ClassName;

const unique class._System.set: ClassName;

const unique class._System.seq: ClassName;

const unique class._System.multiset: ClassName;

const unique class._System.array: ClassName;

function dtype(ref) : ClassName;

function TypeParams(ref, int) : ClassName;

function TypeTuple(a: ClassName, b: ClassName) : ClassName;

function TypeTupleCar(ClassName) : ClassName;

function TypeTupleCdr(ClassName) : ClassName;

axiom (forall a: ClassName, b: ClassName :: { TypeTuple(a, b) } TypeTupleCar(TypeTuple(a, b)) == a && TypeTupleCdr(TypeTuple(a, b)) == b);

type DatatypeType;

function DtType(DatatypeType) : ClassName;

function DtTypeParams(DatatypeType, int) : ClassName;

type DtCtorId;

function DatatypeCtorId(DatatypeType) : DtCtorId;

function DtRank(DatatypeType) : int;

const $ModuleContextHeight: int;

const $FunctionContextHeight: int;

const $InMethodContext: bool;

type Field _;

function FDim<T>(Field T) : int;

function IndexField(int) : Field BoxType;

axiom (forall i: int :: { IndexField(i) } FDim(IndexField(i)) == 1);

function IndexField_Inverse<T>(Field T) : int;

axiom (forall i: int :: { IndexField(i) } IndexField_Inverse(IndexField(i)) == i);

function MultiIndexField(Field BoxType, int) : Field BoxType;

axiom (forall f: Field BoxType, i: int :: { MultiIndexField(f, i) } FDim(MultiIndexField(f, i)) == FDim(f) + 1);

function MultiIndexField_Inverse0<T>(Field T) : Field T;

function MultiIndexField_Inverse1<T>(Field T) : int;

axiom (forall f: Field BoxType, i: int :: { MultiIndexField(f, i) } MultiIndexField_Inverse0(MultiIndexField(f, i)) == f && MultiIndexField_Inverse1(MultiIndexField(f, i)) == i);

function DeclType<T>(Field T) : ClassName;

type NameFamily;

function DeclName<T>(Field T) : NameFamily;

function FieldOfDecl<alpha>(ClassName, NameFamily) : Field alpha;

axiom (forall<T> cl: ClassName, nm: NameFamily :: { FieldOfDecl(cl, nm): Field T } DeclType(FieldOfDecl(cl, nm): Field T) == cl && DeclName(FieldOfDecl(cl, nm): Field T) == nm);

const unique alloc: Field bool;

axiom FDim(alloc) == 0;

function DtAlloc(DatatypeType, HeapType) : bool;

axiom (forall h: HeapType, k: HeapType, d: DatatypeType :: { $HeapSucc(h, k), DtAlloc(d, h) } { $HeapSucc(h, k), DtAlloc(d, k) } $HeapSucc(h, k) ==> DtAlloc(d, h) ==> DtAlloc(d, k));

function GenericAlloc(BoxType, HeapType) : bool;

axiom (forall h: HeapType, k: HeapType, d: BoxType :: { $HeapSucc(h, k), GenericAlloc(d, h) } { $HeapSucc(h, k), GenericAlloc(d, k) } $HeapSucc(h, k) ==> GenericAlloc(d, h) ==> GenericAlloc(d, k));

axiom (forall b: BoxType, h: HeapType :: { GenericAlloc(b, h), h[$Unbox(b): ref, alloc] } GenericAlloc(b, h) ==> $Unbox(b): ref == null || h[$Unbox(b): ref, alloc]);

axiom (forall b: BoxType, h: HeapType, i: int :: { GenericAlloc(b, h), Seq#Index($Unbox(b): Seq BoxType, i) } GenericAlloc(b, h) && 0 <= i && i < Seq#Length($Unbox(b): Seq BoxType) ==> GenericAlloc(Seq#Index($Unbox(b): Seq BoxType, i), h));

axiom (forall b: BoxType, h: HeapType, i: BoxType :: { GenericAlloc(b, h), Map#Domain($Unbox(b): Map BoxType BoxType)[i] } GenericAlloc(b, h) && Map#Domain($Unbox(b): Map BoxType BoxType)[i] ==> GenericAlloc(Map#Elements($Unbox(b): Map BoxType BoxType)[i], h));

axiom (forall b: BoxType, h: HeapType, t: BoxType :: { GenericAlloc(b, h), Map#Domain($Unbox(b): Map BoxType BoxType)[t] } GenericAlloc(b, h) && Map#Domain($Unbox(b): Map BoxType BoxType)[t] ==> GenericAlloc(t, h));

axiom (forall b: BoxType, h: HeapType, t: BoxType :: { GenericAlloc(b, h), ($Unbox(b): Set BoxType)[t] } GenericAlloc(b, h) && ($Unbox(b): Set BoxType)[t] ==> GenericAlloc(t, h));

axiom (forall b: BoxType, h: HeapType :: { GenericAlloc(b, h), DtType($Unbox(b): DatatypeType) } GenericAlloc(b, h) ==> DtAlloc($Unbox(b): DatatypeType, h));

axiom (forall b: bool, h: HeapType :: $IsGoodHeap(h) ==> GenericAlloc($Box(b), h));

axiom (forall x: int, h: HeapType :: $IsGoodHeap(h) ==> GenericAlloc($Box(x), h));

axiom (forall r: ref, h: HeapType :: { GenericAlloc($Box(r), h) } $IsGoodHeap(h) && (r == null || h[r, alloc]) ==> GenericAlloc($Box(r), h));

axiom (forall r: ref, f: Field BoxType, h: HeapType :: { GenericAlloc(read(h, r, f), h) } $IsGoodHeap(h) && r != null && read(h, r, alloc) ==> GenericAlloc(read(h, r, f), h));

function _System.array.Length(a: ref) : int;

axiom (forall o: ref :: 0 <= _System.array.Length(o));

type HeapType = <alpha>[ref,Field alpha]alpha;

function {:inline true} read<alpha>(H: HeapType, r: ref, f: Field alpha) : alpha
{
  H[r, f]
}

function {:inline true} update<alpha>(H: HeapType, r: ref, f: Field alpha, v: alpha) : HeapType
{
  H[r, f := v]
}

function $IsGoodHeap(HeapType) : bool;

var $Heap: HeapType where $IsGoodHeap($Heap);

function $HeapSucc(HeapType, HeapType) : bool;

axiom (forall<alpha> h: HeapType, r: ref, f: Field alpha, x: alpha :: { update(h, r, f, x) } $IsGoodHeap(update(h, r, f, x)) ==> $HeapSucc(h, update(h, r, f, x)));

axiom (forall a: HeapType, b: HeapType, c: HeapType :: { $HeapSucc(a, b), $HeapSucc(b, c) } $HeapSucc(a, b) && $HeapSucc(b, c) ==> $HeapSucc(a, c));

axiom (forall h: HeapType, k: HeapType :: { $HeapSucc(h, k) } $HeapSucc(h, k) ==> (forall o: ref :: { read(k, o, alloc) } read(h, o, alloc) ==> read(k, o, alloc)));

procedure $YieldHavoc(this: ref, rds: Set BoxType, nw: Set BoxType);
  modifies $Heap;
  ensures (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> $o == this || rds[$Box($o)] || nw[$Box($o)] ==> read($Heap, $o, $f) == read(old($Heap), $o, $f));
  ensures $HeapSucc(old($Heap), $Heap);



procedure $IterHavoc0(this: ref, rds: Set BoxType, modi: Set BoxType);
  modifies $Heap;
  ensures (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> rds[$Box($o)] && !modi[$Box($o)] && $o != this ==> read($Heap, $o, $f) == read(old($Heap), $o, $f));
  ensures $HeapSucc(old($Heap), $Heap);



procedure $IterHavoc1(this: ref, modi: Set BoxType, nw: Set BoxType);
  modifies $Heap;
  ensures (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> read($Heap, $o, $f) == read(old($Heap), $o, $f) || $o == this || modi[$Box($o)] || nw[$Box($o)]);
  ensures $HeapSucc(old($Heap), $Heap);



procedure $IterCollectNewObjects(prevHeap: HeapType, newHeap: HeapType, this: ref, NW: Field (Set BoxType)) returns (s: Set BoxType);
  ensures (forall bx: BoxType :: { s[bx] } s[bx] <==> read(newHeap, this, NW)[bx] || ($Unbox(bx) != null && !read(prevHeap, $Unbox(bx): ref, alloc) && read(newHeap, $Unbox(bx): ref, alloc)));



type TickType;

var $Tick: TickType;

const unique class._System.object: ClassName;

const unique class._module.List: ClassName;

function #_module.List.Nil() : DatatypeType;

axiom DtType(#_module.List.Nil()) == class._module.List;

const unique ##_module.List.Nil: DtCtorId;

axiom DatatypeCtorId(#_module.List.Nil()) == ##_module.List.Nil;

axiom (forall d: DatatypeType :: _module.List.Nil_q(d) ==> d == #_module.List.Nil());

function _module.List.Nil_q(this: DatatypeType) : bool
{
  DatatypeCtorId(this) == ##_module.List.Nil
}

axiom (forall $h: HeapType :: { DtAlloc(#_module.List.Nil(), $h) } $IsGoodHeap($h) ==> (DtAlloc(#_module.List.Nil(), $h) <==> true));

function #_module.List.Cons(BoxType, DatatypeType) : DatatypeType;

axiom (forall a0#0: BoxType, a1#1: DatatypeType :: DtType(#_module.List.Cons(a0#0, a1#1)) == class._module.List);

const unique ##_module.List.Cons: DtCtorId;

axiom (forall a0#2: BoxType, a1#3: DatatypeType :: DatatypeCtorId(#_module.List.Cons(a0#2, a1#3)) == ##_module.List.Cons);

axiom (forall d: DatatypeType :: _module.List.Cons_q(d) ==> (exists a0#4: BoxType, a1#5: DatatypeType :: d == #_module.List.Cons(a0#4, a1#5)));

function _module.List.Cons_q(this: DatatypeType) : bool
{
  DatatypeCtorId(this) == ##_module.List.Cons
}

axiom (forall a0#6: BoxType, a1#7: DatatypeType, $h: HeapType :: { DtAlloc(#_module.List.Cons(a0#6, a1#7), $h) } $IsGoodHeap($h) ==> (DtAlloc(#_module.List.Cons(a0#6, a1#7), $h) <==> GenericAlloc(a0#6, $h) && DtAlloc(a1#7, $h) && DtType(a1#7) == class._module.List));

function ##_module.List.Cons#0(DatatypeType) : BoxType;

axiom (forall a0#8: BoxType, a1#9: DatatypeType :: ##_module.List.Cons#0(#_module.List.Cons(a0#8, a1#9)) == a0#8);

axiom (forall a0#10: BoxType, a1#11: DatatypeType :: DtRank($Unbox(a0#10): DatatypeType) < DtRank(#_module.List.Cons(a0#10, a1#11)));

function ##_module.List.Cons#1(DatatypeType) : DatatypeType;

axiom (forall a0#12: BoxType, a1#13: DatatypeType :: ##_module.List.Cons#1(#_module.List.Cons(a0#12, a1#13)) == a1#13);

axiom (forall a0#14: BoxType, a1#15: DatatypeType :: DtRank(a1#15) < DtRank(#_module.List.Cons(a0#14, a1#15)));

function $IsA#_module.List(d: DatatypeType) : bool
{
  _module.List.Nil_q(d) || _module.List.Cons_q(d)
}

const unique class._module.Node: ClassName;

axiom FDim(_module.Node.data) == 0 && FieldOfDecl(class._module.Node, field$data) == _module.Node.data;

const _module.Node.data: Field int;

axiom FDim(_module.Node.next) == 0 && FieldOfDecl(class._module.Node, field$next) == _module.Node.next;

const _module.Node.next: Field ref;

axiom (forall $h: HeapType, $o: ref :: { read($h, $o, _module.Node.next) } $IsGoodHeap($h) && $o != null && read($h, $o, alloc) ==> read($h, $o, _module.Node.next) == null || (read($h, read($h, $o, _module.Node.next), alloc) && dtype(read($h, $o, _module.Node.next)) == class._module.Node));

function _module.Node.Repr($heap: HeapType, this: ref, list#2: DatatypeType) : bool;

function _module.Node.Repr#limited($heap: HeapType, this: ref, list#2: DatatypeType) : bool;

function _module.Node.Repr#2($heap: HeapType, this: ref, list#2: DatatypeType) : bool;

function _module.Node.Repr#canCall($heap: HeapType, this: ref, list#2: DatatypeType) : bool;

axiom (forall $Heap: HeapType, this: ref, list#2: DatatypeType :: { _module.Node.Repr#2($Heap, this, list#2) } _module.Node.Repr#2($Heap, this, list#2) == _module.Node.Repr($Heap, this, list#2));

axiom (forall $Heap: HeapType, this: ref, list#2: DatatypeType :: { _module.Node.Repr($Heap, this, list#2) } _module.Node.Repr($Heap, this, list#2) == _module.Node.Repr#limited($Heap, this, list#2));

// definition axiom for _module.Node.Repr, specialized for 'list'
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref :: { _module.Node.Repr($Heap, this, #_module.List.Nil()) } _module.Node.Repr#canCall($Heap, this, #_module.List.Nil()) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node) ==> _module.Node.Repr($Heap, this, #_module.List.Nil()) == (read($Heap, this, _module.Node.next) == null));

// definition axiom for _module.Node.Repr, specialized for 'list'
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref, d#3: int, cdr#4: DatatypeType :: { _module.Node.Repr($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) } _module.Node.Repr#canCall($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node && DtAlloc(cdr#4, $Heap) && DtType(cdr#4) == class._module.List && DtTypeParams(cdr#4, 0) == class._System.int) ==> (read($Heap, this, _module.Node.data) == d#3 ==> true) && (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null ==> _module.Node.Repr#canCall($Heap, read($Heap, this, _module.Node.next), cdr#4)) && _module.Node.Repr($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) == (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null && _module.Node.Repr#limited($Heap, read($Heap, this, _module.Node.next), cdr#4)));

// definition axiom for _module.Node.Repr
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref, list#2: DatatypeType :: { _module.Node.Repr($Heap, this, list#2) } _module.Node.Repr#canCall($Heap, this, list#2) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node && DtAlloc(list#2, $Heap) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int) ==> true);

// definition axiom for _module.Node.Repr#2, specialized for 'list'
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref :: { _module.Node.Repr#2($Heap, this, #_module.List.Nil()) } _module.Node.Repr#canCall($Heap, this, #_module.List.Nil()) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node) ==> _module.Node.Repr#2($Heap, this, #_module.List.Nil()) == (read($Heap, this, _module.Node.next) == null));

// definition axiom for _module.Node.Repr#2, specialized for 'list'
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref, d#3: int, cdr#4: DatatypeType :: { _module.Node.Repr#2($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) } _module.Node.Repr#canCall($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node && DtAlloc(cdr#4, $Heap) && DtType(cdr#4) == class._module.List && DtTypeParams(cdr#4, 0) == class._System.int) ==> _module.Node.Repr#2($Heap, this, #_module.List.Cons($Box(d#3), cdr#4)) == (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null && _module.Node.Repr($Heap, read($Heap, this, _module.Node.next), cdr#4)));

// definition axiom for _module.Node.Repr#2
axiom 0 < $ModuleContextHeight || (0 == $ModuleContextHeight && (0 <= $FunctionContextHeight || $InMethodContext)) ==> (forall $Heap: HeapType, this: ref, list#2: DatatypeType :: { _module.Node.Repr#2($Heap, this, list#2) } _module.Node.Repr#canCall($Heap, this, list#2) || ((0 != $ModuleContextHeight || 0 != $FunctionContextHeight || $InMethodContext) && $IsGoodHeap($Heap) && this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node && DtAlloc(list#2, $Heap) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int) ==> true);

// frame axiom for _module.Node.Repr
axiom (forall $h0: HeapType, $h1: HeapType, this: ref, list#2: DatatypeType :: { $HeapSucc($h0, $h1), _module.Node.Repr($h1, this, list#2) } $IsGoodHeap($h0) && $IsGoodHeap($h1) && this != null && read($h0, this, alloc) && dtype(this) == class._module.Node && read($h1, this, alloc) && dtype(this) == class._module.Node && DtAlloc(list#2, $h0) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int && DtAlloc(list#2, $h1) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int && $HeapSucc($h0, $h1) ==> (forall<alpha> $o: ref, $f: Field alpha :: $o != null && read($h0, $o, alloc) && read($h1, $o, alloc) ==> read($h0, $o, $f) == read($h1, $o, $f)) ==> _module.Node.Repr($h0, this, list#2) == _module.Node.Repr($h1, this, list#2));

axiom (forall $h0: HeapType, $h1: HeapType, this: ref, list#2: DatatypeType :: { $HeapSucc($h0, $h1), _module.Node.Repr#limited($h1, this, list#2) } $IsGoodHeap($h0) && $IsGoodHeap($h1) && this != null && read($h0, this, alloc) && dtype(this) == class._module.Node && read($h1, this, alloc) && dtype(this) == class._module.Node && DtAlloc(list#2, $h0) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int && DtAlloc(list#2, $h1) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int && $HeapSucc($h0, $h1) ==> (forall<alpha> $o: ref, $f: Field alpha :: $o != null && read($h0, $o, alloc) && read($h1, $o, alloc) ==> read($h0, $o, $f) == read($h1, $o, $f)) ==> _module.Node.Repr#limited($h0, this, list#2) == _module.Node.Repr#limited($h1, this, list#2));

procedure CheckWellformed$$_module.Node.Repr(this: ref where this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node, list#2: DatatypeType where DtAlloc(list#2, $Heap) && DtType(list#2) == class._module.List && DtTypeParams(list#2, 0) == class._System.int);
  free requires 0 == $ModuleContextHeight && 0 == $FunctionContextHeight;



implementation CheckWellformed$$_module.Node.Repr(this: ref, list#2: DatatypeType)
{
  var $_Frame: <beta>[ref,Field beta]bool;
  var d#3: int;
  var cdr#4: DatatypeType;
  var list#16: DatatypeType;

    if (*)
    {
        assume false;
    }
    else
    {
        $_Frame := (lambda<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> true);
        if (list#2 == #_module.List.Nil())
        {
            assert $_Frame[this, _module.Node.next];
            assume _module.Node.Repr($Heap, this, list#2) == (read($Heap, this, _module.Node.next) == null);
            assume true;
        }
        else if (list#2 == #_module.List.Cons($Box(d#3), cdr#4))
        {
            assume DtAlloc(cdr#4, $Heap) && DtType(cdr#4) == class._module.List && DtTypeParams(cdr#4, 0) == class._System.int;
            assert $_Frame[this, _module.Node.data];
            if (read($Heap, this, _module.Node.data) == d#3)
            {
                assert $_Frame[this, _module.Node.next];
            }

            if (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null)
            {
                assert $_Frame[this, _module.Node.next];
                assert read($Heap, this, _module.Node.next) != null;
                list#16 := cdr#4;
                assert (forall<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> $_Frame[$o, $f]);
                assert DtRank(list#16) < DtRank(list#2);
                assume _module.Node.Repr#canCall($Heap, read($Heap, this, _module.Node.next), cdr#4);
            }

            assume _module.Node.Repr($Heap, this, list#2) == (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null && _module.Node.Repr($Heap, read($Heap, this, _module.Node.next), cdr#4));
            assume (read($Heap, this, _module.Node.data) == d#3 ==> true) && (read($Heap, this, _module.Node.data) == d#3 && read($Heap, this, _module.Node.next) != null ==> _module.Node.Repr#canCall($Heap, read($Heap, this, _module.Node.next), cdr#4));
        }
        else
        {
            assume false;
        }
    }
}



procedure CheckWellformed$$_module.Node.Init(this: ref where this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node);
  free requires 0 == $ModuleContextHeight && $InMethodContext;
  modifies $Heap, $Tick;



implementation CheckWellformed$$_module.Node.Init(this: ref)
{
  var $_Frame: <beta>[ref,Field beta]bool;
  var list#17: DatatypeType;

    $_Frame := (lambda<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> $o == this);
    havoc $Heap;
    assume (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> read($Heap, $o, $f) == read(old($Heap), $o, $f) || $o == this);
    assume $HeapSucc(old($Heap), $Heap);
    list#17 := #_module.List.Nil();
    assume _module.Node.Repr#canCall($Heap, this, #_module.List.Nil());
    assume _module.Node.Repr($Heap, this, #_module.List.Nil());
}



procedure _module.Node.Init(this: ref where this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node);
  free requires 0 == $ModuleContextHeight && $InMethodContext;
  modifies $Heap, $Tick;
  ensures _module.Node.Repr#2($Heap, this, #_module.List.Nil());
  // frame condition
  free ensures (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> read($Heap, $o, $f) == read(old($Heap), $o, $f) || $o == this);
  // boilerplate
  free ensures $HeapSucc(old($Heap), $Heap);



implementation _module.Node.Init(this: ref)
{
  var $_Frame: <beta>[ref,Field beta]bool;
  var $rhs#0: ref;

    $_Frame := (lambda<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> $o == this);
    // ----- assignment statement ----- C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(21,10)
    assume true;
    assert $_Frame[this, _module.Node.next];
    assume true;
    $rhs#0 := null;
    $Heap := update($Heap, this, _module.Node.next, $rhs#0);
    assume $IsGoodHeap($Heap);
    assume {:captureState "C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(21,10)"} true;
}



procedure CheckWellformed$$_module.Node.Add(this: ref where this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node, d#5: int, L#6: DatatypeType where DtAlloc(L#6, $Heap) && DtType(L#6) == class._module.List && DtTypeParams(L#6, 0) == class._System.int) returns (r#7: ref where r#7 == null || (read($Heap, r#7, alloc) && dtype(r#7) == class._module.Node));
  free requires 0 == $ModuleContextHeight && $InMethodContext;
  modifies $Heap, $Tick;



implementation CheckWellformed$$_module.Node.Add(this: ref, d#5: int, L#6: DatatypeType) returns (r#7: ref)
{
  var $_Frame: <beta>[ref,Field beta]bool;
  var list#18: DatatypeType;
  var list#19: DatatypeType;

    $_Frame := (lambda<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> false);
    list#18 := L#6;
    assume _module.Node.Repr#canCall($Heap, this, L#6);
    assume _module.Node.Repr($Heap, this, L#6);
    havoc $Heap;
    assume (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> read($Heap, $o, $f) == read(old($Heap), $o, $f));
    assume $HeapSucc(old($Heap), $Heap);
    havoc r#7;
    if (r#7 != null)
    {
        assert r#7 != null;
        list#19 := #_module.List.Cons($Box(d#5), L#6);
        assume _module.Node.Repr#canCall($Heap, r#7, #_module.List.Cons($Box(d#5), L#6));
    }

    assume r#7 != null && _module.Node.Repr($Heap, r#7, #_module.List.Cons($Box(d#5), L#6));
}



procedure _module.Node.Add(this: ref where this != null && read($Heap, this, alloc) && dtype(this) == class._module.Node, d#5: int, L#6: DatatypeType where DtAlloc(L#6, $Heap) && DtType(L#6) == class._module.List && DtTypeParams(L#6, 0) == class._System.int) returns (r#7: ref where r#7 == null || (read($Heap, r#7, alloc) && dtype(r#7) == class._module.Node));
  free requires 0 == $ModuleContextHeight && $InMethodContext;
  requires _module.Node.Repr#2($Heap, this, L#6);
  modifies $Heap, $Tick;
  ensures r#7 != null;
  ensures _module.Node.Repr#2($Heap, r#7, #_module.List.Cons($Box(d#5), L#6));
  // frame condition
  free ensures (forall<alpha> $o: ref, $f: Field alpha :: { read($Heap, $o, $f) } $o != null && read(old($Heap), $o, alloc) ==> read($Heap, $o, $f) == read(old($Heap), $o, $f));
  // boilerplate
  free ensures $HeapSucc(old($Heap), $Heap);



implementation _module.Node.Add(this: ref, d#5: int, L#6: DatatypeType) returns (r#7: ref)
{
  var $_Frame: <beta>[ref,Field beta]bool;
  var $nw: ref;
  var $rhs#0: int;
  var $rhs#1: ref;

    $_Frame := (lambda<alpha> $o: ref, $f: Field alpha :: $o != null && read($Heap, $o, alloc) ==> false);
    // ----- assignment statement ----- C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(28,7)
    assume true;
    havoc $nw;
    assume $nw != null && !read($Heap, $nw, alloc) && dtype($nw) == class._module.Node;
    $Heap := update($Heap, $nw, alloc, true);
    assume $IsGoodHeap($Heap);
    r#7 := $nw;
    assume {:captureState "C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(28,7)"} true;
    // ----- assignment statement ----- C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(29,12)
    assert r#7 != null;
    assume true;
    assert $_Frame[r#7, _module.Node.data];
    assume true;
    $rhs#0 := d#5;
    $Heap := update($Heap, r#7, _module.Node.data, $rhs#0);
    assume $IsGoodHeap($Heap);
    assume {:captureState "C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(29,12)"} true;
    // ----- assignment statement ----- C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(30,12)
    assert r#7 != null;
    assume true;
    assert $_Frame[r#7, _module.Node.next];
    assume true;
    $rhs#1 := this;
    $Heap := update($Heap, r#7, _module.Node.next, $rhs#1);
    assume $IsGoodHeap($Heap);
    assume {:captureState "C:\Projects\Boogie\Test\dafny0\Datatypes.dfy(30,12)"} true;
}



const unique class._module.__default: ClassName;

const unique field$data: NameFamily;

const unique field$next: NameFamily;
