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

  def getPageInfo(url: String, userId: Id[User], experiments: Set[ExperimentType]): Future[KeeperPageInfo] = {
    if (url.isEmpty) throw new Exception(s"empty url for user $userId")

    val (nUriStr, nUri, domain, position, neverOnSite, host) = db.readOnlyMaster { implicit session =>
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(url) match {
        case Success(Left(nUri)) => (nUri.url, Some(nUri))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (url, None)
      }

      val host: Option[String] = URI.parse(nUriStr).get.host.map(_.name)
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
          userToDomainRepo.exists(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW))
      }.getOrElse((None, false))
      (nUriStr, nUri, domain, position, neverOnSite, host)
    }
    val sensitive: Boolean = !experiments.contains(ExperimentType.NOT_SENSITIVE) &&
      (domain.flatMap(_.sensitive) orElse host.flatMap(domainClassifier.isSensitive(_).right.toOption) getOrElse false)

    val shown = nUri map { uri => historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id) } getOrElse false

    nUri.map { uri =>
      val item = AugmentableItem(uri.id.get)
      val request = ItemAugmentationRequest.uniform(userId, item)
      searchClient.augmentation(request).map { response =>
        val restrictedKeeps = response.infos(item).keeps

        val restrictedKeepsMap = (restrictedKeeps map { k => k.id -> (k.keptBy, k.keptIn) }).toMap

        val (keepers, keeps) = db.readOnlyMaster { implicit session =>
          val (a, b) = restrictedKeepsMap.map { m =>
            val keepId = m._1
            val keeperId = m._2._1
            val libId = m._2._2

            val keeperOpt = keeperId.map(basicUserRepo.load(_))
            val libOpt = libId.map(libraryRepo.get(_))
            val libDataOpt = libOpt.map { lib =>
              val owner = basicUserRepo.load(lib.ownerId)
              LibraryData(
                Library.publicId(lib.id.get),
                lib.name,
                lib.visibility,
                Library.formatLibraryUrl(owner.username, owner.externalId, lib.slug))
            }
            val keepDataOpt = libDataOpt.map(KeepData(keepId, true, true, _))
            (keeperOpt, keepDataOpt)
          }.toSeq.unzip
          (a.flatten, b.flatten)
        }
        KeeperPageInfo(nUriStr, position, neverOnSite, sensitive, shown, keepers, keeps)
      }
    }.getOrElse {
      Future.successful(KeeperPageInfo(nUriStr, position, neverOnSite, sensitive, shown, Seq.empty[BasicUser], Seq.empty[KeepData])) // todo: add in otherKeepers?
    }
  }
}
