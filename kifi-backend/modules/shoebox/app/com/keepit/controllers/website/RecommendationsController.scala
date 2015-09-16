package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper, UserRequest }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model._
import com.keepit.model._
import org.joda.time.Days
import com.keepit.model.{ LibraryRecommendationFeedback, Library, UserExperimentType, NormalizedURI, UriRecommendationFeedback, UriRecommendationScores }
import com.keepit.common.net.UserAgent
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, Json }
import com.keepit.common.time._
import scala.concurrent.Future

class RecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    val db: Database,
    val userRepo: UserRepo,
    val libMemRepo: LibraryMembershipRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with RecommendationControllerHelper {

  def topPublicRecos() = UserAction.async { request =>
    commander.topPublicRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def updateUriRecommendationFeedback(id: ExternalId[NormalizedURI]) = UserAction.async(parse.tolerantJson) { request =>
    val feedback = request.body.as[UriRecommendationFeedback].copy(
      source = Some(getRecommendationSource(request)),
      subSource = Some(RecommendationSubSource.RecommendationsFeed)
    )
    commander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    commander.updateUriRecommendationFeedback(request.userId, id, UriRecommendationFeedback(trashed = Some(true))).map(fkis => Ok(Json.toJson(fkis)))
  }

  private def getRecommendationSource(request: UserRequest[_]): RecommendationSource = {
    val agent = UserAgent(request)
    if (agent.isKifiAndroidApp) RecommendationSource.Android
    else if (agent.isKifiIphoneApp) RecommendationSource.IOS
    else RecommendationSource.Site
  }

}

trait RecommendationControllerHelper extends Logging {
  val db: Database
  val userRepo: UserRepo
  val libMemRepo: LibraryMembershipRepo
  // hack, make sure Danny is happy.
  private val specialBoostSet = Set(46862, 36680, 49078, 24103, 47498, 51798, 47889, 47191, 47494, 48661, 49090, 50874, 49135, 26460, 27238, 25168, 50812, 47488, 42651, 27760, 25368, 44475, 24203, 50862, 47284, 25000, 27545, 51496, 27049, 26465).map { Id[Library](_) }

  def mix(uris: Seq[FullUriRecoInfo], libs: Seq[(Id[Library], FullLibRecoInfo)]): Seq[FullRecoInfo] = {
    val (lucky, rest) = libs.partition { case (id, _) => specialBoostSet.contains(id) }
    util.Random.shuffle(lucky.map { _._2 }) ++ util.Random.shuffle(uris ++ rest.map(_._2))
  }

  def libraryRecoCount(userId: Id[User]): Int = {
    val alpha = {
      val user = db.readOnlyMaster { implicit s => userRepo.get(userId) }
      val days = Days.daysBetween(user.createdAt, currentDateTime).getDays max 0
      val ratio = days * 1f / 14
      ratio min 1f
    }
    val timeBased = ((1 - alpha) * 10 + alpha * 2).toInt

    val beta = {
      val cnts = db.readOnlyMaster { implicit s => libMemRepo.countsWithUserIdAndAccesses(userId, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)) }
      val cntsSum = cnts.values.foldLeft(0)(_ + _)
      val ratio = cntsSum * 1f / 50
      ratio min 1f
    }
    val libBased = ((1 - beta) * 10 + beta * 2).toInt

    log.info(s"smart lib reco number: timeBased = $timeBased, libBased = $libBased")

    (libBased min timeBased) max 2
  }
}
