static predicate valid(n: Node, vlst: seq<int>, r: region)
reads region{n.*}, r;
{
	(n == null ==> r == region{} && |vlst| == 0) &&
	(n != null ==>
		|vlst| > 0 &&  vlst[0] == n.data &&
		( n.next == null ==> 
			region{n.*} == r
		) 
		&&
	    ( n.next != null ==>
			region{n.*} < r &&
			region{n.next.*} <= r - region{n.*} &&
			valid(n.next, vlst[1..],  r - region{n.*}) 
		)
	)
}

class Node{
	var data: int;
	var next: Node;

	method Init(d: int)
	requires true;
	modifies region{this.*};
	ensures valid(this, [d], region{this.*});
	{
		this.data := d;
		this.next := null;
	}

	method Update(d: int, ghost lst: seq<int>, ghost r : region)
	requires this.next == null && valid(this, [this.data] + lst, r);
	ensures valid(this, [d] + lst, r);
	{
		this.data := d;
	}

	method Prepend(n: Node, ghost vlist: seq<int>, ghost reg: region) returns (r: Node)
	requires n!= null;
	requires valid(n,[n.data], region{n.*}) &*& valid(this, vlist, reg);
	//requires footprint(valid(n, [n.data], region{n.*})) !! footprint(valid(this, vlist, reg));
	ensures r == n; 
	ensures valid(r, [old(n.data)] + vlist, reg + region{r.*});
	{
		assert region{n.*} == footprint(valid(n, [n.data], region{n.*}));
		r := n;
		r.next := this;

	}
}

class client
{
	method main(){
	
		var n := new Node.Init(4);
		assert n.data == 4;
		assert n.next == null;
		n.Update(5, [4], region{n.*});
		assert n.data == 5;
		assert n.next == null;

		var n2 := new Node.Init(6);
		var n3 := n2.Prepend(n, [6], region{n2.*});
		assert n3.data == 5;
		assert n3.next == n2;
		assert n3.next.data == 6;
	}
}
