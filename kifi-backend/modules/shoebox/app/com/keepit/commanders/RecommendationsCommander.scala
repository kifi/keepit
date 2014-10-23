package com.keepit.commanders

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{
  User,
  NormalizedURI,
  UriRecommendationFeedback,
  NormalizedURIRepo,
  UriRecommendationScores,
  NormalizedURIStates,
  URISummary,
  KeepRepo,
  KeepStates,
  Keep,
  LibraryRepo,
  Library,
  ExperimentType,
  UserRepo
}
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{
  RecoInfo,
  RecommendationClientType,
  FullRecoInfo,
  UriRecoItemInfo,
  RecoMetaData,
  SeedAttribution,
  RecoAttributionInfo,
  RecoAttributionKind,
  RecoKind,
  LibRecoItemInfo,
  RecoLibraryInfo
}
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.domain.DomainToNameMapper

import com.google.inject.Inject
import com.keepit.normalizer.NormalizedURIInterner

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, Json }

import scala.concurrent.Future

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
          path = Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)
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
          url = Some(Library.formatLibraryPath(owner.username, owner.externalId, lib.slug)),
          when = None
        )
      }
    } getOrElse Seq.empty

    val keepAttrInfos = attr.keep.map { keepAttr =>
      keepAttr.keeps.map { keepId =>
        db.readOnlyReplica { implicit session => keepRepo.get(keepId) }
      } filter { keep =>
        keep.state == KeepStates.ACTIVE
      } map { keep =>
        RecoAttributionInfo(
          kind = RecoAttributionKind.Keep,
          name = keep.title,
          url = Some(keep.url),
          when = Some(keep.createdAt)
        )
      }
    } getOrElse Seq.empty

    val topicAttrInfos = attr.topic.map { topicAttr =>
      Seq(RecoAttributionInfo(
        kind = RecoAttributionKind.Topic,
        name = Some(topicAttr.topicName),
        url = None,
        when = None
      ))
    } getOrElse Seq.empty

    libraryAttrInfos ++ keepAttrInfos ++ topicAttrInfos
  }

  def topRecos(userId: Id[User], clientType: RecommendationClientType, more: Boolean, recencyWeight: Float): Future[Seq[FullRecoInfo]] = {
    curator.topRecos(userId, clientType, more, recencyWeight).flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          val attributionInfo = contstructAttributionInfos(reco.attribution.get)
          FullRecoInfo(
            kind = RecoKind.Keep,
            metaData = Some(RecoMetaData(attributionInfo)),
            itemInfo = itemInfo,
            explain = reco.explain
          )
        }
      })
    }
  }

  def topPublicRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val uriRecosFut = curator.topPublicRecos().flatMap { recos =>
      val recosWithUris: Seq[(RecoInfo, NormalizedURI)] = db.readOnlyReplica { implicit session =>
        recos.map { reco => (reco, nUriRepo.get(reco.uriId)) }
      }
      Future.sequence(recosWithUris.map {
        case (reco, nUri) => uriSummaryCommander.getDefaultURISummary(nUri, waiting = false).map { uriSummary =>
          val itemInfo = constructRecoItemInfo(nUri, uriSummary, reco)
          FullRecoInfo(
            kind = RecoKind.Keep,
            metaData = None,
            itemInfo = itemInfo
          )
        }
      })
    }

    if (userExperimentCommander.userHasExperiment(userId, ExperimentType.LIBRARIES)) {
      for (uriRecos <- uriRecosFut; libRecos <- topPublicLibraryRecos(userId)) yield libRecos ++ uriRecos
    } else {
      uriRecosFut
    }

  }

  def topPublicLibraryRecos(userId: Id[User]): Future[Seq[FullRecoInfo]] = {
    val curatedLibs: Seq[Id[Library]] = Seq(
      25537L, 25116L, 24542L, 25345L, 25471L, 25381L, 24203L, 25370L, 25388L, 25528L, 25371L, 25350L, 25340L, 25000L, 26106L
    ).map(Id[Library])

    Future.sequence(db.readOnlyReplica { implicit session =>
      curatedLibs.map(libRepo.get)
    }.map { lib =>
      libCommander.createFullLibraryInfo(Some(userId), lib).map(lib.ownerId -> _)
    }).map {
      libInfosWithOwner =>
        libInfosWithOwner.map {
          case (ownerId, libInfo) =>
            val item = LibRecoItemInfo(
              id = libInfo.id,
              name = libInfo.name,
              url = libInfo.url,
              description = libInfo.description,
              owner = db.readOnlyReplica { implicit session => basicUserRepo.load(ownerId) },
              followers = libInfo.followers,
              numFollowers = libInfo.numFollowers,
              numKeeps = libInfo.numKeeps)

            FullRecoInfo(
              kind = RecoKind.Library,
              metaData = None,
              itemInfo = item
            )
        }
    }
  }

}
