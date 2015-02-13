package com.keepit.test

import com.keepit.common.net.{ WebService, FakeWebService }
import net.codingwell.scalaguice.ScalaModule

case class FakeWebServiceModule() extends ScalaModule {
  override def configure(): Unit = {
    bind[WebService].to[FakeWebService]
  }
}

