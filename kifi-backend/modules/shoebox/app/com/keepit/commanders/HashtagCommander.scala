package com.keepit.commanders

import com.keepit.model.Hashtag

class HashtagCommander {

  // look for [
  def findAllHashtagNames(str: String): Set[String] = {
    var idx = 0
    val hashtags = scala.collection.mutable.Set[String]()

    while (idx < str.length) {
      val currChar = str.charAt(idx)
      if (currChar == '[' && (idx == 0 || (str.charAt(idx - 1) != '\\'))) { // beginning of hashtag
        val closingIdx = str.indexOf(']', idx)
        if (closingIdx > idx) { // end of hashtag
          isValidHashtagName(str.substring(idx + 1, closingIdx)) match {
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
  private def isValidHashtagName(str: String): Option[String] = {
    if (str.startsWith("#") && str.length > 1) {
      Some(str.substring(1))
    } else {
      None
    }
  }

  def appendHashtagsToString(str: String, hashtags: Set[Hashtag]): String = {
    val hashtagNames = hashtags.map(_.tag)
    appendHashtagNamesToString(str, hashtagNames)
  }

  def appendHashtagNamesToString(str: String, hashtagNames: Set[String]): String = {
    val tagsStr = hashtagNames.filter(_.nonEmpty).map("[#" + _ + "]").mkString(" ")
    (str + " " + tagsStr).trim
  }

  def removeHashtagsFromString(str: String, hashtags: Set[Hashtag]): String = {
    val hashtagNames = hashtags.map(_.tag)
    removeHashtagNamesFromString(str, hashtagNames)
  }

  def removeHashtagNamesFromString(str: String, hashtagNames: Set[String]): String = {
    var idx = 0
    var editedStr = str
    while (idx < editedStr.length) {
      val currChar = editedStr.charAt(idx)
      if (currChar == '[') {
        val closingIdx = editedStr.indexOf(']', idx)
        if (closingIdx > idx) {
          isValidHashtagName(editedStr.substring(idx + 1, closingIdx)) match {
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
