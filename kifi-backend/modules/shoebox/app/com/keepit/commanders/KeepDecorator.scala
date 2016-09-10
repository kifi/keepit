package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.{ BasicOrganizationGen, BasicULOBatchFetcher, KeepActivityGen }
import com.keepit.common.akka.TimeoutFuture
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.net.URISanitizer
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageConfig, S3ImageStore }
import com.keepit.common.util.Ord.dateTimeOrdering
import com.keepit.discussion.{ CrossServiceDiscussion, Discussion, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.{ SearchFilter, SearchServiceClient }
import com.keepit.search.augmentation.{ AugmentableItem, LimitedAugmentationInfo }
import com.keepit.shoebox.data.keep.{ BasicLibraryWithKeptAt, KeepInfo }
import com.keepit.slack.models.SlackTeamId
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient, SlackInfoCommander }
import com.keepit.social.{ BasicAuthor, BasicUserLikeEntity }
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepDecoratorImpl])
trait KeepDecorator {
  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], hidePublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, maxMessagesShown: Int, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]]
  def filterLibraries(infos: Seq[LimitedAugmentationInfo]): Seq[LimitedAugmentationInfo]
  def getPersonalKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]], useMultilibLogic: Boolean = false): Map[Id[NormalizedURI], Set[PersonalKeep]]
  def getKeepSummaries(keeps: Seq[Keep], idealImageSize: ImageSize): Future[Seq[URISummary]]
}

@Singleton
class KeepDecoratorImpl @Inject() (
  db: Database,
  basicUserRepo: BasicUserRepo,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  keepRepo: KeepRepo,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  kteRepo: KeepToEmailRepo,
  eventRepo: KeepEventRepo,
  keepImageCommander: KeepImageCommander,
  libraryCardCommander: LibraryCardCommander,
  userCommander: UserCommander,
  userExperimentRepo: UserExperimentRepo,
  basicOrganizationGen: BasicOrganizationGen,
  searchClient: SearchServiceClient,
  keepSourceCommander: KeepSourceCommander,
  permissionCommander: PermissionCommander,
  eliza: ElizaServiceClient,
  rover: RoverServiceClient,
  slackInfoCommander: SlackInfoCommander,
  basicULOBatchFetcher: BasicULOBatchFetcher,
  implicit val airbrake: AirbrakeNotifier,
  implicit val imageConfig: S3ImageConfig,
  implicit val s3: S3ImageStore,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepDecorator with Logging {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def decorateKeepsIntoKeepInfos(viewerIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keepsSeq: Seq[Keep], idealImageSize: ImageSize, maxMessagesShown: Int, sanitizeUrls: Boolean, getTimestamp: Keep => DateTime = _.keptAt): Future[Seq[KeepInfo]] = {
    Future.successful(Seq.empty[KeepInfo])
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
      val keepsById = keepRepo.getActiveByIds(keepIds)
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
    }
  }

  def getAdditionalSources(viewerIdOpt: Option[Id[User]], keepsByUriId: Map[Id[NormalizedURI], Seq[Id[Keep]]]): Map[Id[NormalizedURI], Seq[SourceAttribution]] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamIds = viewerIdOpt match {
        case None => Set.empty[SlackTeamId]
        case Some(userId) => slackInfoCommander.getOrganizationSlackTeamsForUser(userId)
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
