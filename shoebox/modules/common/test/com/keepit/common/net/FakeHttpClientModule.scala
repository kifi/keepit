package com.keepit.common.net

import com.tzavellas.sse.guice.ScalaModule

case class FakeHttpClientModule() extends ScalaModule {
  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient())
  }
}
