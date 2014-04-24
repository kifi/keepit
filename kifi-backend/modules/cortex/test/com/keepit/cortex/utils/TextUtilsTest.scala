package com.keepit.cortex.utils

import org.specs2.mutable.Specification

class TextUtilsTest extends Specification {
  "text utils" should {
    "work" in {
      import TextUtils._

      TextNormalizer.LowerCaseNormalizer.normalize("Hello World") === "hello world"
      TextTokenizer.LowerCaseTokenizer.tokenize(" Hello World  ") === Seq("hello", "world")

    }
  }
}
