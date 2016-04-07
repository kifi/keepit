package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ KeepActivityGen, BasicLibraryGen, BasicOrganizationGen }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.util.{ SetHelpers, RightBias }
import com.keepit.common.core.{ anyExtensionOps, iterableExtensionOps, mapExtensionOps }
import com.keepit.common.util.RightBias._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, AugmentableItem }
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.keep._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.{ BasicNonUser, BasicAuthor }
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepInfoAssemblerImpl])
trait KeepInfoAssembler {
  def assembleKeepInfos(viewer: Option[Id[User]], keepSet: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepInfo]]]
  def assemblePageInfos(viewer: Option[Id[User]], uriSet: Set[Id[NormalizedURI]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[NormalizedURI], NewPageInfo]]
  def assembleKeepViews(viewer: Option[Id[User]], keepSet: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepView]]]
  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity]
}

object KeepInfoAssemblerConfig {
  final case class KeepViewAssemblyOptions(
    idealImageSize: ImageSize,
    numEventsPerKeep: Int,
    showPublishedLibraries: Boolean,
    numContextualKeeps: Int,
    numContextualKeepers: Int,
    numContextualLibraries: Int,
    numContextualTags: Int,
    sanitizeUrls: Boolean)

  val default = KeepViewAssemblyOptions(
    idealImageSize = ProcessedImageSize.Large.idealSize,
    numEventsPerKeep = 5,
    showPublishedLibraries = true,
    numContextualKeeps = 1,
    numContextualKeepers = 1,
    numContextualLibraries = 1,
    numContextualTags = 1,
    sanitizeUrls = true
  )
}

