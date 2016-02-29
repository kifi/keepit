package com.keepit.common.util

import com.keepit.common.core._
import org.specs2.mutable.Specification

class ImplicitsTest extends Specification {
  "Implicits" should {
    "IterableExtensionOps" in {
      "distinctBy" in {
        Seq(3, 1, 4, 1, 5, 9, 2, 6).distinctBy(_ % 3) === Seq(3, 1, 5)
        Map(1 -> "one", 2 -> "two", 3 -> "three").distinctBy(_._2.length) === Map(1 -> "one", 3 -> "three")
      }
      "mapAccumLeft" in {
        // Extract a monotonically increasing subsequence
        val monotonicallyIncreasing = Seq(3, 1, 4, 1, 5, 9, 2, 6).mapAccumLeft(0) {
          case (m, x) =>
            val mp = Math.max(m, x)
            (mp, Option(x).filter(_ >= m))
        }._2.flatten
        monotonicallyIncreasing === Seq(3, 4, 5, 9)

        // Combine items into chunks
        Seq(
          1 -> "a",
          2 -> "b",
          2 -> "c",
          1 -> "d",
          1 -> "e",
          1 -> "f",
          2 -> "g",
          1 -> "i"
        ).mapAccumLeft(Option.empty[Int]) {
            case (lastGroupOpt, (curGroup, v)) => (
              Some(curGroup),
              if (lastGroupOpt.contains(curGroup)) s"  |- $v" else s"$curGroup +- $v"
            )
          }._2.mkString("\n").trim ===
          """
            |1 +- a
            |2 +- b
            |  |- c
            |1 +- d
            |  |- e
            |  |- f
            |2 +- g
            |1 +- i
          """.stripMargin.trim

        // Take a Seq[Set[T]] and ensure that each (x: T) is only present in a single set
        Seq(Set(1, 2, 3), Set(2, 3, 4), Set(3, 1, 4, 1, 5), Set(1, 5, 9, 2, 6)).mapAccumLeft(Set.empty[Int]) {
          case (seenSoFar, xs) => (seenSoFar ++ xs, xs -- seenSoFar)
        } === (Set(1, 2, 3, 4, 5, 6, 9), Seq(Set(1, 2, 3), Set(4), Set(5), Set(9, 6)))

        // Find the rolling mean of an array
        Seq(1.0, 2.0, 3.0, 4.0, 5.0).mapAccumLeft((0.0, 0)) {
          case ((total, len), x) =>
            val (newTotal, newLen) = (total + x, len + 1)
            ((newTotal, newLen), newTotal / newLen)
        } === ((15.0, 5), Seq(1.0, 1.5, 2.0, 2.5, 3.0))
      }
    }
    "MapExtensionOps" in {
      "mapValuesStrict" in {
        class FakeIOResource {
          var open = true
          def close(): Unit = open = false
          def process(x: Int): Int = if (open) 2 * x else throw new IllegalStateException("Resource has been closed")
        }

        val db1 = new FakeIOResource
        val m1 = Map("a" -> 5, "b" -> 3, "c" -> 1).mapValues(db1.process)
        db1.close()
        m1("a") must throwA[IllegalStateException]

        val db2 = new FakeIOResource
        val m2 = Map("a" -> 5, "b" -> 3, "c" -> 1).mapValuesStrict(db2.process)
        db2.close()
        m2("a") === 10

      }
    }
    "EitherExtensionOps" in {
      "partitionEithers" in {
        Set(Left(1), Left(2), Right("a"), Right("b"), Left(5)).partitionEithers === (Set(1, 2, 5), Set("a", "b"))
        Seq(Left(1), Left(2), Right("a"), Right("b"), Left(5)).partitionEithers === (Seq(1, 2, 5), Seq("a", "b"))
      }
    }
    "RegexExtensionOps" in {
      "findMatchesAndInterstitials" in {
        val rx = """<(\d+)>""".r
        def foo(str: String) = rx.findMatchesAndInterstitials(str).map {
          case Left(s) => s
          case Right(m) => m.group(1)
        }

        foo("") === Seq.empty
        foo("abc") === Seq("abc")
        foo("<15>") === Seq("15")
        foo("<15>abc") === Seq("15", "abc")
        foo("abc<15>") === Seq("abc", "15")
        foo("<10>abc<15>") === Seq("10", "abc", "15")
        foo("<10><15>") === Seq("10", "15")
        foo("10>abc<15>") === Seq("10>abc", "15")
        foo("10>abc<15") === Seq("10>abc<15")
        foo("ab<15>cd<20>ef<25>") === Seq("ab", "15", "cd", "20", "ef", "25")
      }
    }
  }
}
