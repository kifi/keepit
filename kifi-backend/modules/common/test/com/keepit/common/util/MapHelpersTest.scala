package com.keepit.common.util

import org.specs2.mutable.Specification

class MapHelpersTest extends Specification {
  "MapHelpers" should {
    "union maps" in {
      "without an operator" in {
        val maps = Seq(
          Map(1 -> "one", 2 -> "two"),
          Map(3 -> "three", 4 -> "four"),
          Map(5 -> "five", 1 -> "asdf")
        )
        MapHelpers.unions(maps) === Map(1 -> "one", 2 -> "two", 3 -> "three", 4 -> "four", 5 -> "five")
      }
      "with an operator" in {
        val maps = Seq(
          Map(1 -> true, 2 -> false, 3 -> false),
          Map(1 -> false, 2 -> true, 3 -> false),
          Map(1 -> false, 2 -> false, 3 -> true),
          Map(4 -> false)
        )
        MapHelpers.unionsWith[Int, Boolean](_ || _)(maps) === Map(1 -> true, 2 -> true, 3 -> true, 4 -> false)
      }
    }
  }
}
