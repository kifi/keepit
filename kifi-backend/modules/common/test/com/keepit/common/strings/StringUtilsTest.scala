package com.keepit.common.strings

import com.keepit.test._
import org.specs2.mutable.Specification

class StringUtilsTest extends Specification with CommonTestInjector {

  "Strings" should {

    "clean line breaks and trim" in {
      "oneword".trimAndRemoveLineBreaks === "oneword"
      "two words".trimAndRemoveLineBreaks === "two words"
      "  words trimmimg outside  ".trimAndRemoveLineBreaks === "words trimmimg outside"
      "two words with two  spaces".trimAndRemoveLineBreaks === "two words with two spaces"
      """line break
         inside""".trimAndRemoveLineBreaks === "line break inside"
      """
         line break
         outside
      """.trimAndRemoveLineBreaks === "line break outside"
    }

    "replace multiple strings literally" in {
      val escapedUrl = """https:\/\/www.youtube.com\/api\/timedtext?hl=en_US\u0026expire=1425690000\u0026sparams=asr_langs%2Ccaps%2Cv%2Cexpire\u0026signature=366E9E448EA6D9211A9D9C5B35C44E274FFEC9CF.43BC49CD4F78F73245946608324FA95E5F1C360E\u0026v=YQs6IC-vgmo\u0026asr_langs=ko%2Cja%2Cen%2Cru%2Cpt%2Cde%2Cfr%2Cnl%2Cit%2Ces\u0026key=yttt1\u0026caps=asr"""
      val expectedUrl = "https://www.youtube.com/api/timedtext?hl=en_US&expire=1425690000&sparams=asr_langs%2Ccaps%2Cv%2Cexpire&signature=366E9E448EA6D9211A9D9C5B35C44E274FFEC9CF.43BC49CD4F78F73245946608324FA95E5F1C360E&v=YQs6IC-vgmo&asr_langs=ko%2Cja%2Cen%2Cru%2Cpt%2Cde%2Cfr%2Cnl%2Cit%2Ces&key=yttt1&caps=asr"
      escapedUrl.replaceAllLiterally("\\/" -> "/", "\\u0026" -> "&") === expectedUrl
    }
  }
}
