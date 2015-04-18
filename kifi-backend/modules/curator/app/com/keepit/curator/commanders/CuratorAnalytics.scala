package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.RecommendationUserAction
import com.keepit.curator.feedback.UserRecoFeedbackTrackingCommander
import com.keepit.curator.model._
import com.keepit.heimdal.{ ContextList, SimpleContextData, HeimdalContext, ContextData, UserEventTypes, HeimdalContextBuilderFactory, UserEvent, HeimdalServiceClient }
import com.keepit.model.{ LibraryRecommendationFeedback, Library, NormalizedURI, User, UriRecommendationFeedback, ExperimentType }
import com.keepit.common.logging.Logging
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class CuratorAnalytics @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    libraryRecoRepo: LibraryRecommendationRepo,
    uriRecoFeedbackRepo: UriRecoFeedbackRepo,
    fbTrackingCmdr: UserRecoFeedbackTrackingCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander) extends Logging {

  def trackDeliveredItems[E](items: Seq[E], sourceOpt: Option[RecommendationSource], subSourceOpt: Option[RecommendationSubSource] = None)(
    implicit f: E => (RecommendationSource, RecommendationSubSource) => RecommendationUserActionContext): Unit = {
    val source = sourceOpt.getOrElse(RecommendationSource.Unknown)
    val subSource = subSourceOpt.getOrElse(RecommendationSubSource.Unknown)
    items.foreach { item =>
      val context: RecommendationUserActionContext = f(item)(source, subSource)
      new SafeFuture(toHeimdalEvent(context).map(heimdal.trackEvent))
    }
  }

  private def addFeedbackToLearningLoop(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Unit = {
    val recoItemOpt = db.readOnlyReplica { implicit s => uriRecoRepo.getByUriAndUserId(uriId, userId, None) }

    if (recoItemOpt.exists(r => r.delivered > 0)) {
      val dumpRecordOpt = UriRecoFeedback.fromUserFeedback(userId, uriId, feedback)
      dumpRecordOpt.foreach { r => db.readWrite { implicit s => uriRecoFeedbackRepo.save(r) } }

      recoItemOpt.foreach { item =>
        dumpRecordOpt.map { _.feedback }.foreach { fbValue =>
          fbTrackingCmdr.trackFeedback(item, fbValue)
        }
      }
    } else {
      // purely a user keep action. not from recommendation
    }
  }

  def trackUserFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Unit = {
    log.info(s"[analytics] Received user $userId reco feedback on $uriId to track: $feedback")
    addFeedbackToLearningLoop(userId, uriId, feedback)

    val contexts = toRecoUserActionContexts(userId, uriId, feedback)
    contexts.foreach { context =>
      new SafeFuture(toHeimdalEvent(context).map { event =>
        log.info(s"[analytics] Sending event: $event")
        heimdal.trackEvent(event)
      })
    }
    if (contexts.isEmpty) log.info(s"[analytics] nothing to do for user $userId reco feedback on $uriId to track: $feedback")
  }

  def trackUserFeedback(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Unit = {
    log.info(s"[analytics] Received user $userId library reco feedback on $libraryId to track: $feedback")
    val contexts = toRecoUserActionContexts(userId, libraryId, feedback)
    contexts.foreach { context =>
      new SafeFuture(toHeimdalEvent(context).map { event =>
        log.info(s"[analytics] Sending event: $event")
        heimdal.trackEvent(event)
      })
    }
    if (contexts.isEmpty) log.info(s"[analytics] nothing to do for user $userId reco feedback on $libraryId to track: $feedback")
  }

  private def toRecoUserActionContexts(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Seq[RecommendationUserActionContext] = {
    val modelOpt = db.readOnlyReplica { implicit s => uriRecoRepo.getByUriAndUserId(uriId, userId, None) }

    if (modelOpt.exists(r => r.delivered > 0)) {
      val masterScore = modelOpt.get.masterScore.toInt
      val keepers = modelOpt.get.attribution.user.map { _.friends }
      val source = feedback.source.getOrElse(RecommendationSource.Unknown)
      val subSource = feedback.subSource.getOrElse(RecommendationSubSource.Unknown)

      var contexts = List.empty[RecommendationUserActionContext]

      feedback.clicked.filter { x => x }.foreach { _ => contexts = KeepRecommendationUserActionContext(userId, uriId, masterScore, source, subSource, RecommendationUserAction.Clicked, None, keepers) :: contexts }
      if (!modelOpt.get.kept) {
        feedback.kept.filter { x => x }.foreach { _ => contexts = KeepRecommendationUserActionContext(userId, uriId, masterScore, source, subSource, RecommendationUserAction.Kept, None, keepers) :: contexts }
      }

      feedback.vote.foreach { isThumbUp =>
        val action = if (isThumbUp) RecommendationUserAction.MarkedGood else RecommendationUserAction.MarkedBad
        contexts = KeepRecommendationUserActionContext(userId, uriId, masterScore, source, subSource, action) :: contexts
      }

      feedback.trashed.filter { x => x }.foreach { _ => contexts = KeepRecommendationUserActionContext(userId, uriId, masterScore, source, subSource, RecommendationUserAction.Trashed) :: contexts }
      feedback.comment.foreach { text => contexts = KeepRecommendationUserActionContext(userId, uriId, masterScore, source, subSource, RecommendationUserAction.ImprovementSuggested, Some(text)) :: contexts }

      contexts
    } else {
      // purely a keep action (not caused by recommendation)
      Seq()
    }
  }

  private def toRecoUserActionContexts(userId: Id[User], libraryId: Id[Library], feedback: LibraryRecommendationFeedback): Seq[LibraryRecommendationUserActionContext] = {
    val modelOpt = db.readOnlyReplica { implicit s => libraryRecoRepo.getByLibraryAndUserId(libraryId, userId, None) }

    if (modelOpt.exists(_.delivered > 0)) {
      val libReco = modelOpt.get
      val masterScore = libReco.masterScore.toInt
      val source = feedback.source.getOrElse(RecommendationSource.Unknown)
      val subSource = feedback.subSource.getOrElse(RecommendationSubSource.Unknown)

      var contexts = List.empty[LibraryRecommendationUserActionContext]

      feedback.clicked.foreach { _ => contexts = LibraryRecommendationUserActionContext(userId, libraryId, masterScore, source, subSource, RecommendationUserAction.Clicked, None) :: contexts }
      if (!libReco.followed) {
        feedback.followed.foreach { _ => contexts = LibraryRecommendationUserActionContext(userId, libraryId, masterScore, source, subSource, RecommendationUserAction.Followed, None) :: contexts }
      }

      feedback.vote.foreach { isThumbUp =>
        val action = if (isThumbUp) RecommendationUserAction.MarkedGood else RecommendationUserAction.MarkedBad
        contexts = LibraryRecommendationUserActionContext(userId, libraryId, masterScore, source, subSource, action) :: contexts
      }

      feedback.trashed.filter { x => x }.foreach { _ => contexts = LibraryRecommendationUserActionContext(userId, libraryId, masterScore, source, subSource, RecommendationUserAction.Trashed) :: contexts }
      feedback.comment.foreach { text => contexts = LibraryRecommendationUserActionContext(userId, libraryId, masterScore, source, subSource, RecommendationUserAction.ImprovementSuggested, Some(text)) :: contexts }

      contexts
    } else {
      // purely a keep action (not caused by recommendation)
      Seq()
    }
  }

  private def toHeimdalEvent(context: RecommendationUserActionContext): Future[UserEvent] = {
    userExperimentCommander.getExperimentsByUser(context.userId).map { experiments =>
      val contextBuilder = heimdalContextBuilder()
      contextBuilder ++= context.contextData.toMap
      contextBuilder += ("experiments", experiments.map(_.value).toSeq)
      contextBuilder += ("userStatus", ExperimentType.getUserStatus(experiments))
      UserEvent(context.userId, contextBuilder.build, UserEventTypes.RECOMMENDATION_USER_ACTION)
    }
  }
}

object RecommendationUserActionContext {
  implicit def toRecoUserActionContext(item: UriRecommendation)(source: RecommendationSource, subSource: RecommendationSubSource): RecommendationUserActionContext = {
    KeepRecommendationUserActionContext(item.userId, item.uriId, item.masterScore.toInt, source, subSource, RecommendationUserAction.Delivered, None)
  }

  implicit def toRecoUserActionContext(item: LibraryRecommendation)(source: RecommendationSource, subSource: RecommendationSubSource): RecommendationUserActionContext = {
    LibraryRecommendationUserActionContext(item.userId, item.libraryId, item.masterScore.toInt, source, subSource, RecommendationUserAction.Delivered, None)
  }
}

sealed trait RecommendationType {
  def value: String
}

object RecommendationType {
  object Library extends RecommendationType { val value = "library" }
  object Keep extends RecommendationType { val value = "keep" }
}

trait RecommendationUserActionContext {
  def userId: Id[User]

  def truncatedMasterScore: Int

  def source: RecommendationSource

  def subSource: RecommendationSubSource

  def userAction: RecommendationUserAction

  def suggestion: Option[String]

  def recommendationType: RecommendationType

  require((userAction == RecommendationUserAction.ImprovementSuggested) == suggestion.isDefined,
    s"invalid arguments: userAction = $userAction, suggestion = $suggestion}")

  def contextData: Seq[(String, ContextData)] = {
    val baseData = Seq[(String, SimpleContextData)](
      ("userId", userId.id),
      ("master_score", truncatedMasterScore),
      ("source", source.value),
      ("subsource", subSource.value),
      ("action", userAction.value),
      ("recommendationType", recommendationType.value)
    )
    suggestion.map { suggest => baseData :+ ("user_suggestion", suggest: SimpleContextData) } getOrElse baseData
  }
}

case class KeepRecommendationUserActionContext(userId: Id[User], uriId: Id[NormalizedURI], truncatedMasterScore: Int, source: RecommendationSource,
    subSource: RecommendationSubSource, userAction: RecommendationUserAction, suggestion: Option[String] = None, keepers: Option[Seq[Id[User]]] = None) extends RecommendationUserActionContext {
  val recommendationType = RecommendationType.Keep

  override def contextData = {
    val uriContextData: (String, SimpleContextData) = ("uriId", uriId.id)
    val keepersContextData: (String, ContextList) = ("keepers", keepers.getOrElse(Seq.empty).map(_.id))
    super.contextData :+ uriContextData :+ keepersContextData
  }
}

case class LibraryRecommendationUserActionContext(userId: Id[User], libraryId: Id[Library], truncatedMasterScore: Int, source: RecommendationSource,
    subSource: RecommendationSubSource, userAction: RecommendationUserAction, suggestion: Option[String] = None) extends RecommendationUserActionContext {
  val recommendationType = RecommendationType.Library

  override def contextData = {
    val libraryContextData: (String, SimpleContextData) = ("libraryId", libraryId.id)
    super.contextData :+ libraryContextData
  }
}
