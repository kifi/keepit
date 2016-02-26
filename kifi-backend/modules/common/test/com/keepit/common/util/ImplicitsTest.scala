package com.keepit.common.util

import com.keepit.common.core.iterableExtensionOps
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

        // Take a Seq of Sets and ensure that each element is only present in a single set
        Seq(Set(1, 2, 3), Set(2, 3, 4), Set(3, 1, 4, 1, 5), Set(1, 5, 9, 2, 6)).mapAccumLeft(Set.empty[Int]) {
          case (seenSoFar, xs) => (seenSoFar ++ xs, xs -- seenSoFar)
        }._2 === Seq(Set(1, 2, 3), Set(4), Set(5), Set(9, 6))
      }
    }
  }
}
