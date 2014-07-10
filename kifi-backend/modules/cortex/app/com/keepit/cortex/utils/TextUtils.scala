package com.keepit.cortex.utils

object TextUtils {

  object TextNormalizer {
    object LowerCaseNormalizer {
      def normalize(text: String): String = {
        text.toLowerCase()
      }
    }
  }

  object TextTokenizer {
    object LowerCaseTokenizer {
      def tokenize(text: String): Seq[String] = {
        TextNormalizer.LowerCaseNormalizer.normalize(text).split("[\\s-]").filter(!_.isEmpty())
      }
    }
  }

}
