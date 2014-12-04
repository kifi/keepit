package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.classify.{ Domain, DomainRepo }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.normalizer.{ NormalizedURIInterner, NormalizationService }
import com.keepit.search.{ SearchServiceClient }
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import com.keepit.search.augmentation.AugmentableItem

class PageCommander @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    normalizationService: NormalizationService,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    keepsCommander: KeepsCommander,
    collectionRepo: CollectionRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    basicUserRepo: BasicUserRepo,
    historyTracker: SliderHistoryTracker,
    normalizedURIInterner: NormalizedURIInterner,
    searchClient: SearchServiceClient,
    implicit val config: PublicIdConfiguration) extends Logging {

  private def getKeepersFuture(userId: Id[User], uri: NormalizedURI): Future[(Seq[BasicUser], Int)] = {
    searchClient.sharingUserInfo(userId, uri.id.get).map { sharingUserInfo =>
      // use the master. BasicUser is heavily cached.
      val keepers: Seq[BasicUser] = db.readOnlyMaster { implicit session =>
        basicUserRepo.loadAll(sharingUserInfo.sharingUserIds).values.toSeq
      }
      (keepers, sharingUserInfo.keepersEdgeSetSize)
    }
  }

  def getPageDetails(url: String, userId: Id[User], experiments: Set[ExperimentType]): KeeperInfo = {
    if (url.isEmpty) throw new Exception(s"empty url for user $userId")

    // use the master. Keep, KeepToCollection, and Collection are heavily cached.
    val (nUriStr, nUri, keepersFutureOpt, keep, tags, position, neverOnSite, host) = db.readOnlyMaster { implicit session =>
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(url) match {
        case Success(Left(nUri)) => (nUri.url, Some(nUri))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (url, None)
      }

      val getKeepersFutureOpt = nUri map { uri => getKeepersFuture(userId, uri) }

      val keep: Option[Keep] = nUri.flatMap { uri =>
        keepRepo.getByUriAndUser(uri.id.get, userId)
      }
      val tags: Seq[Collection] = keep.map { bm =>
        keepToCollectionRepo.getCollectionsForKeep(bm.id.get).map { collId =>
          collectionRepo.get(collId)
        } filter (_.isActive)
      }.getOrElse(Seq())

      val host: Option[String] = URI.parse(nUriStr).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
          userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }.getOrElse((None, false))
      (nUriStr, nUri, getKeepersFutureOpt, keep, tags, position, neverOnSite, host)
    }

    val shown = nUri map { uri => historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id) } getOrElse false

    val (keepers, keeps) = keepersFutureOpt.map { future => Await.result(future, 10 seconds) } getOrElse (Seq[BasicUser](), 0)

    KeeperInfo(
      nUriStr,
      keep.map { k => if (k.isPrivate) "private" else "public" },
      keep.map(_.externalId),
      tags.map { t => SendableTag.from(t.summary) },
      position, neverOnSite, shown, keepers, keeps)
  }

  def getPageInfo(uri: URI, userId: Id[User], experiments: Set[ExperimentType]): Future[KeeperPageInfo] = {
    val host: Option[String] = uri.host.map(_.name)
    val (nUriOpt, nUriStr, domain) = db.readOnlyMaster { implicit session =>
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(uri.raw.get) match {
        case Success(Left(nUri)) => (nUri.url, Some(nUri))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (uri.raw.get, None)
      }
      (nUri, nUriStr, domain)
    }

    val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
      db.readOnlyReplica { implicit session =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
          userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }
    }.getOrElse((None, false))

    val shown = nUriOpt.exists { normUri =>
      historyTracker.getMultiHashFilter(userId).mayContain(normUri.id.get.id)
    }

    nUriOpt.map { normUri =>
      val augmentFuture = searchClient.augment(
        userId = Some(userId),
        showPublishedLibraries = false,
        maxKeepersShown = Int.MaxValue, // TODO: reduce to 5 once most users have extension 3.3.26 or later
        maxLibrariesShown = 2,
        maxTagsShown = 0,
        items = Seq(AugmentableItem(normUri.id.get)))

      val keepDatas = keepsCommander.getBasicKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))

      augmentFuture map {
        case Seq(info) =>
          val userIdSet = info.keepers.toSet
          val otherKeepersTotal = info.keepersTotal - (if (userIdSet.contains(userId)) 1 else 0)
          val libraryIdPairs = info.libraries.filterNot(_._2 == userId) // TODO: also exclude libraries user is following
          val (basicUserMap, libraryMap) = db.readOnlyMaster { implicit session =>
            val basicUserMap = basicUserRepo.loadAll(userIdSet ++ libraryIdPairs.map(_._2) - userId)
            val libraryMap = libraryRepo.getLibraries(libraryIdPairs.map(_._1).toSet)
            (basicUserMap, libraryMap)
          }
          val keepers = info.keepers.filterNot(_ == userId).map(basicUserMap) // preserving ordering
          val libraries = libraryIdPairs.map { // TODO: sort by friends first, secondarily by num followers (or just trust search ordering?)
            case (libraryId, userId) =>
              val library = libraryMap(libraryId);
              val numKeeps = 0 // TODO
              val numFollowers = 0 // TODO
              Json.obj(
                "name" -> library.name,
                "slug" -> library.slug,
                "owner" -> basicUserMap(userId),
                "keeps" -> numKeeps,
                "followers" -> numFollowers)
          }
          KeeperPageInfo(nUriStr, position, neverOnSite, shown, keepers, info.keepersOmitted, otherKeepersTotal, libraries, keepDatas)
      }
    }.getOrElse {
      Future.successful(KeeperPageInfo(nUriStr, position, neverOnSite, shown, Seq.empty[BasicUser], 0, 0, Seq.empty[JsObject], Seq.empty[KeepData]))
    }
  }

  def getUrlInfo(url: String, userId: Id[User]): Either[String, Seq[KeepData]] = {
    URI.parse(url) match {
      case Success(uri) =>
        val (_, nUriOpt) = db.readOnlyMaster { implicit s =>
          normalizedURIInterner.getByUriOrPrenormalize(uri.raw.get) match {
            case Success(Left(nUri)) => (nUri.url, Some(nUri))
            case Success(Right(pUri)) => (pUri, None)
            case Failure(ex) => (uri.raw.get, None)
          }
        }
        val keepData = nUriOpt.map { normUri =>
          keepsCommander.getBasicKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))
        }.getOrElse(Seq.empty[KeepData])
        Right(keepData)

      case Failure(e) =>
        log.error(s"Error parsing url: $url", e)
        Left("parse_url_error")
    }
  }
}
