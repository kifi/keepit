package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.common.akka.TimeoutFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ SlackLog, Logging }
import com.keepit.common.net.URISanitizer
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageStore, ImageSize, S3ImageConfig }
import com.keepit.discussion.{ Discussion, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem, LimitedAugmentationInfo }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.{ BasicAuthor, BasicUser }
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepDecoratorImpl])
trait KeepDecorator {
  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]]
  def filterLibraries(infos: Seq[LimitedAugmentationInfo]): Seq[LimitedAugmentationInfo]
  def getPersonalKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]], useMultilibLogic: Boolean = false): Map[Id[NormalizedURI], Set[PersonalKeep]]
  def getKeepSummaries(keeps: Seq[Keep], idealImageSize: ImageSize): Future[Seq[URISummary]]
}

@Singleton
class KeepDecoratorImpl @Inject() (
  db: Database,
  basicUserRepo: BasicUserRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  libraryRepo: LibraryRepo,
  collectionCommander: CollectionCommander,
  libraryMembershipRepo: LibraryMembershipRepo,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  keepImageCommander: KeepImageCommander,
  libraryCardCommander: LibraryCardCommander,
  userCommander: UserCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  searchClient: SearchServiceClient,
  keepSourceCommander: KeepSourceCommander,
  permissionCommander: PermissionCommander,
  eliza: ElizaServiceClient,
  rover: RoverServiceClient,
  airbrake: AirbrakeNotifier,
  implicit val imageConfig: S3ImageConfig,
  implicit val s3: S3ImageStore,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepDecorator with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def decorateKeepsIntoKeepInfos(viewerIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]] = {
    val keeps = keepsSeq match {
      case k: List[Keep] => k
      case other =>
        // Make sure we're not dealing with a lazy structure here, which doesn't play nice with a database session...
        airbrake.notify("[decorateKeepsIntoKeepInfos] Found it! Grab Léo, Yingjie, and Andrew", new Exception())
        other.toList
    }
    val keepIds = keeps.map(_.id.get).toSet

    if (keeps.isEmpty) Future.successful(Seq.empty[KeepInfo])
    else {
      val augmentationFuture = {
        val items = keeps.map { keep => AugmentableItem(keep.uriId) }
        searchClient.augment(viewerIdOpt, showPublishedLibraries, KeepInfo.maxKeepersShown, KeepInfo.maxLibrariesShown, 0, items).imap(augmentationInfos => filterLibraries(augmentationInfos))
      }
      val ktusByKeep = db.readOnlyMaster(implicit s => ktuRepo.getAllByKeepIds(keeps.map(_.id.get).toSet))
      val entitiesFutures = augmentationFuture.map { augmentationInfos =>
        val idToLibrary = {
          val librariesShown = augmentationInfos.flatMap(_.libraries.map(_._1)).toSet ++ keepsSeq.flatMap(_.libraryId).toSet
          db.readOnlyMaster { implicit s => libraryRepo.getActiveByIds(librariesShown) } //cached
        }

        val basicOrgByLibId = {
          val orgIdByLibId = idToLibrary.collect { case (libId, lib) if lib.organizationId.isDefined => libId -> lib.organizationId.get }
          val orgIds = orgIdByLibId.values.toSet
          val basicOrgById = db.readOnlyMaster(implicit s => organizationInfoCommander.getBasicOrganizations(orgIds))
          orgIdByLibId.mapValues(basicOrgById(_))
        }

        val idToBasicUser = {
          val keepersShown = augmentationInfos.flatMap(_.keepers.map(_._1)).toSet
          val libraryContributorsShown = augmentationInfos.flatMap(_.libraries.map(_._2)).toSet
          val libraryOwners = idToLibrary.values.map(_.ownerId).toSet
          val keepers = keeps.flatMap(_.userId).toSet // is this needed? need to double check, it may be redundant
          val ktuUsers = ktusByKeep.values.flatten.map(_.userId) // may need to use .take(someLimit) for performance
          db.readOnlyMaster { implicit s => basicUserRepo.loadAll(keepersShown ++ libraryContributorsShown ++ libraryOwners ++ keepers ++ ktuUsers) } //cached
        }
        val idToBasicLibrary = idToLibrary.map {
          case (libId, library) =>
            val orgOpt = basicOrgByLibId.get(libId)
            val user = idToBasicUser(library.ownerId)
            libId -> BasicLibrary(library, user, orgOpt.map(_.handle))
        }
        val libraryCardByLibId = {
          val libraries = keeps.flatMap(_.libraryId.map(idToLibrary(_)))
          val cards = db.readOnlyMaster { implicit s =>
            libraryCardCommander.createLibraryCardInfos(libraries, idToBasicUser, viewerIdOpt, withFollowing = true, idealSize = ProcessedImageSize.Medium.idealSize)
          }
          (libraries.map(_.id.get) zip cards).toMap
        }

        (idToBasicUser, idToBasicLibrary, libraryCardByLibId, basicOrgByLibId)
      }
      val pageInfosFuture = getKeepSummaries(keeps, idealImageSize)

      val colls = db.readOnlyMaster { implicit s =>
        keepToCollectionRepo.getCollectionsForKeeps(keeps) //cached
      }.map(collectionCommander.getBasicCollections)

      val sourceAttrsFut = db.readOnlyReplicaAsync { implicit s => keepSourceCommander.getSourceAttributionForKeeps(keepIds) }

      val allMyKeeps = viewerIdOpt.map { userId => getPersonalKeeps(userId, keeps.map(_.uriId).toSet) } getOrElse Map.empty[Id[NormalizedURI], Set[PersonalKeep]]

      val librariesWithWriteAccess = viewerIdOpt.map { userId =>
        db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) } //cached
      } getOrElse Set.empty

      val discussionsByKeepFut = eliza.getDiscussionsForKeeps(keepIds).recover {
        case fail =>
          airbrake.notify(s"[KEEP-DECORATOR] Failed to get discussions for keeps $keepIds", fail)
          Map.empty[Id[Keep], Discussion]
      }
      val discussionsWithStrictTimeout = TimeoutFuture(discussionsByKeepFut)(executionContext, 2.seconds).recover {
        case _ =>
          log.warn(s"[KEEP-DECORATOR] Timed out fetching discussions for keeps $keepIds")
          Map.empty[Id[Keep], Discussion]
      }
      val permissionsByKeep = db.readOnlyMaster(implicit s => permissionCommander.getKeepsPermissions(keepIds, viewerIdOpt))

      for {
        augmentationInfos <- augmentationFuture
        pageInfos <- pageInfosFuture
        sourceAttrs <- sourceAttrsFut
        (idToBasicUser, idToBasicLibrary, idToLibraryCard, idToBasicOrg) <- entitiesFutures
        discussionsByKeep <- discussionsWithStrictTimeout
      } yield {
        val keepsInfo = (keeps zip colls, augmentationInfos, pageInfos).zipped.flatMap {
          case ((keep, collsForKeep), augmentationInfoForKeep, pageInfoForKeep) =>
            val keepers = viewerIdOpt.map { userId => augmentationInfoForKeep.keepers.filterNot(_._1 == userId) } getOrElse augmentationInfoForKeep.keepers
            val keeps = allMyKeeps.getOrElse(keep.uriId, Set.empty)
            val libraries = {
              def doShowLibrary(libraryId: Id[Library]): Boolean = {
                // ensuring consistency of libraries returned by search with the user's latest database data (race condition)
                lazy val publicId = Library.publicId(libraryId)
                !librariesWithWriteAccess.contains(libraryId) || keeps.exists(_.libraryId == publicId)
              }
              augmentationInfoForKeep.libraries.collect { case (libraryId, contributorId, keptAt) if doShowLibrary(libraryId) => (BasicLibraryWithKeptAt(idToBasicLibrary(libraryId), keptAt), idToBasicUser(contributorId)) }
            }

            val bestEffortPath = (keep.title, pageInfoForKeep.title) match {
              case (None, Some(title)) => keep.copy(title = Some(title)).path.relative
              case _ => keep.path.relative
            }

            (for {
              author <- sourceAttrs.get(keep.id.get).map {
                case (_, Some(kifiUser)) => BasicAuthor.fromUser(kifiUser)
                case (attr, _) => BasicAuthor.fromSource(attr)
              } orElse keep.userId.flatMap(keeper => idToBasicUser.get(keeper).map(BasicAuthor.fromUser))
            } yield {
              KeepInfo(
                id = Some(keep.externalId),
                pubId = Some(Keep.publicId(keep.id.get)),
                title = keep.title,
                url = if (sanitizeUrls) URISanitizer.sanitize(keep.url) else keep.url,
                path = bestEffortPath,
                isPrivate = keep.isPrivate,
                user = keep.userId.flatMap(idToBasicUser.get),
                author = author,
                createdAt = Some(getTimestamp(keep)),
                keeps = Some(keeps),
                keepers = Some(keepers.flatMap { case (keeperId, _) => idToBasicUser.get(keeperId) }),
                keepersOmitted = Some(augmentationInfoForKeep.keepersOmitted),
                keepersTotal = Some(augmentationInfoForKeep.keepersTotal),
                libraries = Some(libraries),
                librariesOmitted = Some(augmentationInfoForKeep.librariesOmitted),
                librariesTotal = Some(augmentationInfoForKeep.librariesTotal),
                collections = Some(collsForKeep.map(_.id.get.id).toSet), // Is not used by any client
                tags = Some(collsForKeep.toSet), // Used by site
                hashtags = Some(collsForKeep.toSet.map { c: BasicCollection => Hashtag(c.name) }), // Used by both mobile clients
                summary = Some(pageInfoForKeep),
                siteName = DomainToNameMapper.getNameFromUrl(keep.url),
                libraryId = keep.libraryId.map(Library.publicId),
                library = keep.libraryId.flatMap(idToLibraryCard.get),
                organization = keep.libraryId.flatMap(idToBasicOrg.get),
                sourceAttribution = sourceAttrs.get(keep.id.get),
                note = keep.note,
                discussion = discussionsByKeep.get(keep.id.get),
                participants = ktusByKeep.getOrElse(keep.id.get, Seq.empty).flatMap(ktu => idToBasicUser.get(ktu.userId)),
                permissions = permissionsByKeep.getOrElse(keep.id.get, Set.empty)
              )
            }) tap {
              case None => slackLog.warn(s"Could not generate an author for keep ${keep.id.get}")
              case _ =>
            }
        }
        keepsInfo
      }
    }
  }

  def filterLibraries(infos: Seq[LimitedAugmentationInfo]): Seq[LimitedAugmentationInfo] = {
    val allUsers = (infos flatMap { info =>
      val keepers = info.keepers
      val libs = info.libraries
      libs.map(_._2) ++ keepers.map(_._1)
    }).toSet
    if (allUsers.isEmpty) infos
    else {
      val fakeUsers = userCommander.getAllFakeUsers().intersect(allUsers)
      if (fakeUsers.isEmpty) infos
      else {
        infos map { info =>
          val keepers = info.keepers.filterNot(u => fakeUsers.contains(u._1))
          val libs = info.libraries.filterNot(t => fakeUsers.contains(t._2))
          info.copy(keepers = keepers, libraries = libs)
        }
      }
    }
  }

  def getKeepSummaries(keeps: Seq[Keep], idealImageSize: ImageSize): Future[Seq[URISummary]] = {
    val futureSummariesByUriId = rover.getUriSummaryByUris(keeps.map(_.uriId).toSet)
    val keepImagesByKeepId = keepImageCommander.getBestImagesForKeeps(keeps.map(_.id.get).toSet, ScaleImageRequest(idealImageSize))
    futureSummariesByUriId.map { summariesByUriId =>
      keeps.map { keep =>
        val summary = summariesByUriId.get(keep.uriId).map(_.toUriSummary(idealImageSize)) getOrElse URISummary.empty
        keepImagesByKeepId.get(keep.id.get) match {
          case None => summary
          case Some(keepImage) =>
            summary.copy(imageUrl = keepImage.map(_.imagePath.getUrl), imageWidth = keepImage.map(_.width), imageHeight = keepImage.map(_.height))
        }
      }
    }
  }

  def getPersonalKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]], useMultilibLogic: Boolean = false): Map[Id[NormalizedURI], Set[PersonalKeep]] = {
    val allKeeps = db.readOnlyReplica { implicit session =>
      val writeableLibs = libraryMembershipRepo.getLibrariesWithWriteAccess(userId)
      val oldWay = keepRepo.getByLibraryIdsAndUriIds(writeableLibs, uriIds).toSet
      val newWay = {
        val direct = ktuRepo.getByUserIdAndUriIds(userId, uriIds).map(_.keepId)
        val indirectViaLibraries = ktlRepo.getVisibileFirstOrderImplicitKeeps(uriIds, writeableLibs).map(_.keepId)
        (direct ++ indirectViaLibraries) |> keepRepo.getByIds |> (_.values.toSet)
      }
      if (newWay.map(_.id.get) != oldWay.map(_.id.get)) {
        log.error(s"[KTL-MATCH] getBasicKeeps($userId, $uriIds): ${newWay.map(_.id.get)} != ${oldWay.map(_.id.get)}")
      }
      if (useMultilibLogic) newWay else oldWay
    }
    val grouped = allKeeps.groupBy(_.uriId)
    uriIds.map { uriId =>
      grouped.get(uriId) match {
        case Some(keeps) =>
          val userKeeps = keeps.map { keep =>
            val mine = keep.userId.contains(userId)
            val removable = true // all keeps here are writeable
            PersonalKeep(
              id = keep.externalId,
              mine = mine,
              removable = removable,
              visibility = keep.visibility,
              libraryId = keep.libraryId.map(Library.publicId)
            )
          }
          uriId -> userKeeps
        case _ =>
          uriId -> Set.empty[PersonalKeep]
      }
    }.toMap
  }
}

object KeepDecorator {
  // turns '[#...]' to '[\#...]'. Similar for '[@...]'
  val escapeMarkupsRe = """\[([#@])""".r
  def escapeMarkupNotes(str: String): String = {
    escapeMarkupsRe.replaceAllIn(str, """[\\$1""")
  }

  // turns '[\#...]' to '[#...]'. Similar for '[\@...]'
  val unescapeMarkupsRe = """\[\\([#@])""".r
  def unescapeMarkupNotes(str: String): String = {
    unescapeMarkupsRe.replaceAllIn(str, """[$1""")
  }
}
