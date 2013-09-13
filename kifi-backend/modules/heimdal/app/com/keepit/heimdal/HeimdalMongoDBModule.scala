package com.keepit.heimdal

import reactivemongo.api.MongoDriver

import com.keepit.common.healthcheck.{HealthcheckPlugin}

import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.{Provides, Singleton}


trait MongoModule extends ScalaModule


trait ProdMongoModule extends MongoModule {

  @Singleton
  @Provides
  def userEventLoggingRepo(healthcheckPlugin: HealthcheckPlugin): UserEventLoggingRepo = {
    val driver = new MongoDriver
    val connection = driver.connection(List("mongodb://fortytwo:keepmyeventssecure@ds045508-a0.mongolab.com:45508,ds045508-a1.mongolab.com:45508/heimdal"))  //temporary! will be in config file
    val db = connection("heimdal")
    val collection = db("user_events")
    new ProdUserEventLoggingRepo(collection, healthcheckPlugin)
  }

}
