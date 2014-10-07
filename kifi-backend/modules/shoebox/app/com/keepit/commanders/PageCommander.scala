package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.classify.{ Domain, DomainClassifier, DomainRepo }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.normalizer.{ NormalizedURIInterner, NormalizationService }
import com.keepit.search.{ AugmentableItem, ItemAugmentationRequest, SearchServiceClient }
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging

import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class PageCommander @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    normalizationService: NormalizationService,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    libraryRepo: LibraryRepo,
    domainClassifier: DomainClassifier,
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
    val (nUriStr, nUri, keepersFutureOpt, domain, keep, tags, position, neverOnSite, host) = db.readOnlyMaster { implicit session =>
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
      (nUriStr, nUri, getKeepersFutureOpt, domain, keep, tags, position, neverOnSite, host)
    }
    val sensitive: Boolean = !experiments.contains(ExperimentType.NOT_SENSITIVE) &&
      (domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false)

    val shown = nUri map { uri => historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id) } getOrElse false

    val (keepers, keeps) = keepersFutureOpt.map { future => Await.result(future, 10 seconds) } getOrElse (Seq[BasicUser](), 0)

    KeeperInfo(
      nUriStr,
      keep.map { k => if (k.isPrivate) "private" else "public" },
      keep.map(_.externalId),
      tags.map { t => SendableTag.from(t.summary) },
      position, neverOnSite, sensitive, shown, keepers, keeps)
  }

  def isSensitiveURI(uri: String): Boolean = {
    val host: Option[String] = URI.parse(uri).get.host.map(_.name)
    val domain: Option[Domain] = db.readOnlyMaster { implicit s => host.flatMap(domainRepo.get(_)) }
    domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false
  }

  def getPageInfo(uri: URI, userId: Id[User], experiments: Set[ExperimentType]): Future[KeeperPageInfo] = {
    val (nUriOpt, nUriStr, domain, position, neverOnSite, host) = db.readOnlyMaster { implicit session =>
      val host: Option[String] = uri.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
          userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }.getOrElse((None, false))
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(uri.raw.get) match {
        case Success(Left(nUri)) => (nUri.url, Some(nUri))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (uri.raw.get, None)
      }
      (nUri, nUriStr, domain, position, neverOnSite, host)
    }
    val sensitive: Boolean = !experiments.contains(ExperimentType.NOT_SENSITIVE) &&
      (domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false)

    val shown = nUriOpt.map { normUri => historyTracker.getMultiHashFilter(userId).mayContain(normUri.id.get.id) } getOrElse false
    nUriOpt.map { normUri =>
      val item = AugmentableItem(normUri.id.get)
      val request = ItemAugmentationRequest.uniform(userId, item)
      searchClient.augmentation(request).map { response =>
        val restrictedKeeps = response.infos(item).keeps
        val restrictedKeepsMap = (restrictedKeeps map { k => k.id -> (k.keptBy, k.keptIn) }).toMap

        val (keepers, keepsInfo) = db.readOnlyMaster { implicit session =>
          val (a, b) = restrictedKeepsMap.map { key =>
            val (keepId, (keeperId, libId)) = key

            // get keeper info (if exists, otherwise just None)
            val keeperOpt = keeperId.map(basicUserRepo.load)

            // get keep info which is based on library info (if exists, otherwise just None)
            val keepDataOpt = libId.map { libId =>
              val lib = libraryRepo.get(libId)
              val mine = userId == keeperId.get
              KeepData(
                id = keepId,
                mine = mine,
                removable = mine || userId == lib.ownerId, // TODO: also make removable true if user has RW lib access
                secret = lib.visibility == LibraryVisibility.SECRET,
                libraryId = Library.publicId(lib.id.get))
            }
            (keeperOpt, keepDataOpt)
          }.toSeq.unzip // separate & flatten to keepers & kept in which libraries
          (a.flatten, b.flatten)
        }
        KeeperPageInfo(nUriStr, position, neverOnSite, sensitive, shown, keepers, keepsInfo)
      }
    }.getOrElse {
      Future.successful(KeeperPageInfo(nUriStr, position, neverOnSite, sensitive, shown, Seq.empty[BasicUser], Seq.empty[KeepData])) // todo: add in otherKeepers?
    }
  }
}
