package com.keepit.commanders

import com.keepit.model.Hashtag

class HashtagCommander {

  def findAllHashtagNames(s: String): Set[String] = {
    HashtagRe.hashTagRe.findAllMatchIn(s).map(_ group 1).map { escapedName =>
      HashtagRe.backslashUnescapeRe.replaceAllIn(escapedName, "$1")
    } toSet
  }

  // append hashtags to the end of a string, separated by spaces
  def appendHashtagsToString(str: String, hashtags: Seq[Hashtag]): String = {
    val hashtagNames = hashtags.map(_.tag)
    appendHashtagNamesToString(str, hashtagNames).trim
  }

  def appendHashtagNamesToString(str: String, hashtagNames: Seq[String]): String = {
    val tagsStr = hashtagNames.filter(_.nonEmpty).map { tag =>
      "[#" + HashtagRe.backslashEscapeRe.replaceAllIn(tag, """\\$0""") + "]"
    }.mkString(" ")
    (str + " " + tagsStr).trim
  }

  def removeHashtagsFromString(str: String): String = {
    HashtagRe.hashTagRe.replaceAllIn(str, "").trim
  }
}

object HashtagRe {
  // matches '[#...]'.
  // A literal '[#' indicates the start of a hashtag
  // A literal ']' indicates the end of the hashtag
  // inside that (...), all ']' & '\' will be escaped to indicate they are part of the hashtag
  val hashTagRe = """\[#((?:\\[\\\]]|[^\]])+)\]""".r
  val backslashEscapeRe = """[\]\\]""".r
  val backslashUnescapeRe = """\\(.)""".r
}
