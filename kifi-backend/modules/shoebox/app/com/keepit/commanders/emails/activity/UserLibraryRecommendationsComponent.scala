package com.keepit.commanders.emails.activity

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RecommendationsCommander
import com.keepit.commanders.emails.{ BaseLibraryInfoView, LibraryInfoView }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.curator.model.{ RecommendationSource, RecommendationSubSource }
import com.keepit.model.{ ActivityEmail, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class UserLibraryRecommendationsComponent @Inject() (recoCommander: RecommendationsCommander, private val airbrake: AirbrakeNotifier) {

  val recoSource = RecommendationSource.Email
  val recoSubSource = RecommendationSubSource.Unknown

  // max library recommendations to include in the feed
  val maxLibRecostoDeliver = 3

  // library recommendations to fetch from curator
  val libRecosToFetch = maxLibRecostoDeliver * 2

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail]): Future[Seq[LibraryInfoView]] = {
    recoCommander.topPublicLibraryRecos(toUserId, libRecosToFetch, recoSource, recoSubSource) map { recos =>
      recos.take(maxLibRecostoDeliver).map { case (id, info) => BaseLibraryInfoView(id, info.itemInfo) }
    }
  } recover {
    case e: Exception =>
      airbrake.notify(s"ActivityFeedEmail Failed to load library recommendations for userId=$toUserId", e)
      Seq.empty

  }

}
