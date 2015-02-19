package com.keepit.commanders

import org.apache.commons.lang3.StringUtils._

import com.keepit.common.strings._

class TwitterMessages {
  /**
   * 140 is twitter max msg length
   * UrlLen is the urls after shortning
   */
  private val UrlLen = 25
  private val Overhead = message("", "", "", "").length
  val ContentSpace = 140 - 2 * UrlLen - Overhead

  def keepMessage(title: String, keepUrl: String, libName: String, libUrl: String): String = try {
    val contentLength = title.length + libName.length
    if (UrlLen * 2 + Overhead + title.length + libName.length <= 140) {
      message(title, keepUrl, libName, libUrl)
    } else {
      val overtext = contentLength - ContentSpace - (2 * 3) //the 3 stands for the "..."
      assume(overtext >= 0, s"expecting overtext to be a positive number, its $overtext")
      val maxLibName = (libName.length - (overtext / 3)).min(20)
      val shortLibName = libName.abbreviate(maxLibName)
      val shortTitle = if (title.size > ContentSpace - shortLibName.size) title.abbreviate(ContentSpace - 3 - shortLibName.size) else title
      message(shortTitle, keepUrl, shortLibName, libUrl)
    }
  } catch {
    case e: Exception =>
      throw new Exception(s"could not create a keep message from [$title] [$keepUrl] [$libName] [$libUrl]", e)
  }

  private def message(shortTitle: String, keepUrl: String, shortLibName: String, libUrl: String) = s"${normalizeSpace(shortTitle)} $keepUrl kept to ${normalizeSpace(shortLibName)} $libUrl via @kifi"

  val TwitterUrlPattern = "https://www.twitter.com/(.*)".r

  def parseHandleFromUrl(url: String): String = {
    url match {
      case TwitterUrlPattern(handle) => s"@$handle"
    }
  }
}
