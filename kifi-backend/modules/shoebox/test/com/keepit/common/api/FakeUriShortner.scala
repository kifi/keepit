package com.keepit.common.api

import scala.concurrent.Future

class FakeUriShortner extends UriShortener {
  def shorten(uri: String): Future[String] = Future.successful("http://goo.gl/uxgdgy")
}
