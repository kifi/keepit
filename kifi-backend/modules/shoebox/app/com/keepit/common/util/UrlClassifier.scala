package com.keepit.common.util

import com.keepit.common.logging.Logging

object UrlClassifier {
  val SocialActivityUrls = {
    "(" + Seq(
      """https?://www.swarmapp\.com/c/.*""",
      """https?://4sq\.com/.*""",
      """https?://www.periscope\.tv/.*""",
      """https?://instagram\.com/.*""",
      """https?://super\.me/p/.*""",
      """https?://mrk\.tv/.*""", //http://meerkatapp.co/
      """https?://instagr\.am/.*""",
      """https?://shar\.es/.*""",
      """https?://twitter\.com//.*""", //this one is a bit strange and may take out legit twitter articles. the core issue is people linking to other tweets that have nothing but the tweet and it looks bad
      """https?://runkeeper\.com/user/.*/activity/.*""",
      """https?://techmeme\.com/.*""",
      """https?://rnkpr\.com/.*"""
    ).map("(" + _ + ")").mkString("|") + ")"
  }.r

  val slackFileUrl = """https?://.*\.slack\.com/files/.*""".r
  val slackArchiveUrl = """https?://.*\.slack\.com/archives/.*""".r
}

class UrlClassifier extends Logging {
  def isSocialActivity(url: String): Boolean = {
    val found = UrlClassifier.SocialActivityUrls.findFirstIn(url)
    found foreach { surl =>
      log.info(s"url [$url] is a social activity matching: $url")
    }
    found.isDefined
  }

  def isSlackFile(url: String): Boolean = UrlClassifier.slackFileUrl.findFirstIn(url).isDefined
  def isSlackArchivedMessage(url: String): Boolean = UrlClassifier.slackArchiveUrl.findFirstIn(url).isDefined
}
