package com.keepit.search.util

import org.specs2.mutable._
import com.keepit.macros.Location
import com.keepit.common.logging.Logging

class LocationTest extends Specification with Logging {

  class Tst1 {
    def log(a: Int)(implicit loc: Location): String = s"at ${loc.location}, value=$a"
  }

  class Tst2(tst1: Tst1) {
    def test(n: Int): String = {
      tst1.log(n)
    }
  }

  class Tst3(tst1: Tst1) {
    def test(n: Int): String = { tst1.log(n) }
  }

  class Tst4(tst2: Tst2) {
    def test(n: Int): String = { tst2.test(n) }
  }

  class Tst5(tst3: Tst3) {

    def test(n: Int): String = { tst3.test(n) }
  }

  "Location" should {
    "capture the location in the source code" in {
      val t1 = new Tst1()
      val t2 = new Tst2(t1)
      val t3 = new Tst3(t1)
      val t4 = new Tst4(t2)
      val t5 = new Tst5(t3)

      var loc = t1.log(1)
      println(s"\n\t\t $loc")
      loc = t2.test(1)
      println(s"\n\t\t $loc")
      loc = t3.test(1)
      println(s"\n\t\t $loc")
      loc = t4.test(1)
      println(s"\n\t\t $loc")
      loc = t5.test(1)
      println(s"\n\t\t $loc")
      loc = t1.log(2)
      println(s"\n\t\t $loc")

      1===1
    }
  }
}
