package com.keepit.common.net

import com.keepit.common.amazon._
import com.keepit.common.service._
import com.google.inject._
import net.codingwell.scalaguice.ScalaModule

case class FakeHttpClientModule(requestToResponse: PartialFunction[String, FakeClientResponse]) extends ScalaModule {

  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient(Some(requestToResponse)))
  }
  
}
