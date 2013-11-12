package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.healthcheck._
import com.keepit.common.net.HttpClient

case class FakeSearchServiceClientModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def searchServiceClient(): SearchServiceClient = new FakeSearchServiceClient()
}
