package com.keepit.common.util

import com.keepit.common.util.TestHelpers._
import org.specs2.mutable
import play.api.libs.json._

class TestHelpersTest extends mutable.Specification {
  stopOnFail(false)

  "TestHelpers" should {
    "help match json" in {
      object IHateThatThisIsNecessary {
        final case class A(x: Int, y: String, z: Seq[Int])
        val fmt = Json.format[A]
      }
      import IHateThatThisIsNecessary._

      val a = A(1, "two", List(10, 9, 8))
      val expected = Json.obj(
        "x" -> 1,
        "y" -> "two",
        "z" -> Json.arr(10, 9, 8)
      )
      fmt.writes(a) should matchJson(expected)

      /*
       * All of these will fail with "useful" error messages. Uncomment to check them out.
       */
      // Json.arr(1, 2, 3) should matchJson(expected)
      // Json.obj("x" -> 1, "y" -> "two") must matchJson(expected)
      // Json.obj("x" -> "pqrs") must matchJson(expected)
    }

  }
}
