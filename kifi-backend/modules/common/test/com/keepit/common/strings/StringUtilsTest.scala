package com.keepit.common.strings

import com.keepit.test._
import org.specs2.mutable.Specification

class StringUtilsTest extends Specification with CommonTestInjector {

  "Strings" should {

    "clean line breaks and trim" in {
      "oneword".trimAndRemoveLineBreaks() === "oneword"
      "two words".trimAndRemoveLineBreaks() === "two words"
      "  words trimmimg outside  ".trimAndRemoveLineBreaks() === "words trimmimg outside"
      "two words with two  spaces".trimAndRemoveLineBreaks() === "two words with two spaces"
      """line break
         inside""".trimAndRemoveLineBreaks() === "line break inside"
      """
         line break
         outside
      """.trimAndRemoveLineBreaks() === "line break outside"
    }

  }
}
