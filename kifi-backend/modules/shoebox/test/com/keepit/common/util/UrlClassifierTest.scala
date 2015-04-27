package com.keepit.common.util

import org.specs2.mutable.Specification

class UrlClassifierTest extends Specification {

  "UrlClassifier" should {
    "socialActivityUrl" in {
      val classifier = new UrlClassifier()
      classifier.socialActivityUrl("http://kifi.com") === false
      classifier.socialActivityUrl("http://runkeeper.com/user/antonymd/activity/257087955?&activityList=false&tripIdBase36=492ac3&channel=web.activity.shorturl") === true
      classifier.socialActivityUrl("https://www.swarmapp.com/c/jtR6e30EcD5") === true
      classifier.socialActivityUrl("http://rnkpr.com/a8k288q") === true
      classifier.socialActivityUrl("https://super.me/p/tPS8") === true
    }
  }
}
