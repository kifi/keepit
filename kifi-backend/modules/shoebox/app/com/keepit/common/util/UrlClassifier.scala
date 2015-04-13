package com.keepit.common.util

import com.keepit.common.logging.Logging

object UrlClassifier {
  val SocialActivityUrls = {
    "(" + Seq(
      """http[s]?://www.swarmapp.com/c/.*""",
      """http[s]?://4sq.com/.*""",
      """http[s]?://www.periscope.tv/.*""",
      """http[s]?://instagram.com/.*""",
      """http[s]?://super.me/p/.*""",
      """http[s]?://mrk.tv/.*""", //http://meerkatapp.co/
      """http[s]?://instagr.am/.*""",
      """http[s]?://shar.es/.*""",
      """http[s]?://twitter.com//.*""", //this one is a bit strange and may take out legit twitter articles. the core issue is people linking to other tweets that have nothing but the tweet and it looks bad
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
