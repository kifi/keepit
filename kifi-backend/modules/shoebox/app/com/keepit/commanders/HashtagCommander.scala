package com.keepit.commanders

import com.keepit.model.Hashtag

import scala.util.matching.Regex.Match

class HashtagCommander {

  def findAllHashtagNames(s: String): Set[String] = {
    HashtagRegex.hashTagRe.findAllMatchIn(s).map(_ group 1).map { escapedName =>
      HashtagRegex.backslashUnescapeRe.replaceAllIn(escapedName, "$1")
    } toSet
  }

  def addNewHashtagsToString(str: String, hashtags: Seq[Hashtag]): String = {
    addNewHashtagNamesToString(str, hashtags.map(_.tag))
  }

  def addNewHashtagNamesToString(str: String, hashtagNames: Seq[String]): String = {
    val existingHashtags = findAllHashtagNames(str).map(_.toLowerCase)
    val tagsToAppend = hashtagNames.filterNot(t => existingHashtags.contains(t.toLowerCase))
    appendHashtagNamesToString(str, tagsToAppend)
  }

  // append hashtags to the end of a string, separated by spaces
  def appendHashtagsToString(str: String, hashtags: Seq[Hashtag]): String = {
    val hashtagNames = hashtags.map(_.tag)
    appendHashtagNamesToString(str, hashtagNames).trim
  }

  def appendHashtagNamesToString(str: String, hashtagNames: Seq[String]): String = {
    val tagsStr = hashtagNames.filter(_.nonEmpty).map { tag =>
      "[#" + HashtagRegex.backslashEscapeRe.replaceAllIn(tag, """\\$0""") + "]"
    }.mkString(" ")
    (str + " " + tagsStr).trim
  }

  def removeAllHashtagsFromString(str: String): String = {
    HashtagRegex.hashTagRe.replaceAllIn(str, "").trim
  }

  def removeHashtagsFromString(str: String, hashtags: Set[Hashtag]): String = {
    removeHashtagNamesFromString(str, hashtags.map(_.tag))
  }

  def removeHashtagNamesFromString(str: String, hashtagNames: Set[String]): String = {
    val mapper = (m: Match) => if (hashtagNames.contains(m.group(1))) Some("") else None
    HashtagRegex.hashTagRe.replaceSomeIn(str, mapper).trim
  }
}

object HashtagRegex {
  // matches '[#...]'.
  // A literal '[#' indicates the start of a hashtag
  // A literal ']' indicates the end of the hashtag
  // inside that (...), all ']' & '\' will be escaped to indicate they are part of the hashtag
  val hashTagRe = """\[#((?:\\[\\\]]|[^\]])+)\]""".r
  val backslashEscapeRe = """[\]\\]""".r
  val backslashUnescapeRe = """\\(.)""".r
}
