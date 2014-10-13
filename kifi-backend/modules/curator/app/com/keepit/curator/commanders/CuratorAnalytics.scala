package com.keepit.curator.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.RecommendationUserAction
import com.keepit.curator.model.{ RecommendationClientType, UriRecommendationRepo, UriRecommendation }
import com.keepit.heimdal.{ UserEventTypes, HeimdalContextBuilderFactory, UserEvent, HeimdalServiceClient }
import com.keepit.model.{ NormalizedURI, User, UriRecommendationFeedback }
import com.keepit.common.logging.Logging
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.model.ExperimentType
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class CuratorAnalytics @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander) extends Logging {

  def trackDeliveredItems(items: Seq[UriRecommendation], client: Option[RecommendationClientType]): Unit = {
    val clientType = client.getOrElse(RecommendationClientType.Unknown)
    items.foreach { item =>
      val context = toRecoUserActionContext(item, clientType)
      new SafeFuture(toHeimdalEvent(context).map { event =>
        heimdal.trackEvent(event)
      })
    }
  }

  def trackUserFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Unit = {
    log.info(s"[analytics] Received user $userId reco feedback on $uriId to track: $feedback")
    val contexts = toRecoUserActionContexts(userId, uriId, feedback)
    contexts.foreach { context =>
      new SafeFuture(toHeimdalEvent(context).map { event =>
        log.info(s"[analytics] Sending event: $event")
        heimdal.trackEvent(event)
      })
    }
    if (contexts.isEmpty) log.info(s"[analytics] nothing to do for user $userId reco feedback on $uriId to track: $feedback")
  }

  private def toRecoUserActionContext(item: UriRecommendation, clientType: RecommendationClientType): RecommendationUserActionContext = {
    RecommendationUserActionContext(item.userId, item.uriId, item.masterScore.toInt, clientType, RecommendationUserAction.Delivered, None)
  }

  private def toRecoUserActionContexts(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Seq[RecommendationUserActionContext] = {
    val modelOpt = db.readOnlyReplica { implicit s => uriRecoRepo.getByUriAndUserId(uriId, userId, None) }

    if (modelOpt.exists(r => r.delivered > 0)) {
      val masterScore = modelOpt.get.masterScore.toInt
      val keepers = modelOpt.get.attribution.user.map { _.friends }
      val client = feedback.clientType.getOrElse(RecommendationClientType.Unknown)

      var contexts = List.empty[RecommendationUserActionContext]

      feedback.clicked.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Clicked, None, keepers) :: contexts }
      if (!modelOpt.get.kept) {
        feedback.kept.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Kept, None, keepers) :: contexts }
      }

      feedback.vote.foreach { isThumbUp =>
        val action = if (isThumbUp) RecommendationUserAction.MarkedGood else RecommendationUserAction.MarkedBad
        contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, action) :: contexts
      }

      feedback.trashed.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Trashed) :: contexts }
      feedback.comment.foreach { text => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.ImprovementSuggested, Some(text)) :: contexts }

      contexts
    } else {
      // purely a keep action (not caused by recommendation)
      Seq()
    }
  }

  private def toHeimdalEvent(context: RecommendationUserActionContext): Future[UserEvent] = {
    userExperimentCommander.getExperimentsByUser(context.userId).map { experiments =>
      val contextBuilder = heimdalContextBuilder()
      contextBuilder += ("userId", context.userId.id)
      contextBuilder += ("uriId", context.uriId.id)
      contextBuilder += ("master_score", context.truncatedMasterScore)
      contextBuilder += ("client_type", context.clientType.value)
      contextBuilder += ("action", context.userAction.value)

      contextBuilder += ("experiments", experiments.map(_.value).toSeq)
      contextBuilder += ("userStatus", ExperimentType.getUserStatus(experiments))

      context.suggestion.foreach { suggest => contextBuilder += ("user_suggestion", suggest) }
      context.keepers.foreach { userIds => contextBuilder += ("keepers", userIds.map { _.id }) }
      UserEvent(context.userId, contextBuilder.build, UserEventTypes.RECOMMENDATION_USER_ACTION)
    }
  }

}

case class RecommendationUserActionContext(userId: Id[User], uriId: Id[NormalizedURI], truncatedMasterScore: Int, clientType: RecommendationClientType, userAction: RecommendationUserAction, suggestion: Option[String] = None, keepers: Option[Seq[Id[User]]] = None) {
  require((userAction == RecommendationUserAction.ImprovementSuggested) == suggestion.isDefined, s"invalid arguments: userAction = $userAction, suggestion = ${suggestion}")
}
