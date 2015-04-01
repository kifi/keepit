package com.keepit.realtime

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

case class FakeAppBoyModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def urbanAirshipClient(client: FakeAppBoyClient): AppBoyClient = client

  @Provides
  @Singleton
  def fakeUrbanAirshipClient(): FakeAppBoyClient = {
    new FakeAppBoyClient()
  }
}

