package com.keepit.commanders

import com.keepit.common.concurrent.PimpMyFuture._
import com.google.inject.Inject

import com.keepit.classify.{ Domain, DomainRepo }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.LibraryQualityHelper
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.common.logging.Logging
import com.kifi.macros.json
import org.joda.time.DateTime

import play.api.libs.json._
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import com.keepit.search.augmentation.AugmentableItem

class PageCommander @Inject() (
    db: Database,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    keepDecorator: KeepDecorator,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    basicUserRepo: BasicUserRepo,
    historyTracker: SliderHistoryTracker,
    normalizedURIInterner: NormalizedURIInterner,
    searchClient: SearchServiceClient,
    libraryQualityHelper: LibraryQualityHelper,
    userCommander: UserCommander,
    airbrake: AirbrakeNotifier,
    relatedPageCommander: RelatedPageCommander,
    implicit val executionContext: ExecutionContext,
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
          userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW).exists(_.state == UserToDomainStates.ACTIVE))
      }.getOrElse((None, false))
      (nUriStr, nUri, getKeepersFutureOpt, keep, tags, position, neverOnSite, host)
    }

    val shown = nUri exists { uri =>
      historyTracker.getMultiHashFilter(userId).mayContain(uri.id.get.id)
    }

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
    val domainF = db.readOnlyMasterAsync { implicit session =>
      val domainOpt = host.flatMap(domainRepo.get(_))
      domainOpt.map { dom =>
        (userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]),
          userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW).exists(_.state == UserToDomainStates.ACTIVE))
      }.getOrElse((None, false))
    }
    val uriInfoF = db.readOnlyMasterAsync { implicit session =>
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(uri.raw.get) match {
        case Success(Left(nUri)) => (nUri.url, Some(nUri))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (uri.raw.get, None)
      }
      (nUri, nUriStr)
    }

    val relatedPagesF = uriInfoF.flatMap {
      case (uri, _) =>
        if (uri.isDefined && experiments.contains(ExperimentType.RELATED_PAGE_INFO)) {
          relatedPageCommander.getRelatedPageInfo(uri.get.id.get).recover {
            case _ =>
              airbrake.notify(s"error in getting related page info for uri: ${uri.get.id.get}")
              Seq()
          }
        } else {
          Future.successful(Seq[RelatedPageInfo]())
        }
    }

    val infoF = for {
      (position, neverOnSite) <- domainF
      (nUriOpt, nUriStr) <- uriInfoF
      relatedPages <- relatedPagesF
    } yield {
      val shown = nUriOpt.exists { normUri =>
        historyTracker.getMultiHashFilter(userId).mayContain(normUri.id.get.id)
      }

      nUriOpt.map { normUri =>
        augmentUriInfo(normUri, userId).map { info =>
          KeeperPageInfo(nUriStr, position, neverOnSite, shown, info.keepers, info.keepersTotal, info.libraries, info.keeps, relatedPages)
        }
      }.getOrElse {
        Future.successful(KeeperPageInfo(nUriStr, position, neverOnSite, shown, Seq.empty[BasicUser], 0, Seq.empty[JsObject], Seq.empty[KeepData], Seq.empty[RelatedPageInfo]))
      }
    }
    infoF.flatten
  }

  private def filterLibrariesUserDoesNotOwnOrFollow(libraries: Seq[(Id[Library], Id[User], Option[DateTime])], userId: Id[User])(implicit session: RSession): Seq[Library] = {
    val otherLibraryIds = libraries.filterNot(_._2 == userId).map(_._1)
    val memberLibraryIds = libraryMembershipRepo.getWithLibraryIdsAndUserId(otherLibraryIds.toSet, userId).keys
    val libraryIds = otherLibraryIds.diff(memberLibraryIds.toSeq)
    val libraryMap = libraryRepo.getLibraries(libraryIds.toSet)
    libraryIds.map(libraryMap)
  }

  def firstQualityFilterAndSort(libraries: Seq[Library]): Seq[Library] = {
    libraries.filterNot { lib =>
      require(lib.state == LibraryStates.ACTIVE, s"library is not active: $lib")
      require(lib.kind == LibraryKind.USER_CREATED || lib.kind == LibraryKind.SYSTEM_PERSONA, s"library is not persona or user created: $lib")
      libraryQualityHelper.isBadLibraryName(lib.name)
    } sortBy (lib => (lib.kind != LibraryKind.USER_CREATED, -1 * lib.memberCount))
  }

  def secondQualityFilter(libraries: Seq[Library]): Seq[Library] = libraries.filter { lib =>
    val count = lib.keepCount
    val followers = lib.memberCount
    val descriptionCredit = lib.description.map(d => (d.size / 10).max(2)).getOrElse(0)
    val credit = followers + descriptionCredit
    //must have at least five keeps, if have some followers or description can do with a bit less
    //also, must not have more then 30 keeps unless has some followers or description and then we'll scale by that
    (count >= (5 - credit).min(2)) && (count < (30 + credit * 50))
  }

  private def augmentUriInfo(normUri: NormalizedURI, userId: Id[User]): Future[KeeperPagePartialInfo] = {
    val augmentFuture = searchClient.augment(
      userId = Some(userId),
      showPublishedLibraries = true,
      maxKeepersShown = 5,
      maxLibrariesShown = 10, //actually its three, but we're trimming them up a bit
      maxTagsShown = 0,
      items = Seq(AugmentableItem(normUri.id.get)))

    val keepDatas = keepDecorator.getBasicKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))

    augmentFuture map {
      case Seq(info) =>
        val userIdSet = info.keepers.map(_._1).toSet
        val (basicUserMap, libraries) = db.readOnlyMaster { implicit session =>
          val notMyLibs = filterLibrariesUserDoesNotOwnOrFollow(info.libraries, userId)
          val libraries = firstQualityFilterAndSort(notMyLibs)
          val qualityLibraries = secondQualityFilter(libraries)
          val basicUserMap = basicUserRepo.loadAll(userIdSet ++ qualityLibraries.map(_.ownerId) - userId)
          val topLibs = if (qualityLibraries.isEmpty) {
            qualityLibraries
          } else {
            val fakeUsers = userCommander.getAllFakeUsers()
            qualityLibraries.takeWhile(lib => !fakeUsers.contains(lib.ownerId)).take(2)
          }
          (basicUserMap, topLibs)
        }

        val keeperIdsToExclude = Set(userId) ++ libraries.map(_.ownerId)
        val keepers = info.keepers.collect { case (keeperId, _) if !keeperIdsToExclude.contains(keeperId) => basicUserMap(keeperId) } // preserving ordering
        val otherKeepersTotal = info.keepersTotal - (if (userIdSet.contains(userId)) 1 else 0)
        val followerCounts = db.readOnlyReplica { implicit session =>
          libraryMembershipRepo.countWithAccessByLibraryId(libraries.map(_.id.get).toSet, LibraryAccess.READ_ONLY)
        }
        val libraryObjs = libraries.map { lib =>
          Json.obj(
            "name" -> lib.name,
            "slug" -> lib.slug,
            "color" -> lib.color,
            "owner" -> basicUserMap(lib.ownerId),
            "keeps" -> lib.keepCount,
            "followers" -> followerCounts(lib.id.get))
        }
        KeeperPagePartialInfo(keepers, otherKeepersTotal, libraryObjs, keepDatas)
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
          keepDecorator.getBasicKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))
        }.getOrElse(Seq.empty[KeepData])
        Right(keepData)

      case Failure(e) =>
        log.error(s"Error parsing url: $url", e)
        Left("parse_url_error")
    }
  }
}

