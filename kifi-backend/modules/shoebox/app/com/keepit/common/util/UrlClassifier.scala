package com.keepit.common.util

import com.keepit.common.logging.Logging

object UrlClassifier {
  val SocialActivityUrls = {
    "(" + Seq(
      """http[s]?://www.swarmapp.com/c/.*""",
      """http[s]?://4sq.com/.*""",
      """http[s]?://www.periscope.tv/.*""",
      """http[s]?://instagram.com/.*""",
      """http[s]?://instagr.am/.*""",
      """http[s]?://runkeeper.com/user/.*/activity/.*""",
      """http[s]?://rnkpr.com/.*"""
    ).map("(" + _ + ")").mkString("|") + ")"
  }.r
}

class UrlClassifier extends Logging {
  def socialActivityUrl(url: String): Boolean = {
    val found = UrlClassifier.SocialActivityUrls.findFirstIn(url)
    found foreach { surl =>
      log.info(s"url [$url] is a social activity matching: $url")
    }
    found.isDefined
  }
}
