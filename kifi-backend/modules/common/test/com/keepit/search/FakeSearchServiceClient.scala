package com.keepit.search

import com.keepit.common.healthcheck.BenchmarkResults
import com.keepit.common.db.Id
import com.keepit.model.{ BasicLibrary, Collection, NormalizedURI, User }
import com.keepit.search.augmentation._
import play.twirl.api.Html
import scala.concurrent.Future
import play.api.libs.json.JsArray
import com.keepit.search.user.UserSearchResult
import com.keepit.typeahead.TypeaheadHit
import com.keepit.social.{ TypeaheadUserHit, BasicUser }
import com.keepit.common.healthcheck.BenchmarkResults
import scala.Some

class FakeSearchServiceClient() extends SearchServiceClientImpl(null, null, null) {

  override def updateKeepIndex(): Unit = {}

  override def updateLibraryIndex(): Unit = {}

  override def index(): Unit = {}

  override def reindex(): Unit = {}

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

  override def articleIndexerSequenceNumber(): Future[Int] = ???

  override def refreshSearcher(): Unit = {}

  override def refreshPhrases(): Unit = {}

  override def searchUsers(userId: Option[Id[User]], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[UserSearchResult] = ???

  override def userTypeahead(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[BasicUser]]] = Future.successful(Seq.empty)

  override def userTypeaheadWithUserId(userId: Id[User], query: String, maxHits: Int = 10, context: String = "", filter: String = ""): Future[Seq[TypeaheadHit[TypeaheadUserHit]]] = Future.successful(Seq.empty)

  override def explainResult(query: String, userId: Id[User], uriId: Id[NormalizedURI], lang: String): Future[Html] = ???

  override def dumpLuceneDocument(id: Id[NormalizedURI]): Future[Html] = ???

  override def benchmarks(): Future[BenchmarkResults] = ???

  override def version(): Future[String] = ???

  override def showUserConfig(id: Id[User]): Future[SearchConfig] = ???

  override def setUserConfig(id: Id[User], params: Map[String, String]): Unit = {}

  override def resetUserConfig(id: Id[User]): Unit = {}

  override def getSearchDefaultConfig: Future[SearchConfig] = ???

  override def warmUpUser(userId: Id[User]): Unit = {}

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
    val augmentationInfos = keeperInfos.map { case (keepers, keepersTotal) => LimitedAugmentationInfo(keepers, 0, keepersTotal, Seq.empty, 0, 0, Seq.empty, 0) }
    augmentationInfoData = Some(augmentationInfos)
  }
  override def augment(userId: Option[Id[User]], maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, items: Seq[AugmentableItem]): Future[Seq[LimitedAugmentationInfo]] = {
    Future.successful(augmentationInfoData getOrElse Seq.fill(items.length)(LimitedAugmentationInfo.empty))
  }
}
