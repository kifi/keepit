package com.keepit.commanders

import com.keepit.search.SearchServiceClient

import scala.concurrent.ExecutionContext
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model._
import com.keepit.model.{ KeepRepo, Library, LibraryRepo, NormalizedURIRepo, User, UserRepo }

import scala.concurrent.Future

@Singleton
class FakeRecommendationsCommander @Inject() (
  curator: CuratorServiceClient,
  search: SearchServiceClient,
  db: Database,
  nUriRepo: NormalizedURIRepo,
  libRepo: LibraryRepo,
  userRepo: UserRepo,
  libCommander: LibraryCommander,
  uriSummaryCommander: URISummaryCommander,
  basicUserRepo: BasicUserRepo,
  keepRepo: KeepRepo,
  publicIdConfig: PublicIdConfiguration,
  defaultContext: ExecutionContext,
  keepDecorator: KeepDecorator,
  userExperimentCommander: LocalUserExperimentCommander)
    extends RecommendationsCommander(
      curator,
      search,
      db,
      nUriRepo,
      libRepo,
      userRepo,
      libCommander,
      uriSummaryCommander,
      basicUserRepo,
      keepRepo,
      keepDecorator,
      defaultContext,
      publicIdConfig,
      userExperimentCommander) {

  var uriRecoInfos: Seq[FullUriRecoInfo] = Seq.empty
  var libRecoInfos: Seq[(Id[Library], FullLibRecoInfo)] = Seq.empty

  override def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[FullUriRecoResults] =
    Future.successful(FullUriRecoResults(uriRecoInfos, "fake_uri_context"))

  override def topPublicRecos(userId: Id[User]) = Future.successful(Seq.empty)

  override def topPublicLibraryRecos(userId: Id[User], limit: Int, source: RecommendationSource, subSource: RecommendationSubSource, trackDelivery: Boolean = true, context: Option[String]) = {
    val libs = libRecoInfos take limit
    Future.successful(FullLibRecoResults(libs, "fake_lib_context"))
  }
}
