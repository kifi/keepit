package com.keepit.commanders

import java.util.concurrent.{ Callable, TimeUnit }

import com.google.common.cache.{ Cache, CacheBuilder }
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.CollectionHelpers
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, StackTrace }
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem, ItemAugmentationRequest }
import com.keepit.typeahead.{ HashtagHit, HashtagTypeahead, TypeaheadHit }
import org.joda.time.DateTime
import play.api.http.Status.{ FORBIDDEN, NOT_FOUND }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Failure, Success }

case class RawBookmarksWithCollection(
  collection: Option[Either[ExternalId[Collection], String]], keeps: Seq[RawBookmarkRepresentation])

object RawBookmarksWithCollection {
  implicit val reads = (
    (__ \ 'collectionId).read(ExternalId.format[Collection])
    .map[Option[Either[ExternalId[Collection], String]]](c => Some(Left(c)))
    orElse (__ \ 'collectionName).readNullable[String]
    .map(_.map[Either[ExternalId[Collection], String]](Right(_))) and
    (__ \ 'keeps).read[Seq[RawBookmarkRepresentation]]
  )(RawBookmarksWithCollection.apply _)
}

case class BulkKeepSelection(
  keeps: Option[Seq[ExternalId[Keep]]],
  tag: Option[ExternalId[Collection]],
  exclude: Option[Seq[ExternalId[Keep]]])
object BulkKeepSelection {
  implicit val format = (
    (__ \ 'keeps).formatNullable[Seq[ExternalId[Keep]]] and
    (__ \ 'tag).formatNullable[ExternalId[Collection]] and
    (__ \ 'exclude).formatNullable[Seq[ExternalId[Keep]]]
  )(BulkKeepSelection.apply, unlift(BulkKeepSelection.unapply))
}

@ImplementedBy(classOf[KeepCommanderImpl])
trait KeepCommander {
  // Getting
  def idsToKeeps(ids: Seq[Id[Keep]])(implicit session: RSession): Seq[Keep]
  def getBasicKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], BasicKeep] // for notifications
  def getCrossServiceKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], CrossServiceKeep] // for discussions
  def getKeepsCountFuture(): Future[Int]
  def getKeep(libraryId: Id[Library], keepExtId: ExternalId[Keep], userId: Id[User]): Either[(Int, String), Keep]
  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]], sanitizeUrls: Boolean): Future[Seq[KeepInfo]]

  // Creating
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], source: KeepSource, socialShare: SocialShare)(implicit context: HeimdalContext): (Keep, Boolean)
  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource)(implicit context: HeimdalContext): (Seq[KeepInfo], Seq[String])
  def persistKeep(k: Keep, users: Set[Id[User]], libraries: Set[Id[Library]])(implicit session: RWSession): Keep

  // Updating / managing
  def updateKeepTitle(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User], title: Option[String])(implicit context: HeimdalContext): Either[(Int, String), Keep]
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String, freshTag: Boolean = true)(implicit session: RWSession): Keep
  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep]
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep]

  // Tagging
  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]]
  def suggestTags(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]]
  def removeTagFromKeeps(keeps: Set[Id[Keep]], tag: Hashtag): Int
  def replaceTagOnKeeps(keeps: Set[Id[Keep]], oldTag: Hashtag, newTag: Hashtag): Int

  // Destroying
  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, KeepInfo]
  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[KeepInfo], Seq[ExternalId[Keep]])]
  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit

  // Data integrity
  def refreshLibrariesHash(keep: Keep)(implicit session: RWSession): Keep
  def refreshParticipantsHash(keep: Keep)(implicit session: RWSession): Keep
  def syncWithLibrary(keep: Keep, library: Library)(implicit session: RWSession): Keep
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit

  // On the way out, hopefully.
  def allKeeps(before: Option[ExternalId[Keep]], after: Option[ExternalId[Keep]], collectionId: Option[ExternalId[Collection]], helprankOpt: Option[String], count: Int, userId: Id[User]): Future[Seq[KeepInfo]]
  def autoFixKeepNoteAndTags(keepId: Id[Keep]): Future[Unit]
}

