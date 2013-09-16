package com.keepit.heimdal

import reactivemongo.api.MongoDriver

import com.keepit.common.healthcheck.{HealthcheckPlugin}

import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.{Provides, Singleton}

import play.api.Play.current


trait MongoModule extends ScalaModule


case class ProdMongoModule() extends MongoModule {

  def configure() = {}

  @Singleton
  @Provides
  def userEventLoggingRepo(healthcheckPlugin: HealthcheckPlugin): UserEventLoggingRepo = {
    val config = current.configuration.getString("mongodb.main").get
    val driver = new MongoDriver
    val connection = driver.connection(List(config))
    val db = connection("heimdal")
    val collection = db("user_events")
    new ProdUserEventLoggingRepo(collection, healthcheckPlugin)
  }

}

case class DevMongoModule() extends MongoModule {

  def configure() = {}

  @Singleton
  @Provides
  def userEventLoggingRepo(healthcheckPlugin: HealthcheckPlugin): UserEventLoggingRepo = {
    new DevUserEventLoggingRepo(null, healthcheckPlugin)
  }

}
