package com.keepit.realtime

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

case class FakeUrbanAirshipModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def urbanAirshipClient(client: FakeUrbanAirshipClient): UrbanAirshipClient = client

  @Provides
  @Singleton
  def fakeUrbanAirshipClient(): FakeUrbanAirshipClient = {
    new FakeUrbanAirshipClient()
  }
}

