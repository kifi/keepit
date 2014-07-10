package com.keepit.search.util

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.util.Random

class AhoCorasickTest extends Specification {
  val dict1 = Seq(
    (Seq("a", "b"), "a b"),
    (Seq("b", "c"), "b c"),
    (Seq("b", "a", "b"), "b a b"),
    (Seq("d"), "d"),
    (Seq("a", "b", "c"), "a b c"),
    (Seq("b", "c", "d", "e"), "b c d e"),
    (Seq("d", "e"), "d e"),
    (Seq("a", "b", "c", "d", "e"), "a b c d e")
  )
  val dict2 = Seq(
    (Seq("a"), "a"),
    (Seq("b", "c", "d"), "b c d"),
    (Seq("c"), "c")
  )

  val ac1 = new AhoCorasick[String, String](dict1)
  val ac2 = new AhoCorasick[String, String](dict2)

  "AhoCorasick" should {
    "find phrases" in {
      var res = Set.empty[(Int, String)]
      ac1.scan(Seq("x", "b", "a", "b", "c", "d", "e", "x").iterator) { (pos, data) => res += ((pos, data)) }
      res === Set((3, "b a b"), (3, "a b"), (4, "a b c"), (4, "b c"), (5, "d"), (6, "a b c d e"), (6, "b c d e"), (6, "d e"))

      res = Set.empty[(Int, String)]
      ac1.scan(Seq("a", "b", "c").iterator) { (pos, data) => res += ((pos, data)) }
      res === Set((1, "a b"), (2, "a b c"), (2, "b c"))

      res = Set.empty[(Int, String)]
      ac2.scan(Seq("a", "b", "c", "a", "d").iterator) { (pos, data) => res += ((pos, data)) }
      res === Set((0, "a"), (2, "c"), (3, "a"))
    }
  }
}

