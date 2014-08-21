package com.keepit.curator.commanders

import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ RecommendationClientType, UriRecommendationRepo, UriRecommendation }
import com.keepit.heimdal.{ UserEventTypes, HeimdalContextBuilderFactory, UserEvent, HeimdalServiceClient }
import com.keepit.model.{ NormalizedURI, User, UriRecommendationFeedback }

@Singleton
class CuratorAnalytics @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient) {

  def trackDeliveredItems(items: Seq[UriRecommendation], client: Option[RecommendationClientType]): Unit = {
    val clientType = client.getOrElse(RecommendationClientType.Unknown)
    items.foreach { item =>
      val context = toRecoUserActionContext(item, clientType)
      val event = toHeimdalEvent(context)
      heimdal.trackEvent(event)
    }
  }

  def trackUserFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Unit = {
    toRecoUserActionContexts(userId, uriId, feedback).foreach { context =>
      val event = toHeimdalEvent(context)
      heimdal.trackEvent(event)
    }
  }

  private def toRecoUserActionContext(item: UriRecommendation, clientType: RecommendationClientType): RecommendationUserActionContext = {
    RecommendationUserActionContext(item.userId, item.uriId, item.masterScore.toInt, clientType, RecommendationUserAction.Delivered, None)
  }

  private def toRecoUserActionContexts(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Seq[RecommendationUserActionContext] = {
    val masterScore = db.readOnlyReplica { implicit s =>
      val model = uriRecoRepo.getByUriAndUserId(uriId, userId, None)
      model.get.masterScore.toInt
    }

    val client = feedback.fromClient.getOrElse(RecommendationClientType.Unknown)

    var contexts = List.empty[RecommendationUserActionContext]

    feedback.clicked.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Clicked) :: contexts }
    feedback.kept.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Kept) :: contexts }

    feedback.vote.foreach { isThumbUp =>
      val action = if (isThumbUp) RecommendationUserAction.MarkedGood else RecommendationUserAction.MarkedBad
      contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, action) :: contexts
    }

    feedback.trashed.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.Trashed) :: contexts }
    feedback.improvement.foreach { text => contexts = RecommendationUserActionContext(userId, uriId, masterScore, client, RecommendationUserAction.ImprovementSuggested, Some(text)) :: contexts }

    contexts
  }

  private def toHeimdalEvent(context: RecommendationUserActionContext): UserEvent = {
    val contextBuilder = heimdalContextBuilder()
    contextBuilder += ("userId", context.userId.id)
    contextBuilder += ("uriId", context.uriId.id)
    contextBuilder += ("master_score", context.truncatedMasterScore)
    contextBuilder += ("client_type", context.clientType.value)
    contextBuilder += ("action", context.userAction.value)
    context.suggestion.foreach { suggest => contextBuilder += ("user_suggestion", suggest) }
    UserEvent(context.userId, contextBuilder.build, UserEventTypes.RECOMMENDATION_USER_ACTION)
  }

}

case class RecommendationUserAction(value: String)

object RecommendationUserAction {
  object Delivered extends RecommendationUserAction("delivered")
  object Clicked extends RecommendationUserAction("clicked")
  object Kept extends RecommendationUserAction("kept")
  object MarkedGood extends RecommendationUserAction("marked_good")
  object MarkedBad extends RecommendationUserAction("marked_bad")
  object Trashed extends RecommendationUserAction("trashed")
  object ImprovementSuggested extends RecommendationUserAction("improvement_suggested")
}

case class RecommendationUserActionContext(userId: Id[User], uriId: Id[NormalizedURI], truncatedMasterScore: Int, clientType: RecommendationClientType, userAction: RecommendationUserAction, suggestion: Option[String] = None) {
  require((userAction == RecommendationUserAction.ImprovementSuggested) == suggestion.isDefined, s"invalid arguments: userAction = $userAction, suggestion = ${suggestion}")
}
