package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.CollectionHelpers
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching.Implicits._
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.performance._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.common.util.{ Ord, RightBias }
import com.keepit.common.util.RightBias.FromOption
import com.keepit.discussion.{ Message, MessageSource }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.rover.RoverServiceClient
import com.keepit.search.{ SearchFilter, SearchServiceClient }
import com.keepit.search.augmentation.{ AugmentableItem, ItemAugmentationRequest }
import com.keepit.shoebox.data.keep.{ KeepInfo, PartialKeepInfo }
import com.keepit.slack.LibraryToSlackChannelPusher
import com.keepit.social.Author
import com.keepit.typeahead.{ HashtagHit, HashtagTypeahead, TypeaheadHit }
import org.joda.time.DateTime
import play.api.http.Status.{ FORBIDDEN, NOT_FOUND }

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

/**
 * EVERY method in KeepCommander opens a db session. Do not call it from within a session.
 * If you just need to perform some routine action on a keep without doing any permission
 * checking, you may want KeepMutator
 */
@ImplementedBy(classOf[KeepCommanderImpl])
trait KeepCommander {
  def updateKeepTitle(keepId: Id[Keep], userId: Id[User], title: String, source: Option[KeepEventSource]): RightBias[KeepFail, Keep]
  def updateKeepNote(keepId: Id[Keep], userId: Id[User], newNote: String): RightBias[KeepFail, Keep]

  // Getting
  def getKeepsCountFuture: Future[Int]
  def getKeep(libraryId: Id[Library], keepExtId: ExternalId[Keep], userId: Id[User]): Either[(Int, String), Keep]
  def getKeepInfo(internalOrExternalId: Either[Id[Keep], ExternalId[Keep]], userIdOpt: Option[Id[User]], maxMessagesShown: Int, authTokenOpt: Option[String]): Future[KeepInfo]
  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]], maxMessagesShown: Int, sanitizeUrls: Boolean, filterOpt: Option[FeedFilter] = None): Future[Seq[KeepInfo]]
  def getRelevantKeepsByUserAndUri(userId: Id[User], nUriId: Id[NormalizedURI], beforeDate: Option[DateTime], limit: Int): Seq[Keep]
  def getPersonalKeepsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess] = None): Map[Id[NormalizedURI], Set[Keep]]

  // Creating
  def internKeep(internReq: KeepInternRequest)(implicit context: HeimdalContext): Future[(Keep, Boolean, Option[Message])]
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], source: KeepSource)(implicit context: HeimdalContext): (Keep, Boolean)
  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource)(implicit context: HeimdalContext): (Seq[PartialKeepInfo], Seq[String])

  // Tagging
  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]]
  def suggestTags(userId: Id[User], keepIdOpt: Option[Id[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]]
  def suggestRecipients(userId: Id[User], keepIdOpt: Option[Id[Keep]], query: Option[String], offset: Int, limit: Int, requestedSet: Set[TypeaheadRequest]): Future[Seq[TypeaheadSearchResult]]

  // Destroying
  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, PartialKeepInfo]
  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[PartialKeepInfo], Seq[ExternalId[Keep]])]

  // On the way out, hopefully.
  def allKeeps(before: Option[ExternalId[Keep]], after: Option[ExternalId[Keep]], collectionId: Option[String], helprankOpt: Option[String], count: Int, userId: Id[User]): Future[Seq[KeepInfo]]
}

