package com.keepit.curator

import com.keepit.common.service.{ ServiceClient, ServiceType }
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{ HttpClient, CallTimeouts }
import com.keepit.common.routes.Curator
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.curator.model._

import scala.concurrent.{ ExecutionContext, Future }
import play.api.libs.json._

trait CuratorServiceClient extends ServiceClient {
  final val serviceType = ServiceType.CURATOR

  def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[URIRecoResults]
  def topPublicRecos(userId: Option[Id[User]]): Future[Seq[RecoInfo]]
  def generalRecos(): Future[Seq[RecoInfo]]
  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean]
  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean]
  def triggerEmailToUser(code: String, userId: Id[User]): Future[String]
  def refreshUserRecos(userId: Id[User]): Future[Unit]
  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None, context: Option[String]): Future[LibraryRecoResults]
  def refreshLibraryRecos(userId: Id[User], await: Boolean = false, selectionParams: Option[LibraryRecoSelectionParams] = None): Future[Unit]
  def notifyLibraryRecosDelivered(userId: Id[User], libraryIds: Set[Id[Library]], source: RecommendationSource, subSource: RecommendationSubSource): Future[Unit]
  def ingestPersonaRecos(userId: Id[User], personaIds: Seq[Id[Persona]], reverseIngestion: Boolean = false): Future[Unit]
  def examineUserFeedbackCounter(userId: Id[User]): Future[(Seq[UserFeedbackCountView], Seq[UserFeedbackCountView])] // votes and signals
}

class CuratorServiceClientImpl(
    override val serviceCluster: ServiceCluster,
    override val httpClient: HttpClient,
    implicit val defaultContext: ExecutionContext,
    val airbrakeNotifier: AirbrakeNotifier) extends CuratorServiceClient {

  val longTimeout = CallTimeouts(responseTimeout = Some(30000), maxWaitTime = Some(10000), maxJsonParseTime = Some(10000))

  def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[URIRecoResults] = {
    val payload = Json.obj(
      "source" -> source,
      "subSource" -> subSource,
      "more" -> more,
      "recencyWeight" -> recencyWeight,
      "context" -> context
    )
    call(Curator.internal.topRecos(userId), body = payload).map { response =>
      val js = response.json
      js.validate[URIRecoResults] match {
        case res: JsSuccess[URIRecoResults] => res.get
        case err: JsError =>
          val recos = js.as[Seq[RecoInfo]]
          URIRecoResults(recos, "")
      }
    }
  }

  def topPublicRecos(userId: Option[Id[User]]): Future[Seq[RecoInfo]] = {
    call(Curator.internal.topPublicRecos(userId)).map { response =>
      response.json.as[Seq[RecoInfo]]
    }
  }

  def generalRecos(): Future[Seq[RecoInfo]] = {
    call(Curator.internal.generalRecos()).map { response =>
      response.json.as[Seq[RecoInfo]]
    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    call(Curator.internal.updateUriRecommendationFeedback(userId, uriId), body = Json.toJson(feedback), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }

  def updateLibraryRecommendationFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean] = {
    call(Curator.internal.updateLibraryRecommendationFeedback(userId, libraryId), body = Json.toJson(feedback), callTimeouts = longTimeout).map(response =>
      response.json.as[Boolean]
    )
  }

  def triggerEmailToUser(code: String, userId: Id[User]) = {
    call(Curator.internal.triggerEmailToUser(code, userId), callTimeouts = longTimeout).map { response =>
      response.json.as[String]
    }
  }

  def refreshUserRecos(userId: Id[User]): Future[Unit] = {
    callLeader(Curator.internal.refreshUserRecos(userId), callTimeouts = longTimeout).map { x => }
  }

  def topLibraryRecos(userId: Id[User], limit: Option[Int] = None, context: Option[String]): Future[LibraryRecoResults] = {
    val payload = Json.obj("context" -> JsString(context.getOrElse("")))
    call(Curator.internal.topLibraryRecos(userId, limit), payload).map { response =>
      val js = response.json
      js.validate[LibraryRecoResults] match {
        case res: JsSuccess[LibraryRecoResults] => res.get
        case err: JsError =>
          val recos = js.as[Seq[LibraryRecoInfo]]
          LibraryRecoResults(recos, "")
      }
    }
  }

  def refreshLibraryRecos(userId: Id[User], await: Boolean = false, selectionParams: Option[LibraryRecoSelectionParams] = None) = {
    val payload = Json.toJson(selectionParams)
    call(Curator.internal.refreshLibraryRecos(userId, await), body = payload).map { _ => Unit }
  }

  def notifyLibraryRecosDelivered(userId: Id[User], libraryIds: Set[Id[Library]], source: RecommendationSource, subSource: RecommendationSubSource) = {
    val payload = Json.obj(
      "libraryIds" -> libraryIds,
      "source" -> source,
      "subSource" -> subSource
    )
    call(Curator.internal.notifyLibraryRecosDelivered(userId), body = payload).map { _ => Unit }
  }

  def ingestPersonaRecos(userId: Id[User], personaIds: Seq[Id[Persona]], reverseIngestion: Boolean = false): Future[Unit] = {
    val payload = Json.obj("personaIds" -> personaIds)
    call(Curator.internal.ingestPersonaRecos(userId, reverseIngestion), payload).map { _ => Unit }
  }

  def examineUserFeedbackCounter(userId: Id[User]): Future[(Seq[UserFeedbackCountView], Seq[UserFeedbackCountView])] = {
    call(Curator.internal.examineUserFeedbackCounter(userId)).map { r =>
      r.json match {
        case JsNull => (Seq(), Seq())
        case js =>
          ((js \ "votes").as[Seq[UserFeedbackCountView]], (js \ "signals").as[Seq[UserFeedbackCountView]])
      }
    }
  }
}
