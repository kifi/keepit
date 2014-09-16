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
  ExperimentType
}
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
  LibRecoItemInfo
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
    libCommander: LibraryCommander,
    uriSummaryCommander: URISummaryCommander,
    basicUserRepo: BasicUserRepo,
    keepRepo: KeepRepo,
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
              uriSummary = Some(uriSummary.copy(description = Some(augmentedDescription))),
              others = reco.attribution.get.user.map(_.others),
              keepers = db.readOnlyReplica { implicit session => reco.attribution.get.user.map(_.friends.map(basicUserRepo.load).toSet) }
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
    UriRecoItemInfo(
      id = nUri.externalId,
      title = nUri.title,
      url = nUri.url,
      keepers = reco.attribution.map { attr =>
        db.readOnlyReplica { implicit session => attr.user.map(_.friends.map(basicUserRepo.load)) }
      }.flatten.getOrElse(Seq.empty),
      others = reco.attribution.map { attr =>
        attr.user.map(_.others)
      }.flatten.getOrElse(0),
      siteName = DomainToNameMapper.getNameFromUrl(nUri.url),
      summary = uriSummary
    )
  }

  private def contstructAttributionInfos(attr: SeedAttribution): Seq[RecoAttributionInfo] = {
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

    attr.topic.map { topicAttr =>
      keepAttrInfos :+ RecoAttributionInfo(
        kind = RecoAttributionKind.Topic,
        name = Some(topicAttr.topicName),
        url = None,
        when = None
      )
    } getOrElse keepAttrInfos
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
            itemInfo = itemInfo
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
    Future.sequence(db.readOnlyReplica { implicit session =>
      Seq[Id[Library]](Id[Library](24138)).map(libRepo.get)
    }.map { lib =>
      libCommander.createFullLibraryInfo(userId, lib).map(lib.ownerId -> _)
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
