package com.keepit.inject

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.Provides
import com.keepit.common.time._
import org.joda.time.DateTime
import org.joda.time.LocalDate

case class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
  }

  @Provides
  def dateTime: DateTime = currentDateTime

  @Provides
  def localDate: LocalDate = currentDate

}
