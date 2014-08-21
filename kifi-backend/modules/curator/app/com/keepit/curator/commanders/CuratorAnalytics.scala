package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ UriRecommendationRepo, UriRecommendation }
import com.keepit.heimdal.{ UserEventTypes, HeimdalContextBuilderFactory, UserEvent, HeimdalServiceClient }
import com.keepit.model.{ NormalizedURI, User, UriRecommendationFeedback }

class CuratorAnalytics @Inject() (
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient) {

  def trackDeliveredItems(items: Seq[UriRecommendation]): Unit = {
    items.foreach { item =>
      val context = toRecoUserActionContext(item)
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

  private def toRecoUserActionContext(item: UriRecommendation): RecommendationUserActionContext = {
    RecommendationUserActionContext(item.userId, item.uriId, item.masterScore.toInt, RecommendationUserAction.Delivered, None)
  }

  private def toRecoUserActionContexts(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Seq[RecommendationUserActionContext] = {
    val masterScore = db.readOnlyReplica { implicit s =>
      val model = uriRecoRepo.getByUriAndUserId(uriId, userId, None)
      model.get.masterScore.toInt
    }

    var contexts = List.empty[RecommendationUserActionContext]
    feedback.clicked.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, RecommendationUserAction.Clicked) :: contexts }
    feedback.kept.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, RecommendationUserAction.Kept) :: contexts }
    feedback.trashed.filter { x => x }.foreach { _ => contexts = RecommendationUserActionContext(userId, uriId, masterScore, RecommendationUserAction.Trashed) :: contexts }

    contexts
  }

  private def toHeimdalEvent(context: RecommendationUserActionContext): UserEvent = {
    val contextBuilder = heimdalContextBuilder()
    contextBuilder += ("userId", context.userId.id)
    contextBuilder += ("uriId", context.uriId.id)
    contextBuilder += ("master_score", context.truncatedMasterScore)
    contextBuilder += ("user_action", context.userAction.value)
    context.suggestion.foreach { suggest => contextBuilder += ("user_suggestion", suggest.value) }
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

case class RecommendationImprovmentSuggestion(value: String)

case class RecommendationUserActionContext(userId: Id[User], uriId: Id[NormalizedURI], truncatedMasterScore: Int, userAction: RecommendationUserAction, suggestion: Option[RecommendationImprovmentSuggestion] = None) {
  require((userAction == RecommendationUserAction.ImprovementSuggested) == suggestion.isDefined, s"invalid arguments: userAction = $userAction, suggestion = ${suggestion}")
}
