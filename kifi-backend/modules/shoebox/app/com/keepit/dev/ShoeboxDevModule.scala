package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.service._
import com.keepit.common.zookeeper._
import com.keepit.common.logging.Logging
import com.keepit.common.analytics._
import com.keepit.inject.AppScoped
import com.google.inject.{Inject, Provider, Singleton, Provides}
import com.keepit.common.plugin.{SchedulingProperties, SchedulingEnabled}
import play.api.Play._
import com.keepit.common.zookeeper.{Node, ServiceDiscovery}
import com.keepit.common.db.slick.Database
import com.keepit.model.{NormalizedURIRepo, UserRepo}
import com.keepit.search.SearchServiceClient
import com.keepit.common.time.Clock
import com.keepit.common.service.{IpAddress, FortyTwoServices}
import com.google.common.io.Files
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.mail.{MailToKeepPluginImpl, MailToKeepPlugin, MailToKeepActor}
import com.keepit.common.actor.ActorFactory
import com.keepit.common.mail.MailToKeepServerSettings
import com.keepit.classify.DomainTagImportSettings
import scala.Some
import com.keepit.common.zookeeper.Node
import com.keepit.common.amazon.AmazonInstanceId
import com.keepit.learning.topicmodel._

class ShoeboxDevModule extends ScalaModule with Logging {
  def configure() {
    bind[EventPersister].to[FakeEventPersisterImpl].in[AppScoped]
  }

  @Provides
  def globalSchedulingEnabled: SchedulingEnabled =
    (current.configuration.getBoolean("scheduler.enabled").map {
      case true => SchedulingEnabled.Never
      case false => SchedulingEnabled.Always
    }).getOrElse(SchedulingEnabled.Never)

  @Singleton
  @Provides
  def serviceDiscovery: ServiceDiscovery = new ServiceDiscovery {
    def serviceCluster(serviceType: ServiceType): ServiceCluster = new ServiceCluster(serviceType)
    def register() = Node("me")
    def isLeader() = true
  }

  @Singleton
  @Provides
  def schedulingProperties: SchedulingProperties = new SchedulingProperties() {
    def allowScheduling = true
  }

  @Singleton
  @Provides
  def searchUnloadProvider(
      db: Database,
      userRepo: UserRepo,
      normalizedURIRepo: NormalizedURIRepo,
      persistEventProvider: Provider[EventPersister],
      store: MongoEventStore,
      searchClient: SearchServiceClient,
      clock: Clock,
      fortyTwoServices: FortyTwoServices): SearchUnloadListener = {
    current.configuration.getBoolean("event-listener.searchUnload").getOrElse(false) match {
      case true =>  new SearchUnloadListenerImpl(db,userRepo, normalizedURIRepo, persistEventProvider, store, searchClient, clock, fortyTwoServices)
      case false => new FakeSearchUnloadListenerImpl(userRepo, normalizedURIRepo)
    }
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    DomainTagImportSettings(localDir = Files.createTempDir().getAbsolutePath, url = "http://localhost:8000/42.zip")
  }

  @Singleton
  @Provides
  def amazonInstanceInfo: AmazonInstanceInfo =
    new AmazonInstanceInfo(
      instanceId = AmazonInstanceId("i-f168c1a8"),
      localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
      publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
      localIp = IpAddress("10.160.95.26"),
      publicIp = IpAddress("50.18.183.73"),
      instanceType = "c1.medium",
      availabilityZone = "us-west-1b",
      securityGroups = "default",
      amiId = "ami-1bf9de5e",
      amiLaunchIndex = "0"
    )

  @Provides
  @Singleton
  def mailToKeepServerSettingsOpt: Option[MailToKeepServerSettings] =
    for {
      username <- current.configuration.getString("mailtokeep.username")
      password <- current.configuration.getString("mailtokeep.password")
    } yield {
      val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
      val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
      val emailLabel = System.getProperty("user.name")
      MailToKeepServerSettings(
        username = username,
        password = password,
        server = server,
        protocol = protocol,
        emailLabel = Some(emailLabel))
    }

  @Provides
  @Singleton
  def mailToKeepServerSettings: MailToKeepServerSettings = mailToKeepServerSettingsOpt.get

  @AppScoped
  @Provides
  def mailToKeepPlugin(
                        actorFactory: ActorFactory[MailToKeepActor],
                        mailToKeepServerSettings: Option[MailToKeepServerSettings],
                        schedulingProperties: SchedulingProperties): MailToKeepPlugin = {
    mailToKeepServerSettingsOpt match {
      case None => new FakeMailToKeepPlugin(schedulingProperties)
      case _ => new MailToKeepPluginImpl(actorFactory, schedulingProperties)
    }
  }

  @Provides
  @Singleton
  def wordTopicModel: WordTopicModel = {
     val vocabulary: Set[String] = (0 until TopicModelGlobal.numTopics).map{ i => "word%d".format(i)}.toSet
      val wordTopic: Map[String, Array[Double]] = (0 until TopicModelGlobal.numTopics).foldLeft(Map.empty[String, Array[Double]]){
        (m, i) => { val a = new Array[Double](TopicModelGlobal.numTopics); a(i) = 1.0; m + ("word%d".format(i) -> a) }
      }
      val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map{ i => "topic%d".format(i)}.toArray
      print("loading fake topic model")
      new LdaWordTopicModel(vocabulary, wordTopic, topicNames)
  }

  @Provides
  @Singleton
  def topicNameMapper: TopicNameMapper = {
    val topicNames: Array[String] = (0 until TopicModelGlobal.numTopics).map { i => "topic%d".format(i) }.toArray
    new IdentityTopicNameMapper(topicNames)
  }
}

class FakeMailToKeepPlugin @Inject() (val schedulingProperties: SchedulingProperties) extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}
