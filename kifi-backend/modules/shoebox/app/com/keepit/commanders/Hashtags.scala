package com.keepit.commanders

import com.keepit.model.Hashtag

import scala.util.matching.Regex

object Hashtags {

  def findAllHashtagNames(s: String): Set[String] = {
    hashTagRe.findAllMatchIn(s).map(_ group 1).map { escapedName =>
      backslashUnescapeRe.replaceAllIn(escapedName, "$1")
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
      "[#" + backslashEscapeRe.replaceAllIn(tag, """\\$0""") + "]"
    }.mkString(" ")
    (str + " " + tagsStr).trim
  }

  def removeAllHashtagsFromString(str: String): String = {
    hashTagRe.replaceAllIn(str, "").trim
  }

  def removeHashtagsFromString(str: String, hashtags: Set[Hashtag]): String = {
    removeHashtagNamesFromString(str, hashtags.map(_.tag))
  }

  def removeHashtagNamesFromString(str: String, hashtagNames: Set[String]): String = {
    hashTagRe.replaceSomeIn(str, m => if (hashtagNames.contains(m.group(1))) Some("") else None).trim
  }

  // gives back a note with hashtags stripped out & markups unescaped (reverted back to normal)
  def formatMobileNoteV1(note: Option[String]) = {
    note.map { noteStr =>
      val noteWithoutHashtags = Hashtags.removeAllHashtagsFromString(noteStr)

      // todo(jared|aaron): remove false below once notes + hashtags are launched on any platform
      val note2 = if (false && noteWithoutHashtags.nonEmpty && noteWithoutHashtags != noteStr) {
        hashTagRe.replaceAllIn(noteStr, m => {
          Regex.quoteReplacement("#" + backslashUnescapeRe.replaceAllIn(m.group(1), "$1"))
        })
      } else {
        noteWithoutHashtags
      }

      KeepDecorator.unescapeMarkupNotes(note2).trim
    } filter (_.nonEmpty)
  }

  def formatMobileNote(note: Option[String], parseNote: Boolean) = {
    if (parseNote) {
      formatMobileNoteV1(note)
    } else {
      note
    }
  }

  // matches '[#...]'.
  // A literal '[#' indicates the start of a hashtag
  // A literal ']' indicates the end of the hashtag
  // inside that (...), all ']' & '\' will be escaped to indicate they are part of the hashtag
  val hashTagRe = """\[#((?:\\[\\\]]|[^\]])+)\]""".r
  val backslashEscapeRe = """[\]\\]""".r
  val backslashUnescapeRe = """\\(.)""".r

}
