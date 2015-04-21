package com.keepit.commanders

import com.keepit.model.Hashtag

class HashtagCommander {

  // Given a string, return a list of all hashtags
  // A hashtag is defined as a substring that starts with '#' and ends at a space or newline
  // note: This function returns the hashtag NAME without the '#'
  def findAllHashtags(str: String): Seq[String] = {
    var idx = 0
    var hashtags = Seq.empty[String]
    while (idx < str.length) {
      val currChar = str.charAt(idx)
      if (currChar == '#') {
        // find space or end of line
        val nextSpaceIdx = str.indexOf(' ', idx)
        val potentialHashtag = if (nextSpaceIdx == -1) { // reached end of line
          str.substring(idx)
        } else {
          str.substring(idx, nextSpaceIdx)
        }
        if (potentialHashtag.length > 1 && potentialHashtag.indexOf("#", 1) == -1) { // potentialHashtag contains '#'
          hashtags = hashtags :+ potentialHashtag.substring(1)
        }
        idx = idx + potentialHashtag.length
      } else {
        idx += 1
      }
    }
    hashtags
  }

  def appendHashtagsToString(str: String, hashtagNames: Seq[String]) = {

  }

  def removeHashtagsFromString(str: String, hashtagNames: Seq[String]) = {

  }

}
