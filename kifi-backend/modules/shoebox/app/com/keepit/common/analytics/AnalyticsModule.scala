package com.keepit.common.analytics

import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.model.{NormalizedURIRepo, UserRepo}
import com.keepit.search.SearchServiceClient
import play.api.Play._

trait AnalyticsModule extends ScalaModule

case class ProdAnalyticsModule() extends AnalyticsModule {

  def configure() {
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[UsefulPageListener]
    listenerBinder.addBinding.to[SliderShownListener]
  }
}

case class DevAnalyticsModule() extends AnalyticsModule {

  def configure() {
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[UsefulPageListener]
    listenerBinder.addBinding.to[SliderShownListener]
  }
}
