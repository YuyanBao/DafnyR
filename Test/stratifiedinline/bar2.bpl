procedure foo() returns (x: bool)
{
  var b: bool;
  if (b) {
    x := false;
    return;
  } else {
    x := true;
    return;
  }
}

procedure {:entrypoint} main()
{
  var b1: bool;
  var b2: bool;

  call b1 := foo();
  call b2 := foo();
  assume b1 != b2;
}


