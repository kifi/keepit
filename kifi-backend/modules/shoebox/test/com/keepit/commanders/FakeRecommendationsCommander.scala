package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{ FullUriRecoInfo, RecommendationSubSource, FullLibRecoInfo, RecommendationSource, FullRecoInfo }
import com.keepit.model.{ Library, User, KeepRepo, UserRepo, LibraryRepo, NormalizedURIRepo }
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

  var uriRecoInfos: Seq[FullUriRecoInfo] = Seq.empty
  var libRecoInfos: Seq[(Id[Library], FullLibRecoInfo)] = Seq.empty

  override def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float): Future[Seq[FullUriRecoInfo]] =
    Future.successful(uriRecoInfos)

  override def topPublicRecos(userId: Id[User]) = Future.successful(Seq.empty)

  override def topPublicLibraryRecos(userId: Id[User], limit: Int, source: RecommendationSource, subSource: RecommendationSubSource) = {
    Future.successful(libRecoInfos take limit)
  }
}
