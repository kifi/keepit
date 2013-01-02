package com.keepit.inject

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.{Provides, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.ActorSystem
import akka.actor.Scheduler
import com.keepit.common.db.SlickModule
import com.keepit.common.db.DbInfo
import play.api.Play

case class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      def url = Play.current.configuration.getString("db.shoebox.url").get
      def driver = Play.current.configuration.getString("db.shoebox.driver").get
      override def user = Play.current.configuration.getString("db.shoebox.user").getOrElse(null)
      override def password = Play.current.configuration.getString("db.shoebox.password").getOrElse(null)
    }))
  }

  @Provides
  def dateTime: DateTime = currentDateTime

  @Provides
  @Singleton
  def fortyTwoServices(dateTime: DateTime): FortyTwoServices = FortyTwoServices(dateTime)

  @Provides
  def localDate: LocalDate = currentDate

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = {
    system.scheduler
  }

}
