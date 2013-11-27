package com.keepit.heimdal

import reactivemongo.api.MongoDriver
import reactivemongo.core.actors.Authenticate

import com.keepit.common.healthcheck.AirbrakeNotifier

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{Provides, Singleton}

import play.api.Play.current
import com.keepit.shoebox.ShoeboxServiceClient


trait MongoModule extends ScalaModule


case class ProdMongoModule() extends MongoModule {

  def configure() = {}

  private def getHeimdalCredentials(): (String, String, Authenticate) = {
    val nodeA = current.configuration.getString("mongodb.heimdal.nodeA").get
    val nodeB = current.configuration.getString("mongodb.heimdal.nodeB").get
    val username = current.configuration.getString("mongodb.heimdal.username").get
    val password = current.configuration.getString("mongodb.heimdal.password").get
    val auth = Authenticate("heimdal", username, password)
    (nodeA, nodeB, auth)
  }

  @Singleton
  @Provides
  def userEventLoggingRepo(descriptorRepo: UserEventDescriptorRepo, mixpanel: MixpanelClient, shoebox: ShoeboxServiceClient, airbrake: AirbrakeNotifier): UserEventLoggingRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("UserEventLoggingMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("user_events")
    new ProdUserEventLoggingRepo(collection, mixpanel, descriptorRepo, airbrake)
  }

  @Provides @Singleton
  def userEventDescriptorRepo(cache: UserEventDescriptorNameCache, airbrake: AirbrakeNotifier): UserEventDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("UserEventDescriptorMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("user_event_descriptors")
    new ProdUserEventDescriptorRepo(collection, cache, airbrake)
  }

  @Singleton
  @Provides
  def systemEventLoggingRepo(descriptorRepo: SystemEventDescriptorRepo, mixpanel: MixpanelClient, airbrake: AirbrakeNotifier): SystemEventLoggingRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("SystemEventLoggingMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("system_events")
    new ProdSystemEventLoggingRepo(collection, mixpanel, descriptorRepo, airbrake)
  }


  @Provides @Singleton
  def systemEventDescriptorRepo(cache: SystemEventDescriptorNameCache, airbrake: AirbrakeNotifier): SystemEventDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("SystemEventDescriptorMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("system_event_descriptors")
    new ProdSystemEventDescriptorRepo(collection, cache, airbrake)
  }

  @Singleton
  @Provides
  def metricDescriptorRepo(airbrake: AirbrakeNotifier): MetricDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("MetricDescriptorsMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("metric_descriptors")
    new ProdMetricDescriptorRepo(collection, airbrake)
  }

  @Singleton
  @Provides
  def metricRepoFactory(airbrake: AirbrakeNotifier): MetricRepoFactory = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 5, Some("MetricDescriptorsMongoActorSystem"))
    val db = connection("heimdal")
    new ProdMetricRepoFactory(db, airbrake)
  }

  @Provides @Singleton
  def mixpanel(shoebox: ShoeboxServiceClient): MixpanelClient = {
    val projectToken: String = current.configuration.getString("mixpanel.token").get
    new MixpanelClient(projectToken, shoebox)
  }
}

case class DevMongoModule() extends MongoModule {

  def configure() = {}

  @Singleton
  @Provides
  def userEventLoggingRepo: UserEventLoggingRepo = new DevUserEventLoggingRepo

  @Singleton
  @Provides
  def systemEventLoggingRepo: SystemEventLoggingRepo = new DevSystemEventLoggingRepo

  @Singleton
  @Provides
  def metricDescriptorRepo(airbrake: AirbrakeNotifier): MetricDescriptorRepo = {
    new DevMetricDescriptorRepo(null, airbrake)
  }

  @Singleton
  @Provides
  def metricRepoFactory(): MetricRepoFactory = {
    new DevMetricRepoFactory()
  }
}
