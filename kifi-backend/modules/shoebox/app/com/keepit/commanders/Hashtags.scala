package com.keepit.commanders

import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.model.Hashtag

import scala.util.matching.Regex

object Hashtags {
  def findAllHashtagNames(s: String): Set[String] = {
    hashTagRe.findAllMatchIn(s).map(_ group 1).map { escapedName =>
      backslashUnescapeRe.replaceAllIn(escapedName, "$1")
    }.toSet
  }

  def addHashtagsToString(str: String, hashtags: Seq[Hashtag]): String = {
    addTagsToString(str, hashtags.map(_.tag))
  }

  def addTagsToString(str: String, hashtagNames: Seq[String]): String = {
    val existingHashtags = findAllHashtagNames(str).map(_.toLowerCase)
    val tagsToAppend = hashtagNames.filterNot(t => existingHashtags.contains(t.toLowerCase))
    appendHashtagNamesToString(str, tagsToAppend)
  }

  private def appendHashtagNamesToString(str: String, hashtagNames: Seq[String]): String = {
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

  def format(note: String): DescriptionElements = {
    import DescriptionElements._
    val linkedTags = hashTagRe.findAllMatchIn(note).toList.map { m =>
      val tag = m.group(1)
      tag --> LinkElement(PathCommander.tagSearchPath(tag))
    }
    val surroundingText = hashTagRe.split(note).toList.map(DescriptionElements.fromText)
    assert(surroundingText.length == linkedTags.length + 1)
    DescriptionElements.intersperse(surroundingText, linkedTags)
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

  // Takes an external note, such as from Twitter or any other string "Formatted like #this #winning"
  // and converts to a string "Formatted like [#this] [#winning]"
  def formatExternalNote(note: String): String = {
    externalHashTagRe.replaceAllIn(note, "[$1$2]")
  }

  // matches '[#...]'.
  // A literal '[#' indicates the start of a hashtag
  // A literal ']' indicates the end of the hashtag
  // inside that (...), all ']' & '\' will be escaped to indicate they are part of the hashtag
  private val hashTagRe = """\[#((?:\\[\\\]]|[^\]])+)\]""".r
  private val backslashEscapeRe = """[\]\\]""".r
  private val backslashUnescapeRe = """\\(.)""".r
  private val externalHashTagRe = """(?:\[(#\D[\w\-_ ]+[\w])\])|(#\D[\w\-_]{2,})""".r // Matches [#hash tag] and #hashtags

}
