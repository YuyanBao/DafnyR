class Node<T> {
  var val: T;
  var next: Node<T>;
  var fpt: region;

  function Valid(): bool  
    reads this, fpt; 
  {
    region{this} <= fpt &&
    (next == null ==> 
      (region{this} == fpt)) &&
    (next != null ==>
       region{next.*} < fpt &&
       next.fpt < fpt &&
       !(region{this} <= next.fpt)&&
       fpt == region{this} + next.fpt &&
       next.Valid())
    }

  method Init(d: T)
    modifies this.*;
    ensures Valid() && val==d &&  next==null;
    ensures fresh(fpt - region{this});
  {
        val := d;  
        next := null;  
        fpt := region{this};
  }

  method Prepend(node: Node<T>) 
	returns (newList: Node<T>)
    requires node!=null && node.next==null;
    requires node.Valid() && this.Valid();
    requires node.fpt !! fpt;
    modifies node.next, region{node.fpt};
    ensures newList == node && newList.Valid();
    ensures newList.fpt== region{newList}+this.fpt;
  {
    newList := node;  
    newList.next := this;
    newList.fpt := 
	region{newList} + this.fpt;
  }

  method Update(val: T)
    modifies this.val;  
    ensures this.val == val;
  {
        this.val := val;
  }
}

class client
{
	method main(){
	
		var n := new Node<int>.Init(4);
		assert n.val == 4;
		assert n.next == null;
		n.Update(5);
		assert n.val == 5;
		assert n.next == null;

		var n2 := new Node<int>.Init(6);
		var n3 := n2.Prepend(n);
		assert n3.val == 5;
		assert n3.next == n2;
		assert n2.val == 6;
		assert n3.next.val == 6;
	}
}
