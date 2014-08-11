package com.keepit.heimdal

import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.service.ServiceType
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.zookeeper.ServiceCluster
import org.joda.time.DateTime

import scala.concurrent.{ Future, Promise }

import play.api.libs.json.{ JsArray, Json, JsObject }

import com.google.inject.util.Providers
import com.keepit.common.actor.FakeScheduler

class FakeHeimdalServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends HeimdalServiceClient {
  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), new FakeScheduler(), () => {})
  protected def httpClient: com.keepit.common.net.HttpClient = ???

  var eventsRecorded: Int = 0

  def trackEvent(event: HeimdalEvent): Unit = synchronized {
    eventsRecorded = eventsRecorded + 1
  }

  def eventCount: Int = eventsRecorded

  def getMetricData[E <: HeimdalEvent: HeimdalEventCompanion](name: String): Future[JsObject] = Promise.successful(Json.obj()).future

  def updateMetrics(): Unit = {}

  def getRawEvents[E <: HeimdalEvent](window: Int, limit: Int, events: EventType*)(implicit companion: HeimdalEventCompanion[E]): Future[JsArray] = Future.successful(Json.arr())

  def getEventDescriptors[E <: HeimdalEvent](implicit companion: HeimdalEventCompanion[E]): Future[Seq[EventDescriptor]] = Future.successful(Seq.empty)

  def updateEventDescriptors[E <: HeimdalEvent](eventDescriptors: Seq[EventDescriptor])(implicit companion: HeimdalEventCompanion[E]): Future[Int] = Future.successful(0)

  def deleteUser(userId: Id[User]): Unit = {}

  def incrementUserProperties(userId: Id[User], increments: (String, Double)*): Unit = {}

  def setUserProperties(userId: Id[User], properties: (String, ContextData)*): Unit = {}

  def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = {}

  def getLastDelightedAnswerDate(userId: Id[User]): Future[Option[DateTime]] = Future.successful(None)

  def postDelightedAnswer(userRegistrationInfo: DelightedUserRegistrationInfo, answer: BasicDelightedAnswer): Future[Option[BasicDelightedAnswer]] = Future.successful(None)

  def cancelDelightedSurvey(userRegistrationInfo: DelightedUserRegistrationInfo): Future[Boolean] = Future.successful(true)

  def getPagedKeepDiscoveries(page: Int, size: Int): Future[Seq[KeepDiscovery]] = Future.successful(Seq.empty)

  def getDiscoveryCountByKeeper(userId: Id[User]): Future[Int] = Future.successful(0)

  def getKeepAttributionInfo(userId: Id[User]): Future[UserKeepAttributionInfo] = Future.successful(UserKeepAttributionInfo(Id[User](1), 0, 0, 0, 0, 0))

  def getPagedReKeeps(page: Int, size: Int): Future[Seq[ReKeep]] = Future.successful(Seq.empty)

  def processKifiHit(clicker: Id[User], hit: SanitizedKifiHit): Future[Unit] = Future.successful[Unit]()

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = Future.successful[Unit]()
}