case class KeeperPagePartialInfo(
  keepers: Seq[BasicUser],
  keepersTotal: Int,
  libraries: Seq[JsObject],
  keeps: Seq[KeepData])

case class RelatedPageInfo(title: String, url: String, image: String, width: Option[Int] = None, height: Option[Int] = None)
object RelatedPageInfo {
  implicit val writes = Json.writes[RelatedPageInfo]
}

class RelatedPageCommander @Inject() (
    db: Database,
    uriRepo: NormalizedURIRepo,
    uriSummaryCmdr: URISummaryCommander,
    implicit val executionContext: ExecutionContext,
    cortex: CortexServiceClient) {

  private val MAX_LOOKUP_SIZE = 25
  private val MIN_POOL_SIZE = 5
  private val RETURN_SIZE = 5

  def getRelatedPageInfo(uriId: Id[NormalizedURI]): Future[Seq[RelatedPageInfo]] = {
    val urisF = cortex.similarURIs(uriId)(None).map { uriIds =>
      val current = db.readOnlyReplica { implicit s => uriRepo.get(uriId) }
      val uris = db.readOnlyReplica { implicit s => uriIds.take(MAX_LOOKUP_SIZE).map { uriRepo.get(_) } }
      val filtered = uris.filter { x => x.title.isDefined && x.state == NormalizedURIStates.SCRAPED && x.restriction.isEmpty }
      val uniqueUris = filtered.groupBy(_.title.get.toLowerCase).map { case (title, uriList) => uriList.head }.toArray.filter(_.title.get.toLowerCase != current.title.getOrElse("n/a"))
      uniqueUris
    }

    val summariesF: Future[Seq[URISummary]] = urisF.flatMap { uris =>
      Future.sequence(uris.map { x => uriSummaryCmdr.getDefaultURISummary(x, waiting = false) })
    }

    for {
      uris <- urisF
      summaries <- summariesF
    } yield {
      assume(uris.size == summaries.size)
      val uriWithImages = (uris zip summaries).filter(_._2.imageUrl.isDefined)
      if (uriWithImages.size >= MIN_POOL_SIZE) {
        val pages = uriWithImages.map { case (uri, summary) => RelatedPageInfo(uri.title.get, uri.url, summary.imageUrl.get, summary.imageWidth, summary.imageHeight) }
        scala.util.Random.shuffle(pages.toSeq).take(RETURN_SIZE)
      } else {
        // pool size too small. Don't show anything
        Seq()
      }
    }

  }
}
