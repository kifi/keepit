package com.keepit.inject

import org.scalaquery.session.Database
import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.{Provides, Inject, Singleton, Provider}
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices
import org.joda.time.{DateTime, LocalDate}
import akka.actor.ActorSystem
import akka.actor.Scheduler
import com.keepit.common.db.SlickModule
import com.keepit.common.db.DbInfo
import play.api.Play
import play.api.db.DB
import com.keepit.common.cache.MemcachedCacheModule

class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    println("configuring FortyTwoModule")
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    install(new SlickModule(new DbInfo() {
      //later on we can customize it by the application name
      lazy val database = Database.forDataSource(DB.getDataSource("shoebox")(Play.current))
      lazy val driverName = Play.current.configuration.getString("db.shoebox.driver").get
      println("loading database driver %s".format(driverName))
    }))
    bind[DateTime].toProvider(new Provider[DateTime](){
      override def get(): DateTime = currentDateTime
    })
  }

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
