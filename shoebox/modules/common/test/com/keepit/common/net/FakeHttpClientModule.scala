package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule

case class FakeHttpClientModule() extends ScalaModule {
  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient())
  }
}