@Singleton
class KeepCommanderImpl @Inject() (
    db: Database,
    keepInterner: KeepInterner,
    searchClient: SearchServiceClient,
    globalKeepCountCache: GlobalKeepCountCache,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionCommander: CollectionCommander,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    ktlCommander: KeepToLibraryCommander,
    ktuRepo: KeepToUserRepo,
    ktuCommander: KeepToUserCommander,
    collectionRepo: CollectionRepo,
    libraryAnalytics: LibraryAnalytics,
    heimdalClient: HeimdalServiceClient,
    airbrake: AirbrakeNotifier,
    normalizedURIInterner: NormalizedURIInterner,
    clock: Clock,
    libraryRepo: LibraryRepo,
    userRepo: UserRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    hashtagTypeahead: HashtagTypeahead,
    keepDecorator: KeepDecorator,
    twitterPublishingCommander: TwitterPublishingCommander,
    facebookPublishingCommander: FacebookPublishingCommander,
    permissionCommander: PermissionCommander,
    uriHelpers: UriIntegrityHelpers,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends KeepCommander with Logging {

  def idsToKeeps(ids: Seq[Id[Keep]])(implicit session: RSession): Seq[Keep] = {
    val idToKeepMap = keepRepo.getByIds(ids.toSet)
    ids.map(idToKeepMap)
  }

  def getBasicKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], BasicKeep] = {
    db.readOnlyReplica { implicit session =>
      val keeps = keepRepo.getByIds(ids)
      val users = userRepo.getAllUsers(keeps.collect {
        case (id, keep) => keep.userId
      }.toSeq)
      keeps.map {
        case (id, keep) => id -> BasicKeep(
          keep.externalId,
          keep.title,
          keep.url,
          keep.visibility,
          keep.libraryId.map(Library.publicId),
          users(keep.userId).externalId
        )
      }
    }
  }
  def getCrossServiceKeeps(ids: Set[Id[Keep]]): Map[Id[Keep], CrossServiceKeep] = {
    val (keeps, users, libs) = db.readOnlyReplica { implicit s =>
      val keepsById = keepRepo.getByIds(ids)
      val usersById = ktuRepo.getAllByKeepIds(ids).map { case (keepId, ktus) => keepId -> ktus.map(_.userId).toSet }
      val libsById = ktlRepo.getAllByKeepIds(ids).map { case (keepId, ktls) => keepId -> ktls.map(_.libraryId).toSet }
      (keepsById, usersById, libsById)
    }
    keeps.map {
      case (keepId, keep) => keepId -> CrossServiceKeep(
        id = keepId,
        owner = keep.userId,
        users = users.getOrElse(keepId, Set.empty),
        libraries = libs.getOrElse(keepId, Set.empty),
        url = keep.url,
        uriId = keep.uriId,
        keptAt = keep.keptAt,
        title = keep.title,
        note = keep.note
      )
    }
  }

  def getKeepsCountFuture(): Future[Int] = {
    globalKeepCountCache.getOrElseFuture(GlobalKeepCountKey()) {
      Future.sequence(searchClient.indexInfoList()).map { results =>
        var countMap = Map.empty[String, Int]
        results.flatMap(_._2).foreach { info =>
          /**
           * todo(eishay): we need to parse the index family.
           * Name will look like "KeepIndexer_2_4" where the family is "4" and shard id is "2".
           * If there is more then one family at the same time (i.e. "8" based shareds), we'll have double counting.
           * We need to get a count of only one family (say count both and pick the largest one).
           */
          if (info.name.startsWith("KeepIndex")) {
            countMap.get(info.name) match {
              case Some(count) if count >= info.numDocs =>
              case _ => countMap += (info.name -> info.numDocs)
            }
          }
        }
        countMap.values.sum
      }
    }
  }

  def getKeep(libraryId: Id[Library], keepExtId: ExternalId[Keep], userId: Id[User]): Either[(Int, String), Keep] = {
    db.readOnlyMaster { implicit session =>
      if (libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId).isDefined) {
        val oldWay = keepRepo.getByExtIdandLibraryId(keepExtId, libraryId) match {
          case Some(k) => Right(k)
          case None => Left(NOT_FOUND, "keep_not_found")
        }
        val newWay = keepRepo.getOpt(keepExtId) match {
          case Some(k) if ktlCommander.isKeepInLibrary(k.id.get, libraryId) => Right(k)
          case _ => Left(NOT_FOUND, "keep_not_found")
        }
        if (newWay != oldWay) log.info(s"[KTL-MATCH] getKeep: $newWay != $oldWay")

        oldWay
      } else {
        Left(FORBIDDEN, "library_access_denied")
      }
    }
  }

  private def getHelpRankRelatedKeeps(userId: Id[User], selector: HelpRankSelector, beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Future[Seq[(Keep, Option[Int], Option[Int])]] = {
    @inline def filter(counts: Seq[(Id[NormalizedURI], Int)])(implicit r: RSession): Seq[Id[NormalizedURI]] = {
      val uriIds = counts.map(_._1)
      val before = beforeOpt match {
        case None => uriIds
        case Some(beforeExtId) =>
          keepRepo.getByExtIdAndUser(beforeExtId, userId) match {
            case None => uriIds
            case Some(beforeKeep) => uriIds.dropWhile(_ != beforeKeep.uriId).drop(1)
          }
      }
      val after = afterOpt match {
        case None => before
        case Some(afterExtId) => keepRepo.getByExtIdAndUser(afterExtId, userId) match {
          case None => before
          case Some(afterKeep) => uriIds.takeWhile(_ != afterKeep.uriId)
        }
      }
      after
    }

    val keepIdsF = selector match {
      case HelpRankRekeeps => heimdalClient.getUriReKeepsWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
      case HelpRankDiscoveries => heimdalClient.getUriDiscoveriesWithCountsByKeeper(userId) map { counts => counts.map(c => c.uriId -> c.count) }
    }
    keepIdsF.flatMap { counts =>
      val keeps = db.readOnlyReplica { implicit session =>
        val keepUriIds = filter(counts)
        val km = keepRepo.bulkGetByUserAndUriIds(userId, keepUriIds.toSet)
        val sorted = km.valuesIterator.toList.sortBy(_.id).reverse
        if (count > 0) sorted.take(count) else sorted
      }

      // Fetch counts
      val keepIds = keeps.map(_.id.get).toSet
      val discCountsF = heimdalClient.getDiscoveryCountsByKeepIds(userId, keepIds)
      val rekeepCountsF = heimdalClient.getReKeepCountsByKeepIds(userId, keepIds)
      for {
        discCounts <- discCountsF
        rekeepCounts <- rekeepCountsF
      } yield {
        val discMap = discCounts.map { c => c.keepId -> c.count }.toMap
        val rkMap = rekeepCounts.map { c => c.keepId -> c.count }.toMap
        keeps.map { keep =>
          (keep, discMap.get(keep.id.get), rkMap.get(keep.id.get))
        }
      }
    }
  }

  private def getKeepsFromCollection(userId: Id[User], collectionId: ExternalId[Collection], beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
    db.readOnlyReplica { implicit session =>
      val collectionOpt = collectionRepo.getByUserAndExternalId(userId, collectionId)
      val keeps = collectionOpt.map { collection =>
        keepRepo.getByUserAndCollection(userId, collection.id.get, beforeOpt, afterOpt, count)
      } getOrElse Seq.empty

      keeps
    }
  }

  private def getKeeps(userId: Id[User], beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
    db.readOnlyReplica { implicit session =>
      keepRepo.getByUser(userId, beforeOpt, afterOpt, count)
    }
  }

  // Please do not add to this. It mixes concerns and data sources.
  def allKeeps(
    before: Option[ExternalId[Keep]],
    after: Option[ExternalId[Keep]],
    collectionId: Option[ExternalId[Collection]],
    helprankOpt: Option[String],
    count: Int,
    userId: Id[User]): Future[Seq[KeepInfo]] = {

    // The Option[Int]s are help rank counts. Only included when looking at help rank info currently.
    val keepsF: Future[Seq[(Keep, Option[Int], Option[Int])]] = (collectionId, helprankOpt) match {
      case (Some(c), _) => // collectionId is set
        val keeps = getKeepsFromCollection(userId, c, before, after, count)
        Future.successful(keeps.map((_, None, None)))
      case (_, Some(hr)) => // helprankOpt is set
        getHelpRankRelatedKeeps(userId, HelpRankSelector(hr), before, after, count)
      case _ => // neither is set, deliver normal paginated keeps list
        val keeps = getKeeps(userId, before, after, count)
        Future.successful(keeps.map((_, None, None)))
    }

    keepsF.flatMap {
      case keepsWithHelpRankCounts =>
        val (keeps, clickCounts, rkCounts) = keepsWithHelpRankCounts.unzip3
        keepDecorator.decorateKeepsIntoKeepInfos(Some(userId), showPublishedLibraries = false, keeps, ProcessedImageSize.Large.idealSize, sanitizeUrls = false)
    }
  }

  // TODO: if keep is already in library, return it and indicate whether userId is the user who originally kept it
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], source: KeepSource, socialShare: SocialShare)(implicit context: HeimdalContext): (Keep, Boolean) = {
    log.info(s"[keep] $rawBookmark")
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val (keep, isNewKeep) = keepInterner.internRawBookmark(rawBookmark, userId, library, source).get
    postSingleKeepReporting(keep, isNewKeep, library, socialShare)
    (keep, isNewKeep)
  }

  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource)(implicit context: HeimdalContext): (Seq[KeepInfo], Seq[String]) = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val internResponse = keepInterner.internRawBookmarksWithStatus(rawBookmarks, userId, Some(library), source)

    val keeps = internResponse.successes
    log.info(s"[keepMulti] keeps(len=${keeps.length}):${keeps.mkString(",")}")

    (keeps.map(KeepInfo.fromKeep), internResponse.failures.map(_.url))
  }

  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, KeepInfo] = {
    unkeepManyFromLibrary(Seq(keepId), libId, userId) match {
      case Left(why) => Left(why)
      case Right((Seq(), _)) => Left("keep_not_found")
      case Right((Seq(info), _)) => Right(info)
    }
  }

  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[KeepInfo], Seq[ExternalId[Keep]])] = {
    db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId)
    } match {
      case Some(mem) if mem.canWrite =>
        val (keeps, invalidKeepIds) = db.readWrite { implicit s =>
          val (keepsE, invalidKeepIdsE) = keepIds.map { kId =>
            keepRepo.getByExtIdandLibraryId(kId, libId, excludeSet = Set.empty) match {
              case Some(k) =>
                Left(k)
              case None =>
                Right(kId)
            }
          }.partition(_.isLeft)

          val keeps = keepsE.map(_.left.get)
          val invalidKeepIds = invalidKeepIdsE.map(_.right.get)

          val inactivatedKeeps = keeps.map { k =>
            // TODO(ryan): stop deactivating keeps and instead just detach them from libraries
            // just uncomment the line below this and rework some of this
            // ktlCommander.removeKeepFromLibrary(k.id.get, libId)
            deactivateKeep(k)
            k
          }
          finalizeUnkeeping(keeps, userId)

          // Inactivate tags, update tag
          val phantomActiveKeeps = keeps.map(_.copy(state = KeepStates.ACTIVE))
          (keepToCollectionRepo.getCollectionsForKeeps(phantomActiveKeeps) zip keeps).flatMap {
            case (colls, keep) =>
              log.info(s"[unkeepManyFromLibrary] Removing tags from ${keep.id.get}: ${colls.mkString(",")}")
              colls.foreach { collId => keepToCollectionRepo.remove(keep.id.get, collId) }
              colls
          }.foreach { coll =>
            collectionRepo.collectionChanged(coll, inactivateIfEmpty = true)
          }

          (inactivatedKeeps, invalidKeepIds)
        }

        Right((keeps map KeepInfo.fromKeep, invalidKeepIds))
      case _ =>
        Left("permission_denied")
    }
  }

  private def finalizeUnkeeping(keeps: Seq[Keep], userId: Id[User])(implicit session: RWSession, context: HeimdalContext): Unit = {
    // TODO: broadcast over any open user channels
    keeps.groupBy(_.libraryId).toList.collect {
      case (Some(libId), _) =>
        val library = libraryRepo.get(libId)
        libraryRepo.save(library.copy(keepCount = keepRepo.getCountByLibrary(libId)))
        libraryAnalytics.unkeptPages(userId, keeps, library, context)
    }
    session.onTransactionSuccess {
      searchClient.updateKeepIndex()
    }
  }

  def updateKeepTitle(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User], title: Option[String])(implicit context: HeimdalContext): Either[(Int, String), Keep] = {
    db.readOnlyMaster { implicit session =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId)
    } match {
      case Some(mem) if mem.canWrite =>
        val normTitle = title.map(_.trim).filter(_.nonEmpty)
        db.readWrite { implicit s =>
          keepRepo.getByExtIdandLibraryId(keepId, libId) match {
            case Some(keep) if normTitle.isDefined && normTitle != keep.title =>
              val keep2 = keepRepo.save(keep.withTitle(normTitle))
              s.onTransactionSuccess {
                searchClient.updateKeepIndex()
              }
              Right(keep2)
            case Some(keep) =>
              Right(keep)
            case None =>
              Left((NOT_FOUND, "keep_not_found"))
          }
        }
      case _ =>
        Left((FORBIDDEN, "permission_denied"))
    }
  }

  // Updates note on keep, making sure tags are in sync.
  // i.e., the note is the source of truth, and tags are added/removed appropriately
  def updateKeepNote(userId: Id[User], oldKeep: Keep, newNote: String, freshTag: Boolean)(implicit session: RWSession): Keep = {
    // todo IMPORTANT: check permissions here, this lets anyone edit anyone's keep.
    val noteToPersist = Some(newNote.trim).filter(_.nonEmpty)
    val updatedKeep = oldKeep.copy(userId = userId, note = noteToPersist)
    val hashtagNamesToPersist = Hashtags.findAllHashtagNames(noteToPersist.getOrElse(""))
    val (keep, colls) = syncTagsToNoteAndSaveKeep(userId, updatedKeep, hashtagNamesToPersist.toSeq, freshTag = freshTag)
    session.onTransactionSuccess {
      searchClient.updateKeepIndex()
    }
    colls.foreach { c =>
      Try(collectionRepo.collectionChanged(c.id, c.isNewKeep, c.inactivateIfEmpty)) // deadlock prone
    }
    keep
  }

  def getOrCreateTag(userId: Id[User], name: String)(implicit session: RWSession): Collection = {
    val normalizedName = Hashtag(name.trim.replaceAll("""\s+""", " ").take(Collection.MaxNameLength))
    val collection = collectionRepo.getByUserAndName(userId, normalizedName, excludeState = None)
    collection match {
      case Some(t) if t.isActive => t
      case Some(t) => collectionRepo.save(t.copy(state = CollectionStates.ACTIVE, name = normalizedName, createdAt = clock.now()))
      case None => collectionRepo.save(Collection(userId = userId, name = normalizedName))
    }
  }

  // You have keeps, and want tags removed from it.
  def removeTagFromKeeps(keeps: Set[Id[Keep]], tag: Hashtag): Int = {
    db.readWrite { implicit session =>
      var errors = mutable.Set.empty[Id[Keep]]
      val updated = keepRepo.getByIds(keeps).flatMap {
        case ((_, k)) =>
          val existingTags = Hashtags.findAllHashtagNames(k.note.getOrElse("")).map(Hashtag(_)).toSeq
          val existingNormalized = existingTags.map(_.normalized)

          if (k.isActive && existingNormalized.contains(tag.normalized)) {
            val newTags = existingTags.filterNot(_.normalized == tag.normalized).map(_.tag)
            Try(syncTagsToNoteAndSaveKeep(k.userId, k, newTags)) match { // Note will be updated here
              case Success(r) => Some(r)
              case Failure(ex) =>
                errors += k.id.get
                log.warn(s"[removeTagFromKeeps] Failure removing tag for keep ${k.id.get} removing ${tag.tag}. Existing tags: ${k.note}, new tags: $newTags")
                None
            }
          } else None
      }
      updated.values.flatten.distinctBy(_.id).map { c =>
        collectionRepo.collectionChanged(c.id, c.isNewKeep, c.inactivateIfEmpty)
      }
      if (errors.nonEmpty) {
        airbrake.notify(s"[removeTagFromKeeps] Failure removing tag ${tag.tag} from ${errors.size} keeps. See logs for details.")
      }
      updated.size
    }
  }

  // Assumption that all keeps are owned by the same user
  def replaceTagOnKeeps(keeps: Set[Id[Keep]], oldTag: Hashtag, newTag: Hashtag): Int = {
    if (keeps.nonEmpty && oldTag.normalized == newTag.normalized) { // Changing capitalization, etc
      db.readWrite { implicit session =>
        for {
          firstKeepId <- keeps.headOption
          keep = keepRepo.get(firstKeepId)
          existing <- collectionRepo.getByUserAndName(keep.userId, oldTag)
        } yield {
          collectionRepo.save(existing.copy(name = newTag))
        }
      }
    }
    db.readWrite(attempts = 3) { implicit session =>
      var errors = mutable.Set.empty[Id[Keep]]
      val updated = keepRepo.getByIds(keeps).flatMap {
        case ((_, k)) =>
          val existingTags = Hashtags.findAllHashtagNames(k.note.getOrElse("")).map(Hashtag(_)).toSeq
          val existingNormalized = existingTags.map(_.normalized)
          if (k.isActive && existingNormalized.contains(oldTag.normalized)) {
            val newTags = newTag.tag +: existingTags.filterNot(_.normalized == oldTag.normalized).map(_.tag)
            val newNote = k.note.map(Hashtags.replaceTagNameFromString(_, oldTag.tag, newTag.tag))
            Try(syncTagsToNoteAndSaveKeep(k.userId, k.withNote(newNote), newTags)) match {
              case Success(r) => Some(r)
              case Failure(ex) =>
                errors += k.id.get
                log.warn(s"[replaceTagOnKeeps] Failure updating note for keep ${k.id.get} replacing ${oldTag.tag} with ${newTag.tag}. Existing note: ${k.note}, new note: $newNote")
                None
            }
          } else None
      }
      updated.values.flatten.distinctBy(_.id).map { c =>
        collectionRepo.collectionChanged(c.id, c.isNewKeep, c.inactivateIfEmpty)
      }
      if (errors.nonEmpty) {
        airbrake.notify(s"[replaceTagOnKeeps] Failure replacing ${oldTag.tag} with ${newTag.tag} from ${errors.size} keeps. See logs for details.")
      }
      updated.size
    }
  }

  // Given set of tags and keep, update keep note to reflect tag seq (create tags, remove tags, insert into note, remove from note)
  // i.e., source of tag truth is the tag seq, note will be brought in sync
  // Important: Caller's responsibility to call collectionRepo.collectionChanged from the return value for collections that changed
  case class ChangedCollection(id: Id[Collection], isNewKeep: Boolean, inactivateIfEmpty: Boolean)
  private def syncTagsToNoteAndSaveKeep(userId: Id[User], keep: Keep, allTagsKeepShouldHave: Seq[String], freshTag: Boolean = false)(implicit session: RWSession) = {
    // get all tags from hashtag names list
    val selectedTags = allTagsKeepShouldHave.map { getOrCreateTag(userId, _) }
    val selectedTagIds = selectedTags.map(_.id.get).toSet
    // get all active tags for keep to figure out which tags to add & which tags to remove
    val activeTagIds = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).toSet
    val tagIdsToAdd = selectedTagIds.filterNot(activeTagIds.contains)
    val tagIdsToRemove = activeTagIds.filterNot(selectedTagIds.contains)
    var changedCollections = scala.collection.mutable.Set.empty[ChangedCollection]

    // fix k2c for tagsToAdd & tagsToRemove
    tagIdsToAdd.map { tagId =>
      keepToCollectionRepo.getOpt(keep.id.get, tagId) match {
        case None => keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = tagId))
        case Some(k2c) => keepToCollectionRepo.save(k2c.copy(state = KeepToCollectionStates.ACTIVE))
      }
      changedCollections += ChangedCollection(tagId, isNewKeep = freshTag, inactivateIfEmpty = false)
    }
    tagIdsToRemove.map { tagId =>
      keepToCollectionRepo.remove(keep.id.get, tagId)
      changedCollections += ChangedCollection(tagId, isNewKeep = false, inactivateIfEmpty = true)
    }

    // go through note field and find all hashtags
    val keepNote = keep.note.getOrElse("")
    val hashtagsInNote = Hashtags.findAllHashtagNames(keepNote)
    val hashtagsToPersistSet = allTagsKeepShouldHave.toSet

    // find hashtags to remove & to append
    val hashtagsToRemove = hashtagsInNote.filterNot(hashtagsToPersistSet.contains(_))
    val hashtagsToAppend = allTagsKeepShouldHave.filterNot(hashtagsInNote.contains(_))
    val noteWithHashtagsRemoved = Hashtags.removeTagNamesFromString(keepNote, hashtagsToRemove.toSet)
    val noteWithHashtagsAppended = Hashtags.addTagsToString(noteWithHashtagsRemoved, hashtagsToAppend)
    val finalNote = Some(noteWithHashtagsAppended.trim).filterNot(_.isEmpty)

    (keepRepo.save(keep.withNote(finalNote)), changedCollections)
  }

  private def postSingleKeepReporting(keep: Keep, isNewKeep: Boolean, library: Library, socialShare: SocialShare): Unit = SafeFuture {
    log.info(s"postSingleKeepReporting for user ${keep.userId} with $socialShare keep ${keep.title}")
    if (socialShare.twitter) twitterPublishingCommander.publishKeep(keep.userId, keep, library)
    if (socialShare.facebook) facebookPublishingCommander.publishKeep(keep.userId, keep, library)
    searchClient.updateKeepIndex()
  }

  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[(Hashtag, Int)]
    hashtagTypeahead.topN(userId, query, limit).map(_.map(_.info)).map(HashtagHit.highlight(query, _))
  }

  private def searchTagsForKeep(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    val futureHits = searchTags(userId, query, None)
    val existingTags = keepIdOpt.map { keepId =>
      db.readOnlyMaster { implicit session =>
        keepRepo.getOpt(keepId).map { keep =>
          collectionRepo.getHashtagsByKeepId(keep.id.get)
        }.getOrElse(Set.empty)
      }
    }.getOrElse(Set.empty)
    futureHits.imap { hits =>
      val validHits = hits.filterNot(hit => existingTags.contains(hit.tag))
      limit.map(validHits.take) getOrElse validHits
    }
  }

  private def suggestTagsForKeep(userId: Id[User], keepId: ExternalId[Keep], limit: Option[Int]): Future[Seq[Hashtag]] = {
    val keep = db.readOnlyMaster { implicit session => keepRepo.get(keepId) }
    val item = AugmentableItem(keep.uriId, Some(keep.id.get))
    val futureAugmentationResponse = searchClient.augmentation(ItemAugmentationRequest.uniform(userId, item))
    val existingNormalizedTags = db.readOnlyMaster { implicit session => collectionRepo.getHashtagsByKeepId(keep.id.get).map(_.normalized) }
    futureAugmentationResponse.map { response =>
      val suggestedTags = {
        val restrictedKeeps = response.infos(item).keeps.toSet
        val safeTags = restrictedKeeps.flatMap {
          case myKeep if myKeep.keptBy.contains(userId) => myKeep.tags
          case anotherKeep => anotherKeep.tags.filterNot(_.isSensitive)
        }
        val validTags = safeTags.filterNot(tag => existingNormalizedTags.contains(tag.normalized))
        CollectionHelpers.dedupBy(validTags.toSeq.sortBy(-response.scores.byTag(_)))(_.normalized)
      }
      limit.map(suggestedTags.take) getOrElse suggestedTags
    }
  }

  def suggestTags(userId: Id[User], keepIdOpt: Option[ExternalId[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]] = {
    query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => searchTagsForKeep(userId, keepIdOpt, validQuery, Some(limit)).map(_.map(hit => (hit.tag, hit.matches)))
      case None if keepIdOpt.isDefined => suggestTagsForKeep(userId, keepIdOpt.get, Some(limit)).map(_.map((_, Seq.empty[(Int, Int)])))
      case None => Future.successful(Seq.empty) // We don't support this case yet
    }
  }

  def numKeeps(userId: Id[User]): Int = db.readOnlyReplica { implicit s => keepRepo.getCountByUser(userId) }

  // Only use this directly if you want to skip ALL interning features. You probably want KeepInterner.
  def persistKeep(k: Keep, userIds: Set[Id[User]], libraryIds: Set[Id[Library]])(implicit session: RWSession): Keep = {
    require(userIds.contains(k.userId), "keep owner is not one of the connected users")
    require(k.libraryId.toSet == libraryIds, "ktls in 2 or more libraries are not supported")
    val keep = keepRepo.save(k.withLibraries(libraryIds).withParticipants(userIds))

    userIds.foreach { userId => ktuCommander.internKeepInUser(keep, userId, keep.userId) }
    val libraries = libraryRepo.getActiveByIds(libraryIds).values
    libraries.foreach { lib => ktlCommander.internKeepInLibrary(keep, lib, keep.userId) }

    keep
  }
  def refreshLibrariesHash(keep: Keep)(implicit session: RWSession): Keep = {
    val libraries = ktlRepo.getAllByKeepId(keep.id.get).map(_.libraryId).toSet
    keepRepo.save(keep.withLibraries(libraries))
  }
  def refreshParticipantsHash(keep: Keep)(implicit session: RWSession): Keep = {
    val users = ktuRepo.getAllByKeepId(keep.id.get).map(_.userId).toSet
    keepRepo.save(keep.withParticipants(users))
  }
  def syncWithLibrary(keep: Keep, library: Library)(implicit session: RWSession): Keep = {
    require(keep.libraryId == library.id, "keep.libraryId does not match library id!")
    ktlRepo.getByKeepIdAndLibraryId(keep.id.get, library.id.get).foreach { ktl => ktlCommander.syncWithLibrary(ktl, library) }
    keepRepo.save(keep.withLibrary(library))
  }
  def changeOwner(keep: Keep, newOwnerId: Id[User])(implicit session: RWSession): Keep = {
    ktuCommander.removeKeepFromUser(keep.id.get, keep.userId)
    val updatedKeep = keepRepo.save(keep.withOwner(newOwnerId))
    ktuCommander.internKeepInUser(keep, newOwnerId, addedBy = newOwnerId)
    refreshParticipantsHash(updatedKeep)
  }
  private def isKeepInLibraries(keepId: Id[Keep], targetLibraries: Set[Id[Library]])(implicit session: RSession): Boolean = {
    ktlRepo.getAllByKeepId(keepId).map(_.libraryId).toSet == targetLibraries
  }
  private def getKeepsByUriAndLibraries(uriId: Id[NormalizedURI], targetLibraries: Set[Id[Library]])(implicit session: RSession): Set[Keep] = {
    // TODO(ryan): uncomment the line below, and get rid of the require
    // keepRepo.getByUriAndLibrariesHash(uriId, LibrariesHash(targetLibraries)).filter(k => isKeepInLibraries(k.id.get, targetLibraries))
    require(targetLibraries.size <= 1)
    targetLibraries.headOption.map(libId => keepRepo.getPrimaryByUriAndLibrary(uriId, libId).toSet).getOrElse(Set.empty)
  }
  def changeUri(keep: Keep, newUri: NormalizedURI)(implicit session: RWSession): Unit = {
    if (keep.isInactive) {
      val newKeep = keepRepo.save(keep.withNormUriId(newUri.id.get).withPrimary(false))
      ktlCommander.syncKeep(newKeep)
      ktuCommander.syncKeep(newKeep)
    } else {
      val libIds = ktlRepo.getAllByKeepId(keep.id.get).map(_.libraryId).toSet
      val userIds = ktuRepo.getAllByKeepId(keep.id.get).map(_.userId).toSet
      val similarKeeps = getKeepsByUriAndLibraries(newUri.id.get, libIds)

      val mergeableKeeps = similarKeeps.filter(keep.hasStrictlyLessValuableMetadataThan)
      log.info(s"[URI-MIG] Of the similar keeps ${similarKeeps.map(_.id.get)}, these are mergeable: ${mergeableKeeps.map(_.id.get)}")
      if (mergeableKeeps.nonEmpty) {
        val soonToBeDeadKeep = keepRepo.save(keep.withNormUriId(newUri.id.get).withPrimary(false))
        ktlCommander.syncKeep(soonToBeDeadKeep)
        ktuCommander.syncKeep(soonToBeDeadKeep)

        mergeableKeeps.foreach { k =>
          collectionCommander.copyKeepTags(soonToBeDeadKeep, k) // todo: Handle notes! You can't just combine tags!
        }
        collectionCommander.deactivateKeepTags(soonToBeDeadKeep)
        deactivateKeep(soonToBeDeadKeep)
      } else {
        val soonToBeDeadKeeps = similarKeeps.filter(_.hasStrictlyLessValuableMetadataThan(keep))
        log.info(s"[URI-MIG] Since no keeps are mergeable, we looked and found these other keeps which should die: ${soonToBeDeadKeeps.map(_.id.get)}")
        soonToBeDeadKeeps.foreach { k =>
          collectionCommander.copyKeepTags(k, keep) // todo: Handle notes! You can't just combine tags!
          deactivateKeep(k)
        }

        val newKeep = keepRepo.save(uriHelpers.improveKeepSafely(newUri, keep.withNormUriId(newUri.id.get).withPrimary(true)))
        ktlCommander.syncKeep(newKeep)
        ktuCommander.syncKeep(newKeep)
      }
    }
  }

  def deactivateKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktlCommander.removeKeepFromAllLibraries(keep)
    ktuCommander.removeKeepFromAllUsers(keep)
    collectionCommander.deactivateKeepTags(keep)
    keepRepo.deactivate(keep)
  }

  // TODO(ryan): eventually this should basically JUST call ktlCommander.removeKeep and ktlCommander.internKeep
  def moveKeep(k: Keep, toLibrary: Library, userId: Id[User])(implicit session: RWSession): Either[LibraryError, Keep] = {
    val oldWay = keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    val newWay = ktlRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    if (newWay.map(_.keepId) != oldWay.map(_.id.get)) log.info(s"[KTL-MATCH] moveKeep(uri = ${k.uriId}, toLib = ${toLibrary.id.get}): existingKeepOpt ${newWay.map(_.keepId)} != ${oldWay.map(_.id.get)}")

    val existingKeepOpt = oldWay

    existingKeepOpt match {
      case None =>
        val movedKeep = keepRepo.save(k.withLibrary(toLibrary))
        ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)
        ktlCommander.internKeepInLibrary(k, toLibrary, userId)
        Right(refreshLibrariesHash(movedKeep))
      case Some(existingKeep) if existingKeep.isInactive =>
        combineTags(k.id.get, existingKeep.id.get)

        keepRepo.deactivate(k) // TODO(ryan): don't deactivate keeps here
        ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)

        val movedKeep = keepRepo.save(k.withLibrary(toLibrary).withId(existingKeep.id.get)) // overwrite the old keep
        ktlCommander.internKeepInLibrary(movedKeep, toLibrary, userId)

        Right(refreshLibrariesHash(movedKeep))
      case Some(existingKeep) =>
        if (toLibrary.id.get == k.libraryId.get) {
          Left(LibraryError.AlreadyExistsInDest)
        } else {
          ktlCommander.removeKeepFromLibrary(k.id.get, k.libraryId.get)
          refreshLibrariesHash(k)
          keepRepo.deactivate(k)
          combineTags(k.id.get, existingKeep.id.get)
          Left(LibraryError.AlreadyExistsInDest)
        }
    }
  }
  def copyKeep(k: Keep, toLibrary: Library, userId: Id[User], withSource: Option[KeepSource] = None)(implicit session: RWSession): Either[LibraryError, Keep] = {
    val currentKeepOpt = keepRepo.getPrimaryByUriAndLibrary(k.uriId, toLibrary.id.get)
    val newKeep = k.copy(
      id = None,
      externalId = ExternalId(),
      userId = userId,
      libraryId = Some(toLibrary.id.get),
      visibility = toLibrary.visibility,
      organizationId = toLibrary.organizationId,
      keptAt = currentDateTime,
      source = withSource.getOrElse(k.source),
      originalKeeperId = k.originalKeeperId.orElse(Some(userId))
    )

    currentKeepOpt match {
      case Some(existingKeep) if existingKeep.isActive =>
        combineTags(k.id.get, existingKeep.id.get)
        Left(LibraryError.AlreadyExistsInDest)

      case inactiveKeepOpt =>
        val persistedKeep = persistKeep(newKeep.copy(id = inactiveKeepOpt.flatMap(_.id)), Set(userId), Set(toLibrary.id.get))
        combineTags(k.id.get, persistedKeep.id.get)
        Right(persistedKeep)
    }
  }

  // combine tag info on both keeps & saves difference on the new Keep
  private def combineTags(oldKeepId: Id[Keep], newKeepId: Id[Keep])(implicit s: RWSession) = {
    // todo: Awwww crud, this doesn't edit note
    val oldSet = keepToCollectionRepo.getCollectionsForKeep(oldKeepId).toSet
    val existingSet = keepToCollectionRepo.getCollectionsForKeep(newKeepId).toSet
    val tagsToAdd = oldSet.diff(existingSet)
    tagsToAdd.foreach { tagId =>
      val newKtc = KeepToCollection(keepId = newKeepId, collectionId = tagId)
      val ktcOpt = keepToCollectionRepo.getOpt(newKeepId, tagId)
      if (!ktcOpt.exists(_.isActive)) {
        // either overwrite (if the dead one exists) or create a new one
        keepToCollectionRepo.save(newKtc.copy(id = ktcOpt.map(_.id.get)))
      }
    }
  }

  @StatsdTiming("KeepCommander.getKeepStream")
  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]], sanitizeUrls: Boolean): Future[Seq[KeepInfo]] = {
    val keepsAndTimes = db.readOnlyReplica { implicit session =>
      // TODO(ryan): when the frontend can handle a keep without a library, let them through
      // Grab 2x the required number because we're going to be dropping some
      keepRepo.getRecentKeeps(userId, 2 * limit, beforeExtId, afterExtId).filter { case (k, _) => k.libraryId.isDefined }
    }.distinctBy { case (k, addedAt) => k.uriId }.take(limit)

    val keeps = keepsAndTimes.map(_._1)
    val firstAddedAt = keepsAndTimes.map { case (k, addedAt) => k.id.get -> addedAt }.toMap
    def getKeepTimestamp(keep: Keep) = firstAddedAt(keep.id.get)

    keepDecorator.decorateKeepsIntoKeepInfos(
      Some(userId),
      showPublishedLibraries = false,
      keeps,
      ProcessedImageSize.Large.idealSize,
      sanitizeUrls = sanitizeUrls,
      getTimestamp = getKeepTimestamp
    )
  }

  private val autoFixNoteLimiter = new ReactiveLock()
  def autoFixKeepNoteAndTags(keepId: Id[Keep]): Future[Unit] = {
    autoFixNoteLimiter.withLock {
      db.readWrite(attempts = 3) { implicit session =>
        val keep = keepRepo.getNoCache(keepId)
        val tagsFromHashtags = Hashtags.findAllHashtagNames(keep.note.getOrElse("")).map(Hashtag.apply)
        val tagsFromCollections = collectionRepo.getHashtagsByKeepId(keep.id.get)
        if (tagsFromHashtags.map(_.normalized) != tagsFromCollections.map(_.normalized) && keep.isActive) {
          val newNote = Hashtags.addHashtagsToString(keep.note.getOrElse(""), tagsFromCollections.toSeq)
          if (keep.note.getOrElse("").toLowerCase != newNote.toLowerCase) {
            log.info(s"[autoFixKeepNoteAndTags] (${keep.id.get}) Previous note: '${keep.note.getOrElse("")}', new: '$newNote'")
            Try(updateKeepNote(keep.userId, keep, newNote, freshTag = false)).recover {
              case ex: Throwable =>
                log.warn(s"[autoFixKeepNoteAndTags] (${keep.id.get}) Couldn't update note", ex)
            }
          }
        }
      }
    }
  }
}

sealed trait HelpRankSelector { val name: String }
case object HelpRankRekeeps extends HelpRankSelector { val name = "rekeep" }
case object HelpRankDiscoveries extends HelpRankSelector { val name = "discovery" }
object HelpRankSelector {
  def apply(selector: String) = {
    selector match {
      case HelpRankRekeeps.name => HelpRankRekeeps
      case _ => HelpRankDiscoveries // bad! Need to check all clients to make sure they're sending in the correct string
    }
  }

  def unapply(selector: HelpRankSelector) = selector.name
}
