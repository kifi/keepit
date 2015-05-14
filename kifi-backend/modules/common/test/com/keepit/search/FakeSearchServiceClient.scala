package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search.augmentation._
import play.twirl.api.Html
import scala.concurrent.Future
import com.keepit.search.user.DeprecatedUserSearchResult
import com.keepit.typeahead.TypeaheadHit
import com.keepit.social.{ TypeaheadUserHit, BasicUser }
import com.keepit.common.healthcheck.BenchmarkResults
import com.keepit.common.time._

class FakeSearchServiceClient() extends SearchServiceClientImpl(null, null, null) {

  override def warmUpUser(userId: Id[User]): Unit = {}

  override def updateKeepIndex(): Unit = {}
  override def updateLibraryIndex(): Unit = {}

  override def updateUserGraph(): Unit = {}
  override def updateSearchFriendGraph(): Unit = {}
  override def reindexUserGraphs(): Unit = {}

  override def index(): Unit = {}
  override def reindex(): Unit = {}
  override def articleIndexerSequenceNumber(): Future[Int] = ???

  override def reindexUsers(): Unit = {}
  override def updateUserIndex(): Unit = {}

  override def sharingUserInfo(userId: Id[User], uriId: Id[NormalizedURI]): Future[SharingUserInfo] =
    Future.successful(SharingUserInfo(sharingUserIds = Set(Id[User](1)), keepersEdgeSetSize = 1))

  val sharingUserInfoDataOriginal = SharingUserInfo(Set(Id[User](1)), 1)
  var sharingUserInfoDataFix: Seq[SharingUserInfo] = Seq(sharingUserInfoDataOriginal)
  def sharingUserInfoData(data: Seq[SharingUserInfo]): Unit = sharingUserInfoDataFix = data
  val itemAugmentations = collection.mutable.Map[ItemAugmentationRequest, ItemAugmentationResponse]()

  override def sharingUserInfo(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[SharingUserInfo]] = {
    if (sharingUserInfoDataFix.headOption == Some(sharingUserInfoDataOriginal)) {
      Future.successful(uriIds.map(_ => sharingUserInfoDataOriginal))
    } else {
      Future.successful(sharingUserInfoDataFix)
    }
  }

  override def refreshSearcher(): Unit = {}

  override def refreshPhrases(): Unit = {}

  override def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[DeprecatedUserSearchResult] = ???

  override def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]] = Future.successful(Seq.empty)

  override def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]] = Future.successful(Seq.empty)

  override def explainUriResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String, debug: Option[String]): Future[Html] = ???

  override def explainLibraryResult(query: String, userId: Id[User], libraryId: Id[Library], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean): Future[Html] = ???

  override def explainUserResult(query: String, userId: Id[User], resultUserId: Id[User], acceptLangs: Seq[String], debug: Option[String], disablePrefixSearch: Boolean): Future[Html] = ???

  override def dumpLuceneDocument(id: Id[NormalizedURI], deprecated: Boolean): Future[Html] = ???

  override def getLibraryDocument(library: DetailedLibraryView): Future[Html] = ???

  override def benchmarks(): Future[BenchmarkResults] = ???

  override def version(): Future[String] = ???

  override def showUserConfig(id: Id[User]): Future[SearchConfig] = ???

  override def setUserConfig(id: Id[User], params: Map[String, String]): Unit = {}

  override def resetUserConfig(id: Id[User]): Unit = {}

  override def getSearchDefaultConfig: Future[SearchConfig] = ???

  override def augmentation(request: ItemAugmentationRequest): Future[ItemAugmentationResponse] = Future.successful {
    itemAugmentations.getOrElse(request,
      ItemAugmentationResponse(
        infos = Map.empty,
        scores = AugmentationScores(
          libraryScores = Map.empty,
          userScores = Map.empty,
          tagScores = Map.empty
        )
      )
    )
  }

  var augmentationInfoData: Option[Seq[LimitedAugmentationInfo]] = None

  def setKeepers(keeperInfos: (Seq[Id[User]], Int)*) = {
    val augmentationInfos = keeperInfos.map { case (keepers, keepersTotal) => LimitedAugmentationInfo(None, keepers.map((_, Some(currentDateTime))), 0, keepersTotal, Seq.empty, 0, 0, Seq.empty, 0) }
    augmentationInfoData = Some(augmentationInfos)
  }
  override def augment(userId: Option[Id[User]], showPublishedLibraries: Boolean, maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, items: Seq[AugmentableItem]): Future[Seq[LimitedAugmentationInfo]] = {
    Future.successful(augmentationInfoData getOrElse Seq.fill(items.length)(LimitedAugmentationInfo.empty))
  }
}
