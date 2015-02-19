package com.keepit.commanders

import com.keepit.common.strings._

class TwitterMessages {
  /**
   * todo(eishay): before openning it out, should be heavily tested.
   * 140 is twitter max msg length
   * UrlLen is the urls after shortning
   * 20 is the message overhead " kept to via @kifi"...
   */
  private val UrlLen = 25
  private val Overhead = " kept to  via @kifi".length
  val ContextSpace = 140 - 2 * UrlLen - Overhead

  def keepMessage(title: String, keepUrl: String, libName: String, libUrl: String): String = {
    val contentLength = title.length + libName.length
    val totalLength = UrlLen * 2 + Overhead + title.length + libName.length
    if (UrlLen * 2 + Overhead + title.length + libName.length <= 140) {
      s"$title $keepUrl kept to $libName $libUrl via @kifi"
    } else {
      val overtext = contentLength - ContextSpace - (2 * 3) //the 3 stands for the "..."
      val maxLibName = (overtext / 3).min(20)
      println("maxLibName=" + maxLibName)
      val shortLibName = libName.abbreviate(maxLibName)
      val shortTitle = if (title.size > ContextSpace - shortLibName.size) title.abbreviate(ContextSpace - 3 - shortLibName.size) else title
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
