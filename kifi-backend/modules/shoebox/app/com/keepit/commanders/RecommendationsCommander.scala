package com.keepit.commanders

import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.domain.DomainToNameMapper

import com.google.inject.Inject
import com.keepit.search.util.LongSetIdFilter

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class RecommendationsCommander @Inject() (
    curator: CuratorServiceClient,
    db: Database,
    nUriRepo: NormalizedURIRepo,
    libRepo: LibraryRepo,
    userRepo: UserRepo,
    libCommander: LibraryCommander,
    uriSummaryCommander: URISummaryCommander,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    userExperimentCommander: LocalUserExperimentCommander) {

  def adHocRecos(userId: Id[User], howManyMax: Int, scoreCoefficientsUpdate: UriRecommendationScores): Future[Seq[KeepInfo]] = {
    curator.adHocRecos(userId, howManyMax, scoreCoefficientsUpdate).flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }.filter(_._2.state == NormalizedURIStates.SCRAPED)

      Future.sequence(recosWithUris.map {
        case (reco, nUri) =>
          uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
            val extraInfo = reco.attribution.map { attr =>
              attr.topic.map(_.topicName).map { topicName =>
                s"[$topicName;${reco.explain.getOrElse("")}]"
              } getOrElse {
                s"[${reco.explain.getOrElse("")}]"
              }
            } getOrElse ""
            val augmentedDescription = uriSummary.description.map { desc =>
              extraInfo + desc
            } getOrElse {
              extraInfo
            }
            KeepInfo(
              title = nUri.title,
              url = nUri.url,
              isPrivate = false,
              summary = Some(uriSummary.copy(description = Some(augmentedDescription))),
              others = reco.attribution.get.user.map(_.others),
              keepers = db.readOnlyReplica { implicit session => reco.attribution.get.user.map(_.friends.map(basicUserRepo.load)) }
            )
          }
      })

    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], extId: ExternalId[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    val uriOpt = db.readOnlyMaster { implicit s =>
      nUriRepo.getOpt(extId)
    }
    uriOpt match {
      case Some(uri) => curator.updateUriRecommendationFeedback(userId, uri.id.get, feedback)
      case None => Future.successful(false)
    }
  }

  def updateLibraryRecommendationFeedback(userId: Id[User], id: Id[Library], feedback: LibraryRecommendationFeedback): Future[Boolean] = {
    curator.updateLibraryRecommendationFeedback(userId, id, feedback)
  }

  private def constructRecoItemInfo(nUri: NormalizedURI, uriSummary: URISummary, reco: RecoInfo): UriRecoItemInfo = {
    val libraries: Map[Id[User], Id[Library]] = reco.attribution.flatMap { attr => attr.user.flatMap(_.friendsLib) }.getOrElse(Map.empty)
    val keeperIds: Seq[Id[User]] = reco.attribution.flatMap { attr => attr.user.map(_.friends) }.getOrElse(Seq.empty)

    val libInfos = libraries.toSeq.map {
      case (ownerId, libraryId) =>
        val (lib, owner) = db.readOnlyReplica { implicit session =>
          libRepo.get(libraryId) -> basicUserRepo.load(ownerId)
        }

        RecoLibraryInfo(
          owner = owner,
          id = Library.publicId(libraryId),
          name = lib.name,
          path = Library.formatLibraryPath(owner.username, lib.slug)
        )
    }

    UriRecoItemInfo(
      id = nUri.externalId,
      title = nUri.title,
      url = nUri.url,
      keepers = db.readOnlyReplica { implicit session => keeperIds.toSet.map(basicUserRepo.load).toSeq },
      libraries = libInfos,
      others = reco.attribution.map { attr =>
        attr.user.map(_.others)
      }.flatten.getOrElse(0),
      siteName = DomainToNameMapper.getNameFromUrl(nUri.url),
      summary = uriSummary
    )
  }

  private def contstructAttributionInfos(attr: SeedAttribution): Seq[RecoAttributionInfo] = {
    val libraryAttrInfos = attr.library.map { libAttrs =>
      libAttrs.libraries.map { libId =>
        val (lib, owner): (Library, User) = db.readOnlyReplica { implicit session =>
          val lib = libRepo.get(libId)
          val owner = userRepo.get(lib.ownerId)
          (lib, owner)
        }
        RecoAttributionInfo(
          kind = RecoAttributionKind.Library,
          name = Some(lib.name),
          url = Some(Library.formatLibraryPath(owner.username, lib.slug)),
          when = None
        )
      }
    } getOrElse Seq.empty

    libraryAttrInfos
  }

  def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[FullUriRecoResults] = {
    curator.topRecos(userId, source, subSource, more, recencyWeight, context).flatMap { recoResults =>
      val recos = recoResults.recos
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      val infoF = Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          val attributionInfo = (reco.attribution map contstructAttributionInfos) map RecoMetaData.apply
          FullUriRecoInfo(
            kind = RecoKind.Keep,
            metaData = attributionInfo,
            itemInfo = itemInfo,
            explain = reco.explain
          )
        }
      })

      infoF.map { info => FullUriRecoResults(info, recoResults.context) }
    }
  }

  def topPublicRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val recosFut = if (userExperimentCommander.userHasExperiment(userId, ExperimentType.NEW_PUBLIC_FEED)) {
      curator.topPublicRecos(Some(userId))
    } else {
      val magicRecosUriIds = Seq(9282L, 1113120L, 1174911L, 1322581L, 1429738L, 1455719L, 1597454L, 1698263L, 1912272L, 1946530L, 1959849L, 2060916L, 2143952L, 2147214L, 2163010L, 2320712L, 2439562L, 2665654L, 2669346L, 2765186L, 2767408L, 2804797L, 2819130L, 2838370L, 2908230L, 2917336L, 2950411L, 3035002L, 3035085L, 3035111L, 3036832L, 3036840L, 3036841L, 3036844L, 3036848L, 3036849L, 3036853L, 3036854L, 3036855L, 3036857L, 3036859L, 3036860L, 3036861L, 3036862L, 3036863L, 3036868L, 3036869L, 3036872L, 3036873L, 3036877L, 3036878L, 3036879L, 3036880L, 3036882L, 3036884L, 3036885L, 3036887L, 3036889L, 3036892L, 3036893L, 3036895L, 3036896L, 3036897L, 3036902L, 3036904L, 3036906L, 3036907L, 3036911L, 3036912L, 3036913L, 3036914L, 3036916L, 3036920L, 3036921L, 3036923L, 3036925L, 3036930L, 3036931L, 3036932L, 3036933L, 3036938L, 3036940L, 3036942L, 3036946L, 3036947L, 3036948L, 3036949L, 3036950L, 3036955L, 3036957L, 3036958L, 3036960L, 3036965L, 3036967L, 3036970L, 3036973L, 3036975L, 3036976L, 3036977L, 3036978L, 3036981L, 3036982L, 3036984L, 3036985L, 3036987L, 3036991L, 3036992L, 3036995L, 3036997L, 3036998L, 3037001L, 3037002L, 3037003L, 3037007L, 3037010L, 463170L, 571466L, 691458L, 924722L, 1103920L, 1153307L, 1211098L, 1269096L, 1422342L, 1783994L, 1938067L, 1976452L, 2154007L, 2261470L, 2277662L, 2808995L, 2912069L, 2912327L, 2929756L, 2969450L, 3034005L, 3035065L, 3035267L, 3036594L).map(Id[NormalizedURI])
      Future.successful(Random.shuffle(magicRecosUriIds).take(10).map { uriId =>
        RecoInfo(
          userId = None,
          uriId = uriId,
          score = 42.0f,
          explain = None,
          attribution = None)
      })
    }
    val uriRecosFut = recosFut.flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          FullUriRecoInfo(
            kind = RecoKind.Keep,
            metaData = None,
            itemInfo = itemInfo
          )
        }
      })
    }

    if (userExperimentCommander.userHasExperiment(userId, ExperimentType.LIBRARIES)) {
      for (uriRecos <- uriRecosFut; libRecos <- curatedPublicLibraryRecos(userId)) yield libRecos.map(_._2) ++ uriRecos
    } else {
      uriRecosFut
    }

  }

  def curatedPublicLibraryRecos(userId: Id[User]): Future[Seq[(Id[Library], FullRecoInfo)]] = {
    val curatedLibIds: Seq[Id[Library]] = Seq(
      25345L, 44612L, 24542L, 25471L, 28148L, 25381L, 27207L, 25370L, 25388L, 25371L, 25340L, 26473L, 26460L, 27238L, 42651L, 25168L, 27760L, 49090L, 47498L
    ).map(Id[Library])

    val curatedLibraries = {
      val libraryById = db.readOnlyReplica { implicit session => libRepo.getLibraries(curatedLibIds.toSet) }
      curatedLibIds.map(libraryById)
    }.filter { lib =>
      lib.visibility == LibraryVisibility.PUBLISHED
    }

    createFullLibraryInfos(userId, curatedLibraries)
  }

  def topPublicLibraryRecos(userId: Id[User], limit: Int, source: RecommendationSource, subSource: RecommendationSubSource, trackDelivery: Boolean = true, context: Option[String]): Future[FullLibRecoResults] = {
    def generateNewContext(oldContext: String, newLibs: Set[Id[Library]]): String = {
      val filter = new LongSetIdFilter() {}
      val oldSet = filter.fromBase64ToSet(oldContext)
      val newSet = oldSet ++ newLibs.map { _.id }
      val newContext = filter.fromSetToBase64(newSet)
      newContext
    }

    // get extra recos from curator incase we filter out some below
    curator.topLibraryRecos(userId, Some(limit * 4), context) flatMap { libResults =>
      val libInfos = libResults.recos
      val libIds = libInfos.map(_.libraryId)
      val libraries = db.readOnlyReplica { implicit s =>
        val mapping = libRepo.getLibraries(libIds.toSet)
        libIds.flatMap { id => mapping.get(id).map { x => (id, x) } }.filter(x => x._2.visibility == LibraryVisibility.PUBLISHED && x._2.state == LibraryStates.ACTIVE)
      }.take(limit)

      val idToLibraryMap = libraries.toMap
      val libsAndRecoInfos = libInfos.map { libInfo =>
        idToLibraryMap.get(libInfo.libraryId).map { library => (library, libInfo) }
      }.flatten

      val libToRecoInfoMap = libsAndRecoInfos.map { case (lib, info) => info.libraryId -> info }.toMap

      // for analytics and delivery tracking
      if (trackDelivery) SafeFuture {
        val deliveredIds = libraries.map(_._1).toSet
        curator.notifyLibraryRecosDelivered(userId, deliveredIds, source, subSource)
      }
      // context from curator is generated with limit * 4 items. Not right.
      val newContext = generateNewContext(context.getOrElse(""), idToLibraryMap.keySet)

      createFullLibraryInfos(userId, libraries map (_._2), id => Some(libToRecoInfoMap(id).explain)).map {
        recosInfo => FullLibRecoResults(recosInfo, newContext)
      }
    }
  }

  private def noopLibRecoExplainer(lib: Id[Library]): Option[String] = None

  private def createFullLibraryInfos(userId: Id[User], libraries: Seq[Library], explainer: Id[Library] => Option[String] = noopLibRecoExplainer): Future[Seq[(Id[Library], FullLibRecoInfo)]] = {
    libCommander.createFullLibraryInfos(Some(userId), showPublishedLibraries = false, maxMembersShown = 10,
      maxKeepsShown = 0, ProcessedImageSize.Large.idealSize, libraries,
      ProcessedImageSize.Large.idealSize, withKeepTime = true).map { fullLibraryInfos =>
        fullLibraryInfos.map {
          case (id, libInfo) => id -> FullLibRecoInfo(metaData = None, itemInfo = libInfo, explain = explainer(id))
        }
      }
  }
}
