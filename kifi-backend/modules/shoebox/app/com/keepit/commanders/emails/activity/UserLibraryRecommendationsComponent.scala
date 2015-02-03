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

  def apply(toUserId: Id[User], previouslySent: Seq[ActivityEmail], limit: Int): Future[Seq[LibraryInfoView]] = {
    // does not track deliveries since some of the results may not be included in the email
    recoCommander.topPublicLibraryRecos(toUserId, limit, recoSource, recoSubSource, trackDelivery = false) map { recos =>
      recos.map { case (id, info) => BaseLibraryInfoView(id, info.itemInfo) }
    }
  }

}
