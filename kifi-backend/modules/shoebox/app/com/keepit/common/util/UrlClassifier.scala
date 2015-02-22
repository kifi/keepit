package com.keepit.common.util

import com.keepit.common.logging.Logging

object UrlClassifier {
  val SocialActivityUrls = """(http[s]?://www.swarmapp.com/c/.*)|(http[s]?://runkeeper.com/user/.*/activity/.*)""".r
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