class KeepInfoAssemblerImpl @Inject() (
  db: Database,
  keepRepo: KeepRepo,
  libRepo: LibraryRepo,
  basicUserRepo: BasicUserRepo,
  basicLibGen: BasicLibraryGen,
  basicOrgGen: BasicOrganizationGen,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  kteRepo: KeepToEmailRepo,
  keepSourceCommander: KeepSourceCommander,
  permissionCommander: PermissionCommander,
  keepCommander: KeepCommander,
  keepImageCommander: KeepImageCommander,
  eliza: ElizaServiceClient,
  rover: RoverServiceClient,
  search: SearchServiceClient,
  userExperimentRepo: UserExperimentRepo,
  userCommander: UserCommander, // TODO(ryan): used only to filter out fake users, can replace with user experiment repo?
  private implicit val airbrake: AirbrakeNotifier,
  private implicit val imageConfig: S3ImageConfig,
  private implicit val executionContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepInfoAssembler {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  private final case class KeepsAndConnections(
    keepsById: Map[Id[Keep], Keep],
    ktlsByKeep: Map[Id[Keep], Seq[KeepToLibrary]],
    ktusByKeep: Map[Id[Keep], Seq[KeepToUser]],
    ktesByKeep: Map[Id[Keep], Seq[KeepToEmail]])

  def assembleKeepViews(viewer: Option[Id[User]], keepIds: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepView]]] = {
    keepViewAssemblyHelper(viewer, getKeepsAndConnections(keepIds), config)
  }
  def assembleKeepInfos(viewer: Option[Id[User]], keepIds: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepInfo]]] = {
    keepInfoAssemblyHelper(viewer, getKeepsAndConnections(keepIds), config)
  }
  def assemblePageInfos(viewer: Option[Id[User]], uriIds: Set[Id[NormalizedURI]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[NormalizedURI], NewPageInfo]] = {
    pageInfoAssemblyHelper(viewer, uriIds, config)
  }

  private def getKeepsAndConnections(keepIds: Set[Id[Keep]]): KeepsAndConnections = {
    db.readOnlyMaster { implicit s =>
      val keepsById = keepRepo.getByIds(keepIds)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepIds)
      val ktusByKeep = ktuRepo.getAllByKeepIds(keepIds)
      val ktesByKeep = kteRepo.getAllByKeepIds(keepIds)
      KeepsAndConnections(keepsById, ktlsByKeep, ktusByKeep, ktesByKeep)
    }
  }

  private def keepViewAssemblyHelper(viewer: Option[Id[User]], keepsAndConnections: KeepsAndConnections, config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepView]]] = {
    val keepsById = keepsAndConnections.keepsById
    for {
      keepInfosByKeep <- keepInfoAssemblyHelper(viewer, keepsAndConnections, config)
      pageInfosByUri <- pageInfoAssemblyHelper(viewer, keepsById.values.map(_.uriId).toSet, config)
    } yield {
      keepsById.map {
        case (kId, keep) =>
          val keepInfoOrFail = keepInfosByKeep.getOrElse(kId, RightBias.left(KeepFail.KEEP_NOT_FOUND: KeepFail))
          val pageInfo = pageInfosByUri.get(keep.uriId)
          kId -> keepInfoOrFail.map { keepInfo => NewKeepView(keep = keepInfo, page = pageInfo) }
      }
    }
  }
  private def keepInfoAssemblyHelper(viewer: Option[Id[User]], keepsAndConnections: KeepsAndConnections, config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepInfo]]] = {
    val keepsById = keepsAndConnections.keepsById
    val ktlsByKeep = keepsAndConnections.ktlsByKeep
    val ktusByKeep = keepsAndConnections.ktusByKeep
    val ktesByKeep = keepsAndConnections.ktesByKeep
    val keepSet = keepsById.keySet

    val sourceInfoFut = db.readOnlyReplicaAsync { implicit s =>
      keepSourceCommander.getSourceAttributionForKeeps(keepSet)
    }
    val imageInfoFut = keepImageCommander.getBestImagesForKeepsPatiently(keepSet, ScaleImageRequest(config.idealImageSize)).map { keepImageByKeep =>
      keepImageByKeep.collect {
        case (keepId, Some(keepImage)) => keepId -> NewKeepImageInfo(
          url = keepImage.imagePath.getImageUrl,
          dimensions = keepImage.dimensions
        )
      }
    }
    val permissionsFut = db.readOnlyReplicaAsync { implicit s =>
      permissionCommander.getKeepsPermissions(keepSet, viewer)
    }

    val activityFut = {
      val viewerHasActivityLogExperiment = viewer.exists { viewerId =>
        db.readOnlyMaster { implicit s =>
          userExperimentRepo.hasExperiment(viewerId, UserExperimentType.ACTIVITY_LOG)
        }
      }
      if (!viewerHasActivityLogExperiment) Future.successful(Map.empty[Id[Keep], KeepActivity])
      else FutureHelpers.accumulateOneAtATime(keepSet) { keepId =>
        getActivityForKeep(keepId, fromTime = None, limit = config.numEventsPerKeep)
      }
    }

    for {
      sourceByKeep <- sourceInfoFut
      imagesByKeep <- imageInfoFut
      permissionsByKeep <- permissionsFut
      activityByKeep <- activityFut
    } yield {
      val (usersById, libsById) = db.readOnlyMaster { implicit s =>
        val userSet = ktusByKeep.values.flatMap(_.map(_.userId)).toSet ++ keepsById.values.flatMap(_.userId).toSet
        val libSet = ktlsByKeep.values.flatMap(_.map(_.libraryId)).toSet
        (basicUserRepo.loadAllActive(userSet), basicLibGen.getBasicLibraries(libSet))
      }
      keepSet.toSeq.augmentWith { keepId =>
        for {
          keep <- keepsById.get(keepId).withLeft(KeepFail.KEEP_NOT_FOUND: KeepFail)
          permissions <- RightBias.right(permissionsByKeep.getOrElse(keepId, Set.empty)).filter(_.contains(KeepPermission.VIEW_KEEP), KeepFail.INSUFFICIENT_PERMISSIONS: KeepFail)
          author <- sourceByKeep.get(keepId).map {
            case (attr, userOpt) => BasicAuthor(attr, userOpt)
          }.orElse(keep.userId.flatMap(keeper => usersById.get(keeper).map(BasicAuthor.fromUser))).withLeft(KeepFail.INVALID_ID: KeepFail)
        } yield {
          val viewerInfo = NewKeepViewerInfo(
            permissions = permissions
          )
          val recipients = KeepRecipientsInfo(
            users = ktusByKeep.getOrElse(keepId, Seq.empty).map(_.userId).sorted.flatMap(usersById.get),
            emails = ktesByKeep.getOrElse(keepId, Seq.empty).map(_.emailAddress).sorted(EmailAddress.caseInsensitiveOrdering).map(BasicContact(_)),
            libraries = ktlsByKeep.getOrElse(keepId, Seq.empty).map(_.libraryId).sorted.flatMap(libsById.get)
          )
          val keepInfo = NewKeepInfo(
            id = Keep.publicId(keepId),
            path = keep.path,
            url = keep.url,
            title = keep.title,
            image = imagesByKeep.get(keepId),
            author = author,
            keptAt = keep.keptAt,
            source = sourceByKeep.get(keepId).map(_._1),
            recipients = recipients,
            activity = activityByKeep.get(keepId),
            viewer = viewerInfo
          )

          keepInfo
        }
      }
    }.toMap tap { result =>
      val failures = result.collect { case (kId, LeftSide(fail)) => kId -> fail }
      if (failures.nonEmpty) slackLog.error(s"When generating keep infos for $viewer, ran into errors: " + failures)
    }
  }

  private def pageInfoAssemblyHelper(viewer: Option[Id[User]], uriIds: Set[Id[NormalizedURI]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[NormalizedURI], NewPageInfo]] = {
    val sortedUris = uriIds.toSeq.sorted
    val (augmentationFut, summaryFut) = {
      val augmentation = search.augment(
        viewer,
        config.showPublishedLibraries,
        config.numContextualKeeps,
        config.numContextualKeepers,
        config.numContextualLibraries,
        config.numContextualTags,
        sortedUris.map { uriId => AugmentableItem(uriId) }
      ).map { contexts =>
          (sortedUris zip sanitizeContexts(contexts)).toMap
        }

      val summary = rover.getUriSummaryByUris(uriIds)
      (augmentation, summary)
    }

    for {
      augmentationByUri <- augmentationFut
      summaryByUri <- summaryFut
    } yield {
      val (usersById, libsById) = db.readOnlyMaster { implicit s =>
        val userSet = augmentationByUri.values.flatMap(_.keepers.map(_._1)).toSet
        val libSet = augmentationByUri.values.flatMap(_.libraries.map(_._1)).toSet
        (basicUserRepo.loadAllActive(userSet), basicLibGen.getBasicLibraries(libSet))
      }
      sortedUris.flatAugmentWith { uriId =>
        val context = augmentationByUri.get(uriId).map(augmentation => NewPageContext(
          numVisibleKeeps = augmentation.keeps.length + augmentation.keepsOmitted,
          numTotalKeeps = augmentation.keepsTotal,
          keepers = augmentation.keepers.flatMap { case (userId, time) => usersById.get(userId) },
          numVisibleKeepers = augmentation.keepers.length + augmentation.keepersOmitted,
          numTotalKeepers = augmentation.keepersTotal,
          libraries = augmentation.libraries.flatMap { case (libId, time, x) => libsById.get(libId) },
          numVisibleLibraries = augmentation.libraries.length + augmentation.librariesOmitted,
          numTotalLibraries = augmentation.librariesTotal,
          tags = augmentation.tags,
          numVisibleTags = augmentation.tags.length + augmentation.tagsOmitted
        ))

        val content = summaryByUri.get(uriId).map(summary => NewPageContent(
          summary = NewPageSummary(
            authors = summary.article.authors,
            siteName = summary.article.title,
            publishedAt = summary.article.publishedAt,
            description = summary.article.description,
            wordCount = summary.article.wordCount
          ),
          history = Seq.empty
        ))
        Some(NewPageInfo(content = content, context = context)).filter(_.nonEmpty)
      }.toMap
    }
  }

  def getActivityForKeep(keepId: Id[Keep], fromTime: Option[DateTime], limit: Int): Future[KeepActivity] = {
    val shoeboxFut = db.readOnlyMasterAsync { implicit s =>
      val keep = keepRepo.get(keepId)
      val sourceAttr = keepSourceCommander.getSourceAttributionForKeep(keepId)
      val ktus = ktuRepo.getAllByKeepId(keepId)
      val ktls = ktlRepo.getAllByKeepId(keepId)
      (keep, sourceAttr, ktus, ktls)
    }
    val elizaFut = eliza.getCrossServiceKeepActivity(Set(keepId), fromTime, limit).map(_.get(keepId))

    val basicModelFut = shoeboxFut.map {
      case (keep, sourceAttr, ktus, ktls) =>
        db.readOnlyMaster { implicit s =>
          val libById = libRepo.getActiveByIds(ktls.map(_.libraryId).toSet)
          val basicOrgById = basicOrgGen.getBasicOrganizations(libById.values.flatMap(_.organizationId).toSet)
          val basicOrgByLibId = libById.flatMapValues { library =>
            library.organizationId.flatMap(basicOrgById.get)
          }
          val basicUserById = {
            val ktuUsers = ktus.map(_.userId)
            val libOwners = libById.map { case (libId, library) => library.ownerId }
            basicUserRepo.loadAllActive((ktuUsers ++ libOwners).toSet)
          }
          val basicLibById = libById.map {
            case (libId, library) =>
              libId -> BasicLibrary(library, basicUserById(library.ownerId), basicOrgByLibId.get(libId).map(_.handle))
          }
          (basicUserById, basicLibById, basicOrgByLibId)
        }
    }

    for {
      (keep, sourceAttrOpt, ktus, ktls) <- shoeboxFut
      (elizaActivityOpt) <- elizaFut
      (userById, libById, orgByLibId) <- basicModelFut
    } yield {
      KeepActivityGen.generateKeepActivity(keep, sourceAttrOpt, elizaActivityOpt, ktls, ktus, userById, libById, orgByLibId, limit)
    }
  }

  private def sanitizeContexts(infos: Seq[LimitedAugmentationInfo]): Seq[LimitedAugmentationInfo] = {
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
}
