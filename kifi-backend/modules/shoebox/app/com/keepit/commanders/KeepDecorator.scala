package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
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
import com.keepit.model._
import KeepEvent.AddParticipants
import com.keepit.common.util.{ ImageElement, DescriptionElement, LinkElement, DescriptionElements }
import com.keepit.common.util.Ord.dateTimeOrdering
import com.keepit.discussion.{ CrossServiceKeepActivity, Discussion, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.model.keep2.{ BasicLibraryWithKeptAt, KeepInfo }
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ AugmentableItem, LimitedAugmentationInfo }
import com.keepit.slack.models.{ SlackTeamId, SlackTeamRepo }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.{ ImageUrls, BasicAuthor, BasicUser }
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepDecoratorImpl])
trait KeepDecorator {
  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, maxMessagesShown: Int, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]]
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
  basicOrganizationGen: BasicOrganizationGen,
  searchClient: SearchServiceClient,
  keepSourceCommander: KeepSourceCommander,
  permissionCommander: PermissionCommander,
  eliza: ElizaServiceClient,
  rover: RoverServiceClient,
  airbrake: AirbrakeNotifier,
  slackTeamRepo: SlackTeamRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  implicit val imageConfig: S3ImageConfig,
  implicit val s3: S3ImageStore,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepDecorator with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def decorateKeepsIntoKeepInfos(viewerIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, maxMessagesShown: Int, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]] = {
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
        searchClient.augment(viewerIdOpt, showPublishedLibraries, KeepInfo.maxKeepsShown, KeepInfo.maxKeepersShown, KeepInfo.maxLibrariesShown, 0, items).imap(augmentationInfos => filterLibraries(augmentationInfos))
      }
      val emailParticipantsByKeepFuture = eliza.getEmailParticipantsForKeeps(keepIds)

      val (ktusByKeep, ktlsByKeep) = db.readOnlyMaster { implicit s =>
        (ktuRepo.getAllByKeepIds(keeps.map(_.id.get).toSet), ktlRepo.getAllByKeepIds(keepIds))
      }

      val entitiesFutures = for {
        augmentationInfos <- augmentationFuture
        emailParticipantsByKeep <- emailParticipantsByKeepFuture
      } yield {
        val idToLibrary = {
          val librariesShown = augmentationInfos.flatMap(_.libraries.map(_._1)).toSet ++ ktlsByKeep.values.flatMap(_.map(_.libraryId))
          db.readOnlyMaster { implicit s => libraryRepo.getActiveByIds(librariesShown) } //cached
        }

        val basicOrgByLibId = {
          val orgIdByLibId = idToLibrary.collect { case (libId, lib) if lib.organizationId.isDefined => libId -> lib.organizationId.get }
          val orgIds = orgIdByLibId.values.toSet
          val basicOrgById = db.readOnlyMaster(implicit s => basicOrganizationGen.getBasicOrganizations(orgIds))
          orgIdByLibId.mapValues(basicOrgById(_))
        }

        val idToBasicUser = {
          val keepersShown = augmentationInfos.flatMap(_.keepers.map(_._1)).toSet
          val libraryContributorsShown = augmentationInfos.flatMap(_.libraries.map(_._2)).toSet
          val libraryOwners = idToLibrary.values.map(_.ownerId).toSet
          val keepers = keeps.flatMap(_.userId).toSet // is this needed? need to double check, it may be redundant
          val ktuUsers = ktusByKeep.values.flatten.map(_.userId) // may need to use .take(someLimit) for performance
          val emailParticipantsAddedBy = emailParticipantsByKeep.values.flatMap(_.values.map(_._1))
          db.readOnlyMaster { implicit s => basicUserRepo.loadAll(keepersShown ++ libraryContributorsShown ++ libraryOwners ++ keepers ++ ktuUsers ++ emailParticipantsAddedBy) } //cached
        }
        val idToBasicLibrary = idToLibrary.map {
          case (libId, library) =>
            val orgOpt = basicOrgByLibId.get(libId)
            val user = idToBasicUser(library.ownerId)
            libId -> BasicLibrary(library, user, orgOpt.map(_.handle))
        }
        val libraryCardByLibId = {
          val libraries = keeps.flatMap(_.lowestLibraryId.map(idToLibrary(_)))
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

      val additionalSourcesFuture = augmentationFuture.map { infos =>
        val keepIdsByUriId = (keeps zip infos).map {
          case (keep, info) =>
            keep.uriId -> info.keeps.map(_.id).filter(_ != keep.id.get)
        }.toMap
        getAdditionalSources(viewerIdOpt, keepIdsByUriId)
      }

      val allMyKeeps = viewerIdOpt.map { userId => getPersonalKeeps(userId, keeps.map(_.uriId).toSet) } getOrElse Map.empty[Id[NormalizedURI], Set[PersonalKeep]]

      val librariesWithWriteAccess = viewerIdOpt.map { userId =>
        db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) } //cached
      } getOrElse Set.empty

      val discussionsByKeepFut = eliza.getDiscussionsForKeeps(keepIds, maxMessagesShown).recover {
        case fail =>
          airbrake.notify(s"[KEEP-DECORATOR] Failed to get discussions for keeps $keepIds", fail)
          Map.empty[Id[Keep], Discussion]
      }
      val discussionsWithStrictTimeout = TimeoutFuture(discussionsByKeepFut)(executionContext, 2.seconds).recover {
        case _ =>
          log.warn(s"[KEEP-DECORATOR] Timed out fetching discussions for keeps $keepIds")
          Map.empty[Id[Keep], Discussion]
      }
      val activityByKeepFut = eliza.getCrossServiceKeepActivity(keepIds, maxMessagesShown).recover {
        case fail =>
          airbrake.notify(s"[KEEP-DECORATOR] Failed to get activity for keeps $keepIds", fail)
          Map.empty[Id[Keep], CrossServiceKeepActivity]
      }
      val activityWithStrictTimeout = TimeoutFuture(activityByKeepFut)(executionContext, 2.seconds).recover {
        case _ =>
          log.warn(s"[KEEP-DECORATOR] Timed out fetching activity for keeps $keepIds")
          Map.empty[Id[Keep], CrossServiceKeepActivity]
      }
      val permissionsByKeep = db.readOnlyMaster(implicit s => permissionCommander.getKeepsPermissions(keepIds, viewerIdOpt))

      for {
        augmentationInfos <- augmentationFuture
        pageInfos <- pageInfosFuture
        sourceAttrs <- sourceAttrsFut
        additionalSourcesByUriId <- additionalSourcesFuture
        (idToBasicUser, idToBasicLibrary, idToLibraryCard, idToBasicOrg) <- entitiesFutures
        discussionsByKeep <- discussionsWithStrictTimeout
        activityByKeep <- activityWithStrictTimeout
        emailParticipantsByKeep <- emailParticipantsByKeepFuture
      } yield {
        val keepsInfo = (keeps zip colls, augmentationInfos, pageInfos).zipped.flatMap {
          case ((keep, collsForKeep), augmentationInfoForKeep, pageInfoForKeep) =>
            val keepId = keep.id.get
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

            val keepMembers = {
              val libraries = ktlsByKeep.getOrElse(keepId, Seq.empty).flatMap { ktl =>
                idToLibraryCard.get(ktl.libraryId).map { library =>
                  KeepMember.Library(library, ktl.addedAt, ktl.addedBy.flatMap(idToBasicUser.get))
                }
              }.sortBy(_.addedAt)

              val users = ktusByKeep.getOrElse(keepId, Seq.empty).flatMap { ktu =>
                idToBasicUser.get(ktu.userId).map { user =>
                  KeepMember.User(user, ktu.addedAt, ktu.addedBy.flatMap(idToBasicUser.get))
                }
              }.sortBy(_.addedAt)

              val emails = emailParticipantsByKeep.getOrElse(keepId, Map.empty).map {
                case (emailAddress, (addedBy, addedAt)) =>
                  KeepMember.Email(emailAddress, addedAt, idToBasicUser.get(addedBy))
              }.toSeq.sortBy(_.addedAt)

              KeepMembers(libraries, users, emails)
            }

            (for {
              author <- sourceAttrs.get(keepId).map {
                case (attr, userOpt) => BasicAuthor(attr, userOpt)
              } orElse keep.userId.flatMap(keeper => idToBasicUser.get(keeper).map(BasicAuthor.fromUser))
            } yield {
              val activityLog = generateActivityLog(keep, author, sourceAttrs.get(keepId).map(_._1), activityByKeep.get(keepId),
                ktlsByKeep.getOrElse(keepId, Seq.empty), ktusByKeep.getOrElse(keepId, Seq.empty),
                idToBasicUser, idToBasicLibrary, idToBasicOrg)
              KeepInfo(
                id = Some(keep.externalId),
                pubId = Some(Keep.publicId(keepId)),
                title = keep.title,
                url = if (sanitizeUrls) URISanitizer.sanitize(keep.url) else keep.url,
                path = bestEffortPath,
                isPrivate = !ktlsByKeep.getOrElse(keepId, Seq.empty).exists(_.visibility > LibraryVisibility.SECRET),
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
                sources = additionalSourcesByUriId.get(keep.uriId).collect { case sources if sources.nonEmpty => sources.take(5) },
                collections = Some(collsForKeep.map(_.id.get.id).toSet), // Is not used by any client
                tags = Some(collsForKeep.toSet), // Used by site
                hashtags = Some(collsForKeep.toSet.map { c: BasicCollection => Hashtag(c.name) }), // Used by both mobile clients
                summary = Some(pageInfoForKeep),
                siteName = DomainToNameMapper.getNameFromUrl(keep.url),
                libraryId = keep.lowestLibraryId.map(Library.publicId),
                library = keep.lowestLibraryId.flatMap(idToLibraryCard.get),
                organization = keep.lowestLibraryId.flatMap(idToBasicOrg.get),
                sourceAttribution = sourceAttrs.get(keepId),
                note = keep.note,
                discussion = discussionsByKeep.get(keepId),
                activity = activityLog,
                participants = ktusByKeep.getOrElse(keepId, Seq.empty).flatMap(ktu => idToBasicUser.get(ktu.userId)),
                members = keepMembers,
                permissions = permissionsByKeep.getOrElse(keepId, Set.empty)
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
    val (keepsById, ktlsByKeep) = db.readOnlyReplica { implicit session =>
      val writeableLibs = libraryMembershipRepo.getLibrariesWithWriteAccess(userId)
      val direct = ktuRepo.getByUserIdAndUriIds(userId, uriIds).map(_.keepId)
      val indirectViaLibraries = ktlRepo.getVisibileFirstOrderImplicitKeeps(uriIds, writeableLibs).map(_.keepId)
      val keepIds = direct ++ indirectViaLibraries
      val keepsById = keepRepo.getByIds(keepIds)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepIds)
      (keepsById, ktlsByKeep)
    }
    keepsById.groupBy { case (kId, k) => k.uriId }.map {
      case (uriId, relevantKeeps) =>
        val userKeeps = relevantKeeps.values.map { keep =>
          val mine = keep.userId.safely.contains(userId)
          val removable = true // all keeps here are writeable
          val bestKtl = ktlsByKeep.getOrElse(keep.id.get, Seq.empty).maxByOpt(_.visibility)
          PersonalKeep(
            id = keep.externalId,
            mine = mine,
            removable = removable,
            visibility = bestKtl.map(_.visibility).getOrElse(LibraryVisibility.SECRET),
            libraryId = bestKtl.map(ktl => Library.publicId(ktl.libraryId))
          )
        }.toSet
        uriId -> userKeeps
    }.toMap
  }

  def getAdditionalSources(viewerIdOpt: Option[Id[User]], keepsByUriId: Map[Id[NormalizedURI], Seq[Id[Keep]]]): Map[Id[NormalizedURI], Seq[SourceAttribution]] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamIds = viewerIdOpt match {
        case None => Set.empty[SlackTeamId]
        case Some(userId) => slackTeamRepo.getSlackTeamIds(orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet).values.toSet
      }
      val sourcesByKeepId = keepSourceCommander.getSourceAttributionForKeeps(keepsByUriId.values.flatten.toSet).mapValues(_._1)
      keepsByUriId.mapValues { keepIds =>
        val allSources = keepIds.flatMap(sourcesByKeepId.get)
        val slackSources = allSources.collect { case s: SlackAttribution if slackTeamIds.contains(s.teamId) => s }.distinctBy(s => (s.teamId, s.message.channel.id, s.message.timestamp))
        val twitterSources = allSources.collect { case t: TwitterAttribution => t }.distinctBy(_.tweet.id)
        (slackSources ++ twitterSources).toSeq
      }
    }
  }

  def generateActivityLog(
    keep: Keep, author: BasicAuthor, sourceAttr: Option[SourceAttribution], elizaActivity: Option[CrossServiceKeepActivity],
    ktls: Seq[KeepToLibrary], ktus: Seq[KeepToUser], userById: Map[Id[User], BasicUser],
    libById: Map[Id[Library], BasicLibrary], orgByLibraryId: Map[Id[Library], BasicOrganization]): KeepActivity = {
    import com.keepit.common.util.DescriptionElements._

    val initialKeepEvent = {
      val sortedKtls = ktls.sortBy {
        _.addedAt.getMillis * -1
      }
      val sortedKtus = ktus.sortBy {
        _.addedAt.getMillis * -1
      }

      val authorElement = fromBasicAuthor(author)
      val header = sortedKtls.headOption match {
        case Some(ktl) =>
          val library: DescriptionElement = libById.get(ktl.libraryId).map(fromBasicLibrary).getOrElse("a library")
          val orgOpt = orgByLibraryId.get(ktl.libraryId).map(fromBasicOrg)
          sourceAttr match {
            case Some(SlackAttribution(message, _)) =>
              DescriptionElements(authorElement, "added this into", library, orgOpt.map(org => DescriptionElements("in", org)),
                ImageElement(Some(message.permalink), ImageUrls.SLACK_LOGO), message.channel.name.map(_.value --> LinkElement(message.permalink)))
            case Some(TwitterAttribution(tweet)) =>
              DescriptionElements(authorElement, "kept this into", library, orgOpt.map(org => DescriptionElements("in", org)),
                ImageElement(Some(tweet.permalink), ImageUrls.TWITTER_LOGO), tweet.user.screenName.value --> LinkElement(tweet.permalink))
            case None =>
              DescriptionElements(authorElement, "kept this into", library, orgOpt.map(org => DescriptionElements("in", org)))
          }
        case None =>
          sortedKtus.headOption match {
            case None =>
              airbrake.notify(s"[activityLog] no ktu or ktls on ${keep.id.get}, can't generate initial keep event")
              DescriptionElements(authorElement, "kept this page")
            case Some(firstKtu) =>
              val firstMinute = firstKtu.addedAt.plusMinutes(1)
              val firstSentTo = sortedKtus.takeWhile(_.addedAt.getMillis <= firstMinute.getMillis)
                .collect { case ktu if !keep.userId.contains(ktu.userId) => userById.get(ktu.userId) }.flatten
              DescriptionElements(authorElement, "started a discussion", if (firstSentTo.nonEmpty) DescriptionElements("with", DescriptionElements.unwordsPretty(firstSentTo.map(fromBasicUser))) else "on this page")
          }
      }
      val body = DescriptionElements(keep.note)
      BasicActivityEvent(
        KeepEventKind.Initial,
        image = authorElement.image,
        header = header,
        body = body,
        timestamp = keep.keptAt,
        source = None
      )
    }

    val commentEvents = elizaActivity.map(_.messages.flatMap { message =>
      message.sentBy match {
        case Some(userOrNonUser) =>
          import DescriptionElements._
          val userOpt = userOrNonUser.left.toOption.flatMap(userById.get)
          val msgAuthor: DescriptionElement = userOrNonUser.fold[Option[DescriptionElement]](userId => userOpt.map(fromBasicUser), nonUser => Some(nonUser.id)).getOrElse {
            airbrake.notify(s"[activityLog] could not generate message author name on keep ${keep.id.get}")
            "Someone"
          }
          Some(BasicActivityEvent(
            KeepEventKind.Comment,
            image = userOpt.map(_.picturePath.getUrl).getOrElse("0.jpg"), // todo(cam): figure out a protocol for non-user images
            header = DescriptionElements(msgAuthor, "commented on this page"),
            body = DescriptionElements(message.text),
            timestamp = message.sentAt,
            source = KeepEventSource.fromMessageSource(message.source)
          ))
        case None =>
          message.auxData match {
            case Some(AddParticipants(addedBy, addedUsers, addedNonUsers)) =>
              val basicAddedBy = userById.get(addedBy)
              val addedElement = unwordsPretty(addedUsers.flatMap(userById.get).map(fromBasicUser) ++ addedNonUsers.map(fromNonUser))
              Some(BasicActivityEvent(
                KeepEventKind.AddParticipants,
                image = basicAddedBy.map(_.picturePath.getUrl).getOrElse {
                  airbrake.notify(s"[activityLog] can't find user $addedBy for keep ${keep.id.get}")
                  "0.jpg"
                },
                header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "added", addedElement),
                body = DescriptionElements(),
                timestamp = message.sentAt,
                source = KeepEventSource.fromMessageSource(message.source)
              ))
            case dataOpt =>
              if (dataOpt.isEmpty) airbrake.notify(s"[activityLog] messsage ${message.id} has no .sentBy and no .auxData, can't generate event")
              None
          }
      }
    }).getOrElse(Seq.empty)
    val events = commentEvents :+ initialKeepEvent
    KeepActivity(
      events = events,
      numEvents = events.size, // todo(cam): fetch the eliza total event count
      numComments = elizaActivity.map(_.numComments).getOrElse(0) + keep.note.size)
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
