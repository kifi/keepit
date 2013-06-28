package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule

case class FakeHttpClientModule(requestToResponse: PartialFunction[String, FakeClientResponse]) extends ScalaModule {

  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient(Some(requestToResponse)))
  }
}
