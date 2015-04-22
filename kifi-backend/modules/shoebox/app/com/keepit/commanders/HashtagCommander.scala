package com.keepit.commanders

import com.keepit.model.Hashtag

class HashtagCommander {

  // Given a string, return a list of all hashtags
  // A hashtag is defined as a substring that starts with '#' and ends at a space or newline
  // note: This function returns the hashtag NAME without the '#'
  def findAllHashtags_old(str: String): Set[String] = {
    var idx = 0
    val hashtags = scala.collection.mutable.Set[String]()
    while (idx < str.length) {
      val currChar = str.charAt(idx)
      if (currChar == '#') {
        val nextSpaceIdx = str.indexOf(' ', idx) // find space or end of line
        val potentialHashtag = if (nextSpaceIdx == -1) { // reached end of line
          str.substring(idx)
        } else {
          str.substring(idx, nextSpaceIdx)
        }
        if (potentialHashtag.length > 1 && potentialHashtag.indexOf("#", 1) == -1) { // potentialHashtag does not contain '#'
          hashtags.add(potentialHashtag.substring(1))
        }
        idx += potentialHashtag.length
      } else {
        idx += 1
      }
    }
    hashtags.toSet
  }

  // look for [
  def findAllHashtags(str: String): Set[String] = {
    var idx = 0
    val hashtags = scala.collection.mutable.Set[String]()

    while (idx < str.length) {
      val currChar = str.charAt(idx)
      if (currChar == '[' && (idx == 0 || (str.charAt(idx - 1) != '\\'))) { // beginning of hashtag
        val closingIdx = str.indexOf(']', idx)
        if (closingIdx > idx) { // end of hashtag
          isValidHashtag(str.substring(idx + 1, closingIdx)) match {
            case Some(hashtagName) =>
              hashtags.add(hashtagName)
            case _ =>
          }
          idx = closingIdx + 1
        } else {
          idx = str.length // no closing ']' in string, so no more potential hashtags
        }
      } else {
        idx += 1
      }
    }
    hashtags.toSet
  }

  // i.e. '#a' '#hashtag' '#asdf'. But cannot be '#'
  private def isValidHashtag(str: String): Option[String] = {
    if (str.startsWith("#") && str.length > 1) {
      Some(str.substring(1))
    } else {
      None
    }
  }

  // Given tag names, append a '#' before it, separated by white spaces
  def appendHashtagsToString_old(str: String, hashtagNames: Set[String]): String = {
    val tagsStr = hashtagNames.filter(_.nonEmpty).map("#" + _).mkString(" ")
    (str + " " + tagsStr).trim
  }

  def appendHashtagsToString(str: String, hashtagNames: Set[String]): String = {
    val tagsStr = hashtagNames.filter(_.nonEmpty).map("[#" + _ + "]").mkString(" ")
    (str + " " + tagsStr).trim
  }

  // given tag names, remove all instances of #tagName from string
  def removeHashtagsFromString_old(str: String, hashtagNames: Set[String]): String = {
    var idx = 0
    var editedStr = str
    while (idx < editedStr.length) {
      val currChar = editedStr.charAt(idx)
      if (currChar == '#') {
        val nextSpaceIdx = editedStr.indexOf(' ', idx) // find space or end of line
        val (potentialHashtag, charsToTruncate) = if (nextSpaceIdx == -1) { // reached end of line
          val hashTag = editedStr.substring(idx)
          (hashTag, hashTag.length)
        } else {
          val hashTag = editedStr.substring(idx, nextSpaceIdx)
          (hashTag, hashTag.length + 1) // remove space as well
        }
        val potentialHashtagName = potentialHashtag.substring(1)
        if (potentialHashtagName.length >= 1 && hashtagNames.contains(potentialHashtagName)) {
          editedStr = editedStr.substring(0, idx) + editedStr.substring(idx + charsToTruncate)
        } else {
          idx += charsToTruncate
        }
      } else {
        idx += 1
      }
    }
    editedStr.trim
  }

  def removeHashtagsFromString(str: String, hashtagNames: Set[String]): String = {
    var idx = 0
    var editedStr = str
    while (idx < editedStr.length) {
      val currChar = editedStr.charAt(idx)
      if (currChar == '[') {
        val closingIdx = editedStr.indexOf(']', idx)
        if (closingIdx > idx) {
          isValidHashtag(editedStr.substring(idx + 1, closingIdx)) match {
            case Some(name) =>
              if (hashtagNames.contains(name)) {
                // remove the space after the tagname if there is one
                val strToKeep = if ((closingIdx + 1 < editedStr.length) && (editedStr.charAt(closingIdx + 1) == ' ')) {
                  editedStr.substring(closingIdx + 2)
                } else {
                  editedStr.substring(closingIdx + 1)
                }
                editedStr = editedStr.substring(0, idx) + strToKeep
              } else {
                idx = closingIdx + 1 // not a tagname in `hashtagNames`
              }
            case _ =>
              idx = closingIdx + 1 // not a valid tag
          }
        } else {
          idx = editedStr.length // no closing ']', there should not be any more hashtags in string
        }
      } else {
        idx += 1
      }
    }
    editedStr.trim
  }

}
