package com.keepit.commanders

import com.keepit.common.concurrent.PimpMyFuture._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.common.api.UriShortener
import org.apache.commons.lang3.StringUtils._

import com.keepit.common.strings._

import scala.concurrent.Future

class TwitterMessages @Inject() (
    shortner: UriShortener) {

  /**
   * 140 is twitter max msg length
   */
  private val Overhead = message("", "", "", "").length + 5 //the +5 is extra padding since for an unknown reason twitter is still failing us on content size
  val ContentSpace = 140 - Overhead

  def keepMessage(title: String, keepUrl: String, libName: String, libUrl: String): Future[String] = {
    val contentLength = title.length + libName.length
    if (libUrl.length + keepUrl.length + Overhead + title.length + libName.length <= 140) {
      Future.successful(message(title, keepUrl, libName, libUrl))
    } else {
      val shortMessage = shortner.shorten(keepUrl) map { shortenKeepUrl: String =>
        if (libUrl.length + shortenKeepUrl.length + Overhead + title.length + libName.length <= 140) {
          Future.successful(message(title, shortenKeepUrl, libName, libUrl))
        } else {
          shortner.shorten(libUrl) map { shortenLibUrl =>
            if (shortenLibUrl.length + shortenKeepUrl.length + Overhead + title.length + libName.length <= 140) {
              message(title, shortenKeepUrl, libName, shortenLibUrl)
            } else {
              try {
                val urlsLen = shortenLibUrl.length + shortenKeepUrl.length
                val overtext = contentLength + urlsLen + (2 * 3) - ContentSpace //the 3 stands for the "..."
                assume(overtext >= 0, s"expecting overtext to be a positive number, its $overtext. contentLength = $contentLength, ContentSpace = $ContentSpace, title = $title, libName = $libName")
                val maxLibName = libName.length - (overtext / 3)
                val shortLibName = if (maxLibName < 20) libName else libName.abbreviate(maxLibName)
                val shortTitle = if (title.size > ContentSpace - urlsLen - shortLibName.size) {
                  title.abbreviate(ContentSpace - urlsLen - 3 - shortLibName.size)
                } else title
                message(shortTitle, shortenKeepUrl, shortLibName, shortenLibUrl)
              } catch {
                case e: Exception =>
                  throw new Exception(s"could not create a keep message from [$title] [$shortenKeepUrl] [$libName] [$shortenLibUrl]", e)
              }
            }
          }
        }
      }
      shortMessage.flatten
    }
  }

  private def message(shortTitle: String, keepUrl: String, shortLibName: String, libUrl: String) = s"${normalizeSpace(shortTitle)} $keepUrl kept to ${normalizeSpace(shortLibName)} $libUrl via @kifi"

  val TwitterUrlPattern = "https://www.twitter.com/(.*)".r

  def parseHandleFromUrl(url: String): String = {
    url match {
      case TwitterUrlPattern(handle) => s"@$handle"
    }
  }
}
