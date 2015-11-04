package com.keepit.common.reflection

import org.specs2.mutable.Specification

sealed abstract class Foo(val value: String)
object Foo extends Enumerator[Foo] {
  case object A extends Foo("a")
  case object B extends Foo("b")
  case object C extends Foo("c")
  def all = _all.toSet
}

class EnumeratorTest extends Specification {
  "EnumeratorTest" should {
    "retrieve all of a sealed trait's subclasses" in {
      Foo.all must haveSize(3)
      Foo.all.map(_.value) === Set("a", "b", "c")
    }
  }
}

