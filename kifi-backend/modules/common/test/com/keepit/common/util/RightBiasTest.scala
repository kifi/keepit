package com.keepit.common.util

import com.keepit.common.util.RightBias._
import org.specs2.mutable.Specification

class RightBiasTest extends Specification {
  "RightBias" should {
    "fragileMap" in {
      def crappyFn(x: Int) = if (x == 14) None else Some(x * x)

      Seq.range(1, 6).fragileMap(x => crappyFn(x).withLeft("hit_undefined")).getRight must beSome(Seq(1, 4, 9, 16, 25))
      Seq.range(1, 15).fragileMap(x => crappyFn(x).withLeft("hit_undefined")).getLeft must beSome("hit_undefined")
    }
  }
}
