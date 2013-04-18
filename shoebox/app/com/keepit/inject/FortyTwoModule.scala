package com.keepit.inject

import scala.slick.session.{Database => SlickDatabase}
import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.{Provides, Inject, Singleton, Provider}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import org.joda.time.{DateTime, LocalDate}
import akka.actor.ActorSystem
import akka.actor.Scheduler
import com.keepit.common.db.SlickModule
import com.keepit.common.db.DbInfo
import play.api.Play
import play.api.db.DB

class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    println("configuring FortyTwoModule")
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      lazy val database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
      lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
      println("loading database driver %s".format(driverName))
    }))
  }

  @Provides
  @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices = FortyTwoServices(clock)

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = {
    system.scheduler
  }

}
