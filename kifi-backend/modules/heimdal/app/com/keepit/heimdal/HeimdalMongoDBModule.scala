package com.keepit.heimdal

import com.keepit.model._
import reactivemongo.api.MongoDriver

import com.keepit.common.healthcheck.AirbrakeNotifier

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Provides, Singleton }

import play.api.Play.current
import com.keepit.shoebox.ShoeboxServiceClient
import reactivemongo.core.nodeset.Authenticate

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
    new ProdUserEventLoggingRepo(collection, mixpanel, descriptorRepo, shoebox, airbrake)
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

  @Provides @Singleton
  def anonymousEventLoggingRepo(descriptorRepo: AnonymousEventDescriptorRepo, mixpanel: MixpanelClient, airbrake: AirbrakeNotifier): AnonymousEventLoggingRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("AnonymousEventLoggingMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("anonymous_events")
    new ProdAnonymousEventLoggingRepo(collection, mixpanel, descriptorRepo, airbrake)
  }

  @Provides @Singleton
  def anonymousEventDescriptorRepo(cache: AnonymousEventDescriptorNameCache, airbrake: AirbrakeNotifier): AnonymousEventDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("AnonymousEventDescriptorMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("anonymous_event_descriptors")
    new ProdAnonymousEventDescriptorRepo(collection, cache, airbrake)
  }

  @Provides @Singleton
  def visitorEventLoggingRepo(descriptorRepo: VisitorEventDescriptorRepo, mixpanel: MixpanelClient, airbrake: AirbrakeNotifier): VisitorEventLoggingRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("VisitorEventLoggingMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("visitor_events")
    new ProdVisitorEventLoggingRepo(collection, mixpanel, descriptorRepo, airbrake)
  }

  @Provides @Singleton
  def visitorEventDescriptorRepo(cache: VisitorEventDescriptorNameCache, airbrake: AirbrakeNotifier): VisitorEventDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("VisitorEventDescriptorMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("visitor_event_descriptors")
    new ProdVisitorEventDescriptorRepo(collection, cache, airbrake)
  }

  @Provides @Singleton
  def nonUserEventLoggingRepo(descriptorRepo: NonUserEventDescriptorRepo, mixpanel: MixpanelClient, airbrake: AirbrakeNotifier): NonUserEventLoggingRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("NonUserEventLoggingMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("non_user_events")
    new ProdNonUserEventLoggingRepo(collection, mixpanel, descriptorRepo, airbrake)
  }

  @Provides @Singleton
  def nonUserEventDescriptorRepo(cache: NonUserEventDescriptorNameCache, airbrake: AirbrakeNotifier): NonUserEventDescriptorRepo = {
    val (nodeA, nodeB, auth) = getHeimdalCredentials()
    val driver = new MongoDriver
    val connection = driver.connection(List(nodeA), List(auth), 2, Some("NonUserEventDescriptorMongoActorSystem"))
    val db = connection("heimdal")
    val collection = db("non_user_event_descriptors")
    new ProdNonUserEventDescriptorRepo(collection, cache, airbrake)
  }

  @Provides @Singleton
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

  @Provides @Singleton
  def anonymousEventLoggingRepo: AnonymousEventLoggingRepo = new DevAnonymousEventLoggingRepo

  @Provides @Singleton
  def visitorEventLoggingRepo: VisitorEventLoggingRepo = new DevVisitorEventLoggingRepo

  @Provides @Singleton
  def nonUserEventLoggingRepo: NonUserEventLoggingRepo = new DevNonUserEventLoggingRepo

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