@Singleton
class KeepCommanderImpl @Inject() (
    db: Database,
    keepInterner: KeepInterner,
    searchClient: SearchServiceClient,
    globalKeepCountCache: GlobalKeepCountCache,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    kteRepo: KeepToEmailRepo,
    ktlCommander: KeepToLibraryCommander,
    ktuCommander: KeepToUserCommander,
    kteCommander: KeepToEmailCommander,
    keepMutator: KeepMutator,
    keepSourceCommander: KeepSourceCommander,
    eventCommander: KeepEventCommander,
    keepSourceRepo: KeepSourceAttributionRepo,
    tagCommander: TagCommander,
    libraryAnalytics: LibraryAnalytics,
    heimdalClient: HeimdalServiceClient,
    eliza: ElizaServiceClient,
    implicit val airbrake: AirbrakeNotifier,
    normalizedURIInterner: NormalizedURIInterner,
    clock: Clock,
    libraryRepo: LibraryRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    basicOrganizationGen: BasicOrganizationGen,
    libraryMembershipRepo: LibraryMembershipRepo,
    hashtagTypeahead: HashtagTypeahead,
    typeaheadCommander: TypeaheadCommander,
    keepDecorator: KeepDecorator,
    twitterPublishingCommander: TwitterPublishingCommander,
    permissionCommander: PermissionCommander,
    uriHelpers: UriIntegrityHelpers,
    userExperimentRepo: UserExperimentRepo,
    slackPusher: LibraryToSlackChannelPusher,
    roverClient: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends KeepCommander with Logging {

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
    Left(NOT_FOUND, "keep_not_found")
  }

  def getKeepInfo(internalOrExternalId: Either[Id[Keep], ExternalId[Keep]], userIdOpt: Option[Id[User]], maxMessagesShown: Int, authTokenOpt: Option[String]): Future[KeepInfo] = {
    Future.failed(new Exception("Service closed."))
  }

  def getRelevantKeepsByUserAndUri(userId: Id[User], nUriId: Id[NormalizedURI], beforeDate: Option[DateTime], limit: Int): Seq[Keep] = {
    Seq.empty
  }

  def getPersonalKeepsOnUris(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeAccess: Option[LibraryAccess] = None): Map[Id[NormalizedURI], Set[Keep]] = {
    Map.empty
  }

  // Please do not add to this. It mixes concerns and data sources.
  def allKeeps(
    before: Option[ExternalId[Keep]],
    after: Option[ExternalId[Keep]],
    collectionId: Option[String],
    helprankOpt: Option[String],
    count: Int,
    userId: Id[User]): Future[Seq[KeepInfo]] = {

    def getKeepsFromCollection(userId: Id[User], collectionId: String, beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
      db.readOnlyReplica { implicit session =>
        // todo: This screws up pagination. Crap.
        val tagKeeps = {
          val ids = tagCommander.getKeepsByTagAndUser(Hashtag(collectionId), userId)
          keepRepo.getActiveByIds(ids.toSet).values.toSeq.sortBy(_.lastActivityAt)
        }

        tagKeeps
      }
    }

    def getKeeps(userId: Id[User], beforeOpt: Option[ExternalId[Keep]], afterOpt: Option[ExternalId[Keep]], count: Int): Seq[Keep] = {
      db.readOnlyReplica { implicit session =>
        keepRepo.getByUser(userId, beforeOpt, afterOpt, count)
      }
    }

    // The Option[Int]s are help rank counts. Only included when looking at help rank info currently.
    val keepsF: Future[Seq[(Keep, Option[Int], Option[Int])]] = (collectionId, helprankOpt) match {
      case (Some(c), _) => // collectionId is set
        val keeps = getKeepsFromCollection(userId, c, before, after, count)
        Future.successful(keeps.map((_, None, None)))
      case _ => // neither is set, deliver normal paginated keeps list
        val keeps = getKeeps(userId, before, after, count)
        Future.successful(keeps.map((_, None, None)))
    }

    keepsF.flatMap {
      case keepsWithHelpRankCounts =>
        val (keeps, clickCounts, rkCounts) = keepsWithHelpRankCounts.unzip3
        keepDecorator.decorateKeepsIntoKeepInfos(Some(userId), hidePublishedLibraries = true, keeps, ProcessedImageSize.Large.idealSize, maxMessagesShown = 8, sanitizeUrls = false)
    }
  }

  def internKeep(internReq: KeepInternRequest)(implicit context: HeimdalContext): Future[(Keep, Boolean, Option[Message])] = {
    val permissionsByLib = db.readOnlyMaster { implicit s =>
      permissionCommander.getLibrariesPermissions(internReq.recipients.libraries, Author.kifiUserId(internReq.author))
    }
    val fails: Seq[KeepFail] = Seq(
      !internReq.recipients.libraries.forall(libId => permissionsByLib.getOrElse(libId, Set.empty).contains(LibraryPermission.ADD_KEEPS)) -> KeepFail.INSUFFICIENT_PERMISSIONS
    ).collect { case (true, fail) => fail }

    for {
      _ <- fails.headOption.fold(Future.successful(()))(fail => Future.failed(fail))
      (keep, isNew) <- Future.fromTry(db.readWrite { implicit s => keepInterner.internKeepByRequest(internReq) })
      msgOpt <- Author.kifiUserId(internReq.author).fold(Future.successful(Option.empty[Message])) { user =>
        internReq.note.fold(Future.successful(Option.empty[Message])) { note =>
          implicit val time = CrossServiceTime(Ord.max(clock.now, keep.createdAt))
          eliza.sendMessageOnKeep(user, note, keep.id.get, source = MessageSource.fromKeepSource(internReq.source)).imap(Some(_))
        }
      }
    } yield (keep, isNew, msgOpt)
  }
  // TODO: if keep is already in library, return it and indicate whether userId is the user who originally kept it
  def keepOne(rawBookmark: RawBookmarkRepresentation, userId: Id[User], libraryId: Id[Library], source: KeepSource)(implicit context: HeimdalContext): (Keep, Boolean) = {
    log.info(s"[keep] $rawBookmark")
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val (keep, isNewKeep) = keepInterner.internRawBookmark(rawBookmark, userId, library, source).get
    searchClient.updateKeepIndex()
    (keep, isNewKeep)
  }

  def keepMultiple(rawBookmarks: Seq[RawBookmarkRepresentation], libraryId: Id[Library], userId: Id[User], source: KeepSource)(implicit context: HeimdalContext): (Seq[PartialKeepInfo], Seq[String]) = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(libraryId)
    }
    val internResponse = keepInterner.internRawBookmarksWithStatus(rawBookmarks, Some(userId), Some(library), usersAdded = Set.empty, source)

    val keeps = internResponse.successes
    log.info(s"[keepMulti] keeps(len=${keeps.length}):${keeps.mkString(",")}")

    (keeps.map(PartialKeepInfo.fromKeep), internResponse.failures.map(_.url))
  }

  def unkeepOneFromLibrary(keepId: ExternalId[Keep], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, PartialKeepInfo] = {
    unkeepManyFromLibrary(Seq(keepId), libId, userId) match {
      case Left(why) => Left(why)
      case Right((Seq(), _)) => Left("keep_not_found")
      case Right((Seq(info), _)) => Right(info)
    }
  }

  def unkeepManyFromLibrary(keepIds: Seq[ExternalId[Keep]], libId: Id[Library], userId: Id[User])(implicit context: HeimdalContext): Either[String, (Seq[PartialKeepInfo], Seq[ExternalId[Keep]])] = {
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

          keeps.foreach { k =>
            // TODO[keepscussions]: stop deactivating keeps and instead just detach them from libraries
            // just uncomment the line below this and rework some of this
            // ktlCommander.removeKeepFromLibrary(k.id.get, libId)
            keepMutator.deactivateKeep(k)
          }
          finalizeUnkeeping(keeps, userId)

          // Inactivate tags, update tag
          val phantomActiveKeeps = keeps.map(_.copy(state = KeepStates.ACTIVE))
          tagCommander.removeAllTagsFromKeeps(phantomActiveKeeps.flatMap(_.id))

          (keeps, invalidKeepIds)
        }

        Right((keeps map PartialKeepInfo.fromKeep, invalidKeepIds))
      case _ =>
        Left("permission_denied")
    }
  }

  private def finalizeUnkeeping(keeps: Seq[Keep], userId: Id[User])(implicit session: RWSession, context: HeimdalContext): Unit = {
    // TODO: broadcast over any open user channels
    val libIds: Set[Id[Library]] = keeps.flatMap(_.recipients.libraries).toSet
    libIds.foreach { libId =>
      val library = libraryRepo.get(libId)
      libraryRepo.save(library.copy(keepCount = ktlRepo.getCountByLibraryId(libId)))
      libraryAnalytics.unkeptPages(userId, keeps, library, context)
    }
    session.onTransactionSuccess {
      searchClient.updateKeepIndex()
    }
  }

  def updateKeepTitle(keepId: Id[Keep], userId: Id[User], title: String, source: Option[KeepEventSource]): RightBias[KeepFail, Keep] = {
    val result = db.readWrite { implicit s =>
      def canEdit(keepId: Id[Keep]) = permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.EDIT_KEEP)
      for {
        oldKeep <- keepRepo.getActive(keepId).withLeft(KeepFail.KEEP_NOT_FOUND)
        _ <- RightBias.unit.filter(_ => canEdit(oldKeep.id.get), KeepFail.INSUFFICIENT_PERMISSIONS)
      } yield {
        (oldKeep, keepMutator.updateKeepTitle(oldKeep, title.trim))
      }
    }
    result.foreach {
      case (oldKeep, newKeep) =>
        db.readWrite { implicit s =>
          keepMutator.unsafeModifyKeepRecipients(keepId, KeepRecipientsDiff.addUser(userId), Some(userId))
          eventCommander.persistKeepEventAndUpdateEliza(keepId, KeepEventData.EditTitle(userId, oldKeep.title, newKeep.title), source, eventTime = None)
        }
        slackPusher.schedule(newKeep.recipients.libraries)
    }
    result.map(_._2)
  }

  def updateKeepNote(keepId: Id[Keep], userId: Id[User], newNote: String): RightBias[KeepFail, Keep] = {
    val result = db.readWrite { implicit s =>
      for {
        keep <- keepRepo.getActive(keepId).withLeft(KeepFail.KEEP_NOT_FOUND)
        _ <- RightBias.unit.filter(_ => permissionCommander.getKeepPermissions(keepId, Some(userId)).contains(KeepPermission.EDIT_KEEP), KeepFail.INSUFFICIENT_PERMISSIONS: KeepFail)
      } yield keepMutator.updateKeepNote(userId, keep, newNote)
    }
    result.foreach { newKeep =>
      db.readWrite { implicit s =>
        keepMutator.unsafeModifyKeepRecipients(keepId, KeepRecipientsDiff.addUser(userId), userAttribution = None)
      }
      slackPusher.schedule(newKeep.recipients.libraries)
    }
    result
  }

  def searchTags(userId: Id[User], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    implicit val hitOrdering = TypeaheadHit.defaultOrdering[(Hashtag, Int)]
    hashtagTypeahead.topN(userId, query, limit).map(_.map(_.info)).map(HashtagHit.highlight(query, _))
  }

  private def searchTagsForKeep(userId: Id[User], keepIdOpt: Option[Id[Keep]], query: String, limit: Option[Int]): Future[Seq[HashtagHit]] = {
    val futureHits = searchTags(userId, query, None)
    val existingTags = keepIdOpt.map { keepId =>
      db.readOnlyMaster { implicit session =>
        val keep = keepRepo.get(keepId)
        tagCommander.getTagsForKeep(keep.id.get).toSet
      }
    }.getOrElse(Set.empty)
    futureHits.imap { hits =>
      val validHits = hits.filterNot(hit => existingTags.contains(hit.tag))
      limit.map(validHits.take) getOrElse validHits
    }
  }

  private def suggestTagsForKeep(userId: Id[User], keepIdOpt: Option[Id[Keep]], limit: Option[Int]): Future[Seq[Hashtag]] = {
    keepIdOpt match {
      case None =>
        Future.successful(tagCommander.tagsForUser(userId, 0, limit.getOrElse(10), TagSorting.LastKept).map(t => Hashtag(t.name)))
      case Some(keepId) =>
        val keep = db.readOnlyMaster { implicit session => keepRepo.get(keepId) }
        val item = AugmentableItem(keep.uriId, Some(keep.id.get))
        val futureAugmentationResponse = searchClient.augmentation(ItemAugmentationRequest.uniform(userId, SearchFilter.default, item))
        val existingNormalizedTags = db.readOnlyMaster { implicit session => tagCommander.getTagsForKeep(keep.id.get).map(_.normalized) }
        futureAugmentationResponse.map { response =>
          val suggestedTags = {
            val restrictedKeeps = response.infos(item).keeps.toSet
            val safeTags = restrictedKeeps.flatMap {
              case myKeep if myKeep.owner.contains(userId) => myKeep.tags
              case anotherKeep => anotherKeep.tags.filterNot(_.isSensitive)
            }
            val validTags = safeTags.filterNot(tag => existingNormalizedTags.contains(tag.normalized))
            CollectionHelpers.dedupBy(validTags.toSeq.sortBy(-response.scores.byTag(_)))(_.normalized)
          }
          limit.map(suggestedTags.take) getOrElse suggestedTags
        }
    }
  }

  def suggestTags(userId: Id[User], keepIdOpt: Option[Id[Keep]], query: Option[String], limit: Int): Future[Seq[(Hashtag, Seq[(Int, Int)])]] = {
    query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => searchTagsForKeep(userId, keepIdOpt, validQuery, Some(limit)).map(_.map(hit => (hit.tag, hit.matches)))
      case None => suggestTagsForKeep(userId, keepIdOpt, Some(limit)).map(_.map((_, Seq.empty[(Int, Int)])))
    }
  }

  def suggestRecipients(userId: Id[User], keepIdOpt: Option[Id[Keep]], query: Option[String], offset: Int, limit: Int, requestedSet: Set[TypeaheadRequest]): Future[Seq[TypeaheadSearchResult]] = {
    keepIdOpt.map { keepId => suggestNewRecipientsForKeep(userId, keepId, query getOrElse "", offset, limit, requestedSet)
    }.getOrElse(typeaheadCommander.searchAndSuggestKeepRecipients(userId, query getOrElse "", Some(limit), Some(offset), requestedSet))
  }

  private def suggestNewRecipientsForKeep(userId: Id[User], keepId: Id[Keep], query: String, offset: Int, limit: Int, requestedSet: Set[TypeaheadRequest]): Future[Seq[TypeaheadSearchResult]] = {
    val (usersToExclude: Set[ExternalId[User]], emailsToExclude: Set[EmailAddress], librariesToExclude: Set[PublicId[Library]]) = db.readOnlyReplica { implicit s =>
      (userRepo.getUsers(ktuRepo.getAllByKeepId(keepId).map(_.userId)).values.map(_.externalId).toSet,
        kteRepo.getAllByKeepId(keepId).map(_.emailAddress).toSet,
        ktlRepo.getAllByKeepId(keepId).map(ktl => Library.publicId(ktl.libraryId)).toSet)
    }
    val excludeSize = usersToExclude.size + emailsToExclude.size + librariesToExclude.size

    // we can't accurately know how much to drop if we're going to filter keep recipients after so,
    // request the true sequence up to this page, filter, then slice using offset, offset+limit

    typeaheadCommander.searchAndSuggestKeepRecipients(userId, query, limitOpt = Some(offset + limit + excludeSize), dropOpt = None, requested = requestedSet).imap { result =>
      result.filterNot {
        case u: UserContactResult => usersToExclude.contains(u.id)
        case e: EmailContactResult => emailsToExclude.contains(e.email)
        case l: LibraryResult => librariesToExclude.contains(l.id)
      }.drop(offset).take(limit)
    }
  }

  def numKeeps(userId: Id[User]): Int = db.readOnlyReplica { implicit s => keepRepo.getCountByUser(userId) }

  @StatsdTiming("KeepCommander.getKeepStream")
  def getKeepStream(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]], afterExtId: Option[ExternalId[Keep]], maxMessagesShown: Int, sanitizeUrls: Boolean, filterOpt: Option[FeedFilter]): Future[Seq[KeepInfo]] = {
    val keepsAndTimesFut = filterOpt match {
      case Some(filter: ElizaFeedFilter) =>
        val beforeId = beforeExtId.flatMap(extId => db.readOnlyReplica(implicit s => keepRepo.get(extId).id))
        eliza.getElizaKeepStream(userId, limit, beforeId, filter).map { lastActivityByKeepId =>
          val keepsByIds = db.readOnlyReplica(implicit s => keepRepo.getActiveByIds(lastActivityByKeepId.keySet))
          keepsByIds.map { case (keepId, keep) => (keep, lastActivityByKeepId(keepId)) }.toList.sortBy(-_._2.getMillis)
        }
      case shoeboxFilterOpt: Option[ShoeboxFeedFilter @unchecked] =>
        Future.successful {
          db.readOnlyReplica { implicit session =>
            // Grab 2x the required number because we're going to be dropping some
            keepRepo.getRecentKeepsByActivity(userId, 2 * limit, beforeExtId, afterExtId, shoeboxFilterOpt)
          }.distinctBy { case (k, addedAt) => k.uriId }.take(limit)
        }
    }

    keepsAndTimesFut.flatMap { keepsAndTimes =>

      val keeps = keepsAndTimes.map(_._1)
      val firstAddedAt = keepsAndTimes.map { case (k, addedAt) => k.id.get -> addedAt }.toMap
      def getKeepTimestamp(keep: Keep) = firstAddedAt(keep.id.get)

      keepDecorator.decorateKeepsIntoKeepInfos(
        Some(userId),
        hidePublishedLibraries = true,
        keeps,
        ProcessedImageSize.Large.idealSize,
        sanitizeUrls = sanitizeUrls,
        maxMessagesShown = maxMessagesShown,
        getTimestamp = getKeepTimestamp
      )
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
