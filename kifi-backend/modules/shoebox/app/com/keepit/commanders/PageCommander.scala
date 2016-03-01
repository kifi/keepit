package com.keepit.commanders

import com.keepit.common.cache.{ PrimitiveCacheImpl, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.concurrent.PimpMyFuture._
import com.google.inject.Inject

import com.keepit.classify.{ NormalizedHostname, Domain, DomainRepo }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.URI
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.curator.LibraryQualityHelper
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser
import com.keepit.common.logging.{ AccessLog, Logging }
import org.joda.time.DateTime

import play.api.libs.json._
import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.util.{ Try, Failure, Success }
import com.keepit.search.augmentation.AugmentableItem

class PageCommander @Inject() (
    db: Database,
    domainRepo: DomainRepo,
    userToDomainRepo: UserToDomainRepo,
    keepRepo: KeepRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    keepSourceCommander: KeepSourceCommander,
    keepDecorator: KeepDecorator,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    basicUserRepo: BasicUserRepo,
    historyTracker: SliderHistoryTracker,
    normalizedURIInterner: NormalizedURIInterner,
    searchClient: SearchServiceClient,
    libraryQualityHelper: LibraryQualityHelper,
    userCommander: UserCommander,
    inferredKeeperPositionCache: InferredKeeperPositionCache,
    airbrake: AirbrakeNotifier,
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

  def getPageDetails(url: String, userId: Id[User], experiments: Set[UserExperimentType]): KeeperInfo = {
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

      val host: Option[NormalizedHostname] = URI.parse(nUriStr).get.host.map(_.name).flatMap(name => NormalizedHostname.fromHostname(name))
      val domain: Option[Domain] = host.flatMap(domainRepo.get(_))
      val (position, neverOnSite): (Option[JsObject], Boolean) = domain.map { dom =>
        val position = userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]).orElse(inferKeeperPosition(dom.id.get))
        val neverShow = userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW).exists(_.state == UserToDomainStates.ACTIVE)
        (position, neverShow)
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

  def getPageInfo(uri: URI, userId: Id[User], experiments: Set[UserExperimentType]): Future[KeeperPageInfo] = {
    val useMultilibLogic = experiments.contains(UserExperimentType.KEEP_MULTILIB)
    val host: Option[NormalizedHostname] = uri.host.flatMap(host => NormalizedHostname.fromHostname(host.name, allowInvalid = true))
    val domainF = db.readOnlyMasterAsync { implicit session =>
      val domainOpt = host.flatMap(domainRepo.get(_))
      domainOpt.map { dom =>
        val position = userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.KEEPER_POSITION).map(_.value.get.as[JsObject]).orElse(inferKeeperPosition(dom.id.get))
        val neverShow = userToDomainRepo.get(userId, dom.id.get, UserToDomainKinds.NEVER_SHOW).exists(_.state == UserToDomainStates.ACTIVE)
        (position, neverShow)
      }.getOrElse((None, false))
    }
    val uriInfoF = db.readOnlyMasterAsync { implicit session =>
      val (nUriStr, nUri) = normalizedURIInterner.getByUriOrPrenormalize(uri.raw.get) match {
        case Success(Left(n)) => (n.url, Some(n))
        case Success(Right(pUri)) => (pUri, None)
        case Failure(ex) => (uri.raw.get, None)
      }
      (nUri, nUriStr)
    }

    val infoF = for {
      (position, neverOnSite) <- domainF
      (nUriOpt, nUriStr) <- uriInfoF
    } yield {
      val shown = nUriOpt.exists { normUri =>
        historyTracker.getMultiHashFilter(userId).mayContain(normUri.id.get.id)
      }

      nUriOpt.map { normUri =>
        augmentUriInfo(normUri, userId, useMultilibLogic).map { info =>
          KeeperPageInfo(nUriStr, position, neverOnSite, shown, info.keepers, info.keepersTotal, info.libraries, info.sources, info.keeps)
        }
      }.getOrElse {
        Future.successful(KeeperPageInfo(nUriStr, position, neverOnSite, shown, Seq.empty[BasicUser], 0, Seq.empty[JsObject], Seq.empty[SourceAttribution], Seq.empty[KeepData]))
      }
    }
    infoF.flatten
  }

  private val doNotMoveKeeperDomains = Set(
    371L // ted.com
  ).map(Id[Domain](_))
  @StatsdTiming("PageCommander.inferKeeperPosition")
  private def inferKeeperPosition(domainId: Id[Domain])(implicit session: RSession): Option[JsObject] = {
    // if a domain has more than $minSamples users moving it in the past $since months, move the keeper to a popular location
    inferredKeeperPositionCache.getOrElseOpt(InferredKeeperPositionKey(domainId)) {
      val since = currentDateTime.minusMonths(6)
      val roundToNearest = 100 // return value will vary in roundToNearest intervals
      val minSamples = 10

      val positions = userToDomainRepo.getPositionsForDomain(domainId, sinceOpt = Some(since))
      val positionOpt = if (positions.size >= minSamples) {
        val countByPosition = positions.foldLeft(Map.empty[Long, Int]) {
          case (countByBucket, js) =>
            val bottomOpt = (js \ "bottom").asOpt[Double]
            val topOpt = (js \ "top").asOpt[Double]
            val position = Math.round(bottomOpt.orElse(topOpt.map(_ * -1)).getOrElse(0.0) / roundToNearest.toDouble) * roundToNearest
            countByBucket + (position -> (countByBucket.getOrElse(position, 0) + 1))
        } - 0 // ignore default position

        val (positionMode, _) = countByPosition.maxBy { case (position, count) => count }
        if (positionMode < 0) Some(Json.obj("top" -> -positionMode))
        else Some(Json.obj("bottom" -> positionMode))
      } else None

      if (doNotMoveKeeperDomains.contains(domainId)) {
        None
      } else {
        positionOpt.foreach(pos => log.info(s"[inferKeeper] setting position on domain id=${domainId.id} to position=${Json.stringify(pos)}"))
        positionOpt
      }
    }
  }

  private def filterLibrariesUserDoesNotOwnOrFollow(libraries: Seq[(Id[Library], Id[User], DateTime)], userId: Id[User])(implicit session: RSession): Seq[Library] = {
    val otherLibraryIds = libraries.filterNot(_._2 == userId).map(_._1)
    val memberLibraryIds = libraryMembershipRepo.getWithLibraryIdsAndUserId(otherLibraryIds.toSet, userId).filter(lm => lm._2.isDefined).keys
    val libraryIds = otherLibraryIds.diff(memberLibraryIds.toSeq)
    val libraryMap = libraryRepo.getActiveByIds(libraryIds.toSet).filter(_._2.state == LibraryStates.ACTIVE)
    libraryIds.flatMap(libraryMap.get)
  }

  def firstQualityFilterAndSort(libraries: Seq[Library]): Seq[Library] = {
    val allowedLibraryKinds: Set[LibraryKind] = Set(LibraryKind.USER_CREATED)
    libraries.filter { lib =>
      allowedLibraryKinds.contains(lib.kind) && !libraryQualityHelper.isBadLibraryName(lib.name)
    }.sortBy(lib => (-lib.memberCount, lib.name))
  }

  def secondQualityFilter(libraries: Seq[Library]): Seq[Library] = libraries.filter { lib =>
    val count = lib.keepCount
    val followers = lib.memberCount
    val descriptionCredit = lib.description.map(d => (d.length / 10).max(2)).getOrElse(0)
    val credit = followers + descriptionCredit
    //must have at least five keeps, if have some followers or description can do with a bit less
    //also, must not have more then 30 keeps unless has some followers or description and then we'll scale by that
    (count >= (5 - credit).min(2)) && (count < (30 + credit * 50))
  }

  private def augmentUriInfo(normUri: NormalizedURI, userId: Id[User], useMultilibLogic: Boolean = false): Future[KeeperPagePartialInfo] = {
    val augmentFuture = searchClient.augment(
      userId = Some(userId),
      showPublishedLibraries = true,
      maxKeepsShown = 10, // actually used to compute fewer sources
      maxKeepersShown = 5,
      maxLibrariesShown = 10, //actually its three, but we're trimming them up a bit
      maxTagsShown = 0,
      items = Seq(AugmentableItem(normUri.id.get)))

    val keepDatas = keepDecorator.getPersonalKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))

    augmentFuture map {
      case Seq(info) =>
        val userIdSet = info.keepers.map(_._1).toSet
        val (basicUserMap, libraries, sources) = db.readOnlyMaster { implicit session =>
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
          val sources = keepSourceCommander.getSourceAttributionForKeeps(info.keeps.map(_.id).toSet).values.map(_._1).toSeq.sortBy {
            case _: SlackAttribution => true
            case _ => false
          }.take(5)
          (basicUserMap, topLibs, sources)
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
        KeeperPagePartialInfo(keepers, otherKeepersTotal, libraryObjs, sources, keepDatas)
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
          keepDecorator.getPersonalKeeps(userId, Set(normUri.id.get))(normUri.id.get).toSeq.map(KeepData(_))
        }.getOrElse(Seq.empty[KeepData])
        Right(keepData)

      case Failure(e) =>
        log.error(s"Error parsing url: $url", e)
        Left("parse_url_error")
    }
  }
}

case class InferredKeeperPositionKey(id: Id[Domain]) extends Key[JsObject] {
  override val version = 4
  val namespace = "inferred_keeper_position"
  def toKey(): String = id.id.toString
}

class InferredKeeperPositionCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[InferredKeeperPositionKey, JsObject](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KeeperPagePartialInfo(
  keepers: Seq[BasicUser],
  keepersTotal: Int,
  libraries: Seq[JsObject],
  sources: Seq[SourceAttribution],
  keeps: Seq[KeepData])
