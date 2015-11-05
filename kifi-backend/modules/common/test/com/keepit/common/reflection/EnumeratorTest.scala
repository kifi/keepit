package com.keepit.common.reflection

import org.specs2.mutable.Specification

sealed abstract class Foo(val value: String)
object Foo extends Enumerator[Foo] {
  case object A extends Foo("a")
  object Bar extends Enumerator[Foo] {
    case object B extends Foo("b")
    object Baz {
      case object C extends Foo("c")
    }
    case class Obstacle(y: String)
    def all: Set[Foo] = _all.toSet
  }
  case class Rando(x: Int)
  def all: Set[Foo] = _all.toSet
}

class EnumeratorTest extends Specification {
  "EnumeratorTest" should {
    "retrieve all of an trait's subclasses enclosed within a given object" in {
      Foo.all must haveSize(3)
      Foo.all.map(_.value) === Set("a", "b", "c")

      Foo.Bar.all must haveSize(2)
      Foo.Bar.all.map(_.value) === Set("b", "c")
    }
  }
}

