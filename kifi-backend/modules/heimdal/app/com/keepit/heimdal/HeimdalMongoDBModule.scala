package com.keepit.heimdal

import reactivemongo.api.MongoDriver
import reactivemongo.core.actors.Authenticate

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
    val nodeA = current.configuration.getString("mongodb.heimdal.nodeA").get
    val nodeB = current.configuration.getString("mongodb.heimdal.nodeB").get
    val username = current.configuration.getString("mongodb.heimdal.username").get
    val password = current.configuration.getString("mongodb.heimdal.password").get
    val auth = Authenticate("heimdal", username, password)
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA, nodeB), List(auth, auth), 10, Some("UserEventLoggingMongoActorSystem"))
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
