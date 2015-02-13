package com.keepit.commanders

import com.keepit.common.strings._

class TwitterMessages {
  /**
   * todo(eishay): before openning it out, should be heavily tested.
   * 140 is twitter max msg length
   * 20 is the urls after shortning
   * 20 is the message overhead " kept to via @kifi"...
   * 140 - 2 * 20 - 20 = 79
   */
  def keepMessage(title: String, keepUrl: String, libName: String, libUrl: String): String = {
    val contentLength = title.length + libName.length
    val totalLength = 20 * 2 + 20 + title.length + libName.length
    if (20 * 2 + 20 + title.length + libName.length <= 140) {
      s"$title $keepUrl kept to $libName $libUrl via @kifi"
    } else {
      val overtext = 79 - (2 * 3) - contentLength //the 3 stands for the "..."
      val maxLibName = (libName.size - overtext / 3).min(20)
      val shortLibName = libName.abbreviate(maxLibName)
      val shortTitle = if (title.size > 79 - shortLibName.size) title.abbreviate(79 - 3 - shortLibName.size) else title
      s"$shortTitle $keepUrl kept to $shortLibName $libUrl via @kifi"
    }
  }

  val TwitterUrlPattern = "https://www.twitter.com/(.*)".r

  def parseHandleFromUrl(url: String): String = {
    url match {
      case TwitterUrlPattern(handle) => s"@$handle"
    }
  }
}
