package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{ RecommendationClientType, FullRecoInfo }
import com.keepit.model.{ User, KeepRepo, UserRepo, LibraryRepo, NormalizedURIRepo }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }

import scala.concurrent.Future

@Singleton
class FakeRecommendationsCommander @Inject() (
  curator: CuratorServiceClient,
  db: Database,
  nUriRepo: NormalizedURIRepo,
  libRepo: LibraryRepo,
  userRepo: UserRepo,
  libCommander: LibraryCommander,
  uriSummaryCommander: URISummaryCommander,
  basicUserRepo: BasicUserRepo,
  keepRepo: KeepRepo,
  publicIdConfig: PublicIdConfiguration,
  userExperimentCommander: LocalUserExperimentCommander)
    extends RecommendationsCommander(
      curator,
      db,
      nUriRepo,
      libRepo,
      userRepo,
      libCommander,
      uriSummaryCommander,
      basicUserRepo,
      keepRepo,
      publicIdConfig,
      userExperimentCommander) {

  var recoInfos: Seq[FullRecoInfo] = Seq.empty

  override def topRecos(userId: Id[User], clientType: RecommendationClientType, more: Boolean, recencyWeight: Float): Future[Seq[FullRecoInfo]] =
    Future.successful(recoInfos)

  override def topPublicRecos(userId: Id[User]) = Future.successful(Seq.empty)
}
