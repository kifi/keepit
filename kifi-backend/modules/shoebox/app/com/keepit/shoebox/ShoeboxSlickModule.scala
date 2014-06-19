package com.keepit.shoebox

import scala.util._
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}


import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem
import com.keepit.model.{UrlPatternRuleRepoImpl, UrlPatternRuleRepo}

case class ShoeboxDbInfo() extends DbInfo {
  def masterDatabase = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  // can't probe for existing (or not) db, must try and possibly fail.
  override def slaveDatabase = Try(SlickDatabase.forDataSource(DB.getDataSource("shoeboxslave")(Play.current))) match {
    case Success(db) =>
      println("loaded slave db")
      Some(db)
    case Failure(e) =>
      println(s"could not load slave db for: $e")
      None
  }
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class ShoeboxSlickModule() extends SlickModule(ShoeboxDbInfo()) {
  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext =
    DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))

  @Provides @Singleton
  def UrlPatternRuleRepoProvider(db: Database, urlPatternRuleRepo: UrlPatternRuleRepoImpl): UrlPatternRuleRepo = {
    db.readWrite { implicit session =>
      urlPatternRuleRepo.loadCache()
    }
    urlPatternRuleRepo
  }
}
