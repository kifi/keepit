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
      """https?://.*\.amazonaws\.com/.*""", // like https://s3.amazonaws.com/uncatagorized/Joel+Geier+-+Confronting+the+Right+What+Works+What+Doesn%27t+and+Why.mp3
      """https?://yfrog\.com/.*""", // like http://yfrog.com/nuahrypj
      """https?://*riffiti\.com/.*""", //download sites
      """https?://ift\.tt/.*""", //IFTTT has lots of bad dead urls
      """https?://dsh\.re/.*""", //screenshot sharing
      """https?://shar\.es/.*""",
      """https?://fb\.me/.*""", //facebook shortner, looks very bad on the library
      """https?://blab\.im/.*""", //some real time chat like periscope
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
