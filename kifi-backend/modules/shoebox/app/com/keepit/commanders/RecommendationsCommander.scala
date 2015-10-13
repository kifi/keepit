package com.keepit.commanders

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.store.S3ImageConfig
import com.keepit.model._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.curator.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.time._

import com.google.inject.Inject
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem }
import com.keepit.search.util.LongSetIdFilter
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random
import play.api.libs.json.Json

class RecommendationsCommander @Inject() (
    systemValueRepo: SystemValueRepo,
    search: SearchServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    libRepo: LibraryRepo,
    userRepo: UserRepo,
    libraryInfoCommander: LibraryInfoCommander,
    libPathCommander: PathCommander,
    rover: RoverServiceClient,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    keepDecorator: KeepDecorator,
    userValueRepo: UserValueRepo,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val imageConfig: S3ImageConfig,
    userExperimentCommander: LocalUserExperimentCommander) extends Logging {

  def updateUriRecommendationFeedback(userId: Id[User], extId: ExternalId[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    log.info(s"updateUriRecommendationFeedback is NO-OP")
    Future.successful(true)
  }

  def topPublicRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val magicRecosUriIds = Seq(9282L, 1113120L, 1174911L, 1322581L, 1429738L, 1455719L, 1597454L, 1698263L, 1912272L, 1946530L, 1959849L, 2060916L, 2143952L, 2147214L, 2163010L, 2320712L, 2439562L, 2665654L, 2669346L, 2765186L, 2767408L, 2804797L, 2819130L, 2838370L, 2908230L, 2917336L, 2950411L, 3035002L, 3035085L, 3035111L, 3036832L, 3036840L, 3036841L, 3036844L, 3036848L, 3036849L, 3036853L, 3036854L, 3036855L, 3036857L, 3036859L, 3036860L, 3036861L, 3036862L, 3036863L, 3036868L, 3036869L, 3036872L, 3036873L, 3036877L, 3036878L, 3036879L, 3036880L, 3036882L, 3036884L, 3036885L, 3036887L, 3036889L, 3036892L, 3036893L, 3036895L, 3036896L, 3036897L, 3036902L, 3036904L, 3036906L, 3036907L, 3036911L, 3036912L, 3036913L, 3036914L, 3036916L, 3036920L, 3036921L, 3036923L, 3036925L, 3036930L, 3036931L, 3036932L, 3036933L, 3036938L, 3036940L, 3036942L, 3036946L, 3036947L, 3036948L, 3036949L, 3036950L, 3036955L, 3036957L, 3036958L, 3036960L, 3036965L, 3036967L, 3036970L, 3036973L, 3036975L, 3036976L, 3036977L, 3036978L, 3036981L, 3036982L, 3036984L, 3036985L, 3036987L, 3036991L, 3036992L, 3036995L, 3036997L, 3036998L, 3037001L, 3037002L, 3037003L, 3037007L, 3037010L, 463170L, 571466L, 691458L, 924722L, 1103920L, 1153307L, 1211098L, 1269096L, 1422342L, 1783994L, 1938067L, 1976452L, 2154007L, 2261470L, 2277662L, 2808995L, 2912069L, 2912327L, 2929756L, 2969450L, 3034005L, 3035065L, 3035267L, 3036594L).map(Id[NormalizedURI])
    val recos = Random.shuffle(magicRecosUriIds).take(10).map { uriId =>
      RecoInfo(
        userId = None,
        uriId = uriId,
        score = 42.0f,
        explain = None,
        attribution = None)
    }
    val uriRecosFut = decorateUriRecos(userId, recos, explain = false)

    for (uriRecos <- uriRecosFut; libRecos <- curatedPublicLibraryRecos(userId)) yield libRecos.map(_._2) ++ uriRecos

  }

  def curatedPublicLibraries(): Seq[Library] = {
    val curatedLibIds = db.readOnlyReplica { implicit s =>
      val json = systemValueRepo.getValue(MarketingSuggestedLibrarySystemValue.systemValueName).get
      Json.fromJson[Seq[MarketingSuggestedLibrarySystemValue]](Json.parse(json)).get.map(lib => lib.id)
    }

    val curatedLibraries = {
      val libraryById = db.readOnlyReplica { implicit session => libRepo.getLibraries(curatedLibIds.toSet) }
      curatedLibIds.map(libraryById)
    }.filter { lib =>
      lib.visibility == LibraryVisibility.PUBLISHED
    }
    curatedLibraries
  }

  def curatedPublicLibraryRecos(userId: Id[User]): Future[Seq[(Id[Library], FullLibRecoInfo)]] = {
    createFullLibraryInfos(userId, curatedPublicLibraries())
  }

  def topPublicLibraryRecos(userId: Id[User], limit: Int, source: RecommendationSource, subSource: RecommendationSubSource, trackDelivery: Boolean = true, context: Option[String]): Future[FullLibRecoResults] = {
    val libraries = curatedPublicLibraries()

    createFullLibraryInfos(userId, libraries).map {
      recosInfo => FullLibRecoResults(recosInfo, "")
    }
  }

  def sampleFairly[T](seqOfSeqs: Seq[Seq[T]], maxPerSeq: Int): Seq[T] = {
    seqOfSeqs.flatMap(_.take(maxPerSeq))
  }
  def maybeUpdatesFromFollowedLibraries(userId: Id[User], maxUpdates: Int = 20, maxUpdatesPerLibrary: Int = 5): Future[Option[FullLibUpdatesRecoInfo]] = {
    val keepsOpt: Option[Seq[Keep]] = db.readWrite { implicit session =>
      val lastSeen = userValueRepo.getValue(userId, UserValues.libraryUpdatesLastSeen)
      if (lastSeen.isBefore(currentDateTime.minusHours(12))) {
        userValueRepo.setValue(userId, UserValueName.UPDATED_LIBRARIES_LAST_SEEN, currentDateTime)

        val recentlyUpdatedKeeps = keepRepo.getRecentKeeps(userId, 10 * maxUpdates, None, None).filterNot(_.userId == userId)
        val keepsByLibrary = recentlyUpdatedKeeps.groupBy(_.libraryId).values.toList
        val fairlySampledKeeps = sampleFairly(keepsByLibrary, maxUpdatesPerLibrary)
        val result = fairlySampledKeeps.sortBy(_.keptAt).reverse.take(maxUpdates)

        Some(result)
      } else {
        None
      }
    }

    keepsOpt.map { keeps =>
      keepDecorator.decorateKeepsIntoKeepInfos(Some(userId), false, keeps, ProcessedImageSize.Large.idealSize, true).map { keepInfos =>
        FullLibUpdatesRecoInfo(itemInfo = keepInfos)
      }
    }.map { keepInfoFuture =>
      keepInfoFuture.map { infos =>
        if (infos.itemInfo.isEmpty) None
        else Some(infos)
      }
    }.getOrElse(Future.successful(None))
  }

  private def decorateUriRecos(userId: Id[User], recos: Seq[RecoInfo], explain: Boolean): Future[Seq[FullUriRecoInfo]] = {
    val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
      recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
    }
    val uriIds = recosWithUris.map { _._2.id.get }
    val userAttrsF = getUserAttributions(userId, uriIds)
    val uriSummariesF = rover.getUriSummaryByUris(uriIds.toSet).map { summaries =>
      uriIds.map { uriId => summaries.get(uriId).map(_.toUriSummary(ProcessedImageSize.Large.idealSize)) getOrElse URISummary() }
    }

    for {
      userAttrs <- userAttrsF
      uriSummaries <- uriSummariesF
    } yield {
      Seq.tabulate(recosWithUris.size) { i =>
        val info = constructRecoItemInfo(recosWithUris(i)._2, uriSummaries(i), userAttrs(i))
        val explanation = if (explain) recosWithUris(i)._1.explain else None
        FullUriRecoInfo(RecoKind.Keep, metaData = None, itemInfo = info, explain = explanation)
      }
    }
  }

  private def getUserAttributions(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Option[UserAttribution]]] = {
    val uriId2Idx = uriIds.zipWithIndex.toMap
    val ret: Array[Option[UserAttribution]] = Array.fill(uriIds.size)(None)

    search.augment(Some(userId), false, maxKeepersShown = 20, maxLibrariesShown = 15, maxTagsShown = 0, items = uriIds.map(AugmentableItem(_))).map { infos =>
      (uriIds zip infos).foreach {
        case (uriId, info) =>
          val idx = uriId2Idx(uriId)
          val attr = UserAttribution.fromLimitedAugmentationInfo(info)
          val n = attr.friends.size + attr.friendsLib.map { _.size }.getOrElse(0)
          if (n > 0) ret(idx) = Some(attr)
      }
      ret
    }
  }

  private def constructRecoItemInfo(nUri: NormalizedURI, uriSummary: URISummary, userAttr: Option[UserAttribution]): UriRecoItemInfo = {
    val libraries: Map[Id[User], Id[Library]] = userAttr.flatMap(_.friendsLib).getOrElse(Map.empty)
    val keeperIds: Seq[Id[User]] = userAttr.map(_.friends).getOrElse(Seq.empty)
    val others: Int = userAttr.map(_.others).getOrElse(0)

    val libInfos = libraries.toSeq.map {
      case (ownerId, libraryId) =>
        val (lib, owner) = db.readOnlyReplica { implicit session =>
          libRepo.get(libraryId) -> basicUserRepo.load(ownerId)
        }

        RecoLibraryInfo(
          owner = owner,
          id = Library.publicId(libraryId),
          name = lib.name,
          path = libPathCommander.getPathForLibrary(lib),
          color = lib.color.map { _.hex }
        )
    }

    UriRecoItemInfo(
      id = nUri.externalId,
      title = nUri.title,
      url = nUri.url,
      keepers = db.readOnlyReplica { implicit session => keeperIds.toSet.map(basicUserRepo.load).toSeq },
      libraries = libInfos,
      others = others,
      siteName = DomainToNameMapper.getNameFromUrl(nUri.url),
      summary = uriSummary
    )
  }

  private def noopLibRecoExplainer(lib: Id[Library]): Option[String] = None

  private def createFullLibraryInfos(userId: Id[User], libraries: Seq[Library], explainer: Id[Library] => Option[String] = noopLibRecoExplainer): Future[Seq[(Id[Library], FullLibRecoInfo)]] = {
    libraryInfoCommander.createFullLibraryInfos(Some(userId), showPublishedLibraries = false, maxMembersShown = 10,
      maxKeepsShown = 0, ProcessedImageSize.Large.idealSize, libraries,
      ProcessedImageSize.Large.idealSize, withKeepTime = true).map { fullLibraryInfos =>
        fullLibraryInfos.map {
          case (id, libInfo) => id -> FullLibRecoInfo(metaData = None, itemInfo = libInfo, explain = explainer(id))
        }
      }
  }
}
