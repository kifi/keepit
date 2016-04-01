package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ BasicLibraryGen, BasicOrganizationGen }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.util.{ SetHelpers, RightBias }
import com.keepit.common.util.RightBias._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, AugmentableItem }
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.keep._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.BasicAuthor

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepInfoAssemblerImpl])
trait KeepInfoAssembler {
  def assembleKeepViews(viewer: Option[Id[User]], keepSet: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], NewKeepView]]
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
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  keepSourceCommander: KeepSourceCommander,
  permissionCommander: PermissionCommander,
  keepCommander: KeepCommander,
  keepImageCommander: KeepImageCommander,
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

  private final case class KeepsAndConnections(keepsById: Map[Id[Keep], Keep], ktlsByKeep: Map[Id[Keep], Seq[KeepToLibrary]], ktusByKeep: Map[Id[Keep], Seq[KeepToUser]])

  def assembleKeepViews(viewer: Option[Id[User]], keepIds: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], NewKeepView]] = {
    viewAssemblyHelper(viewer, getKeepsAndConnections(keepIds), config)
  }

  private def getKeepsAndConnections(keepIds: Set[Id[Keep]]): KeepsAndConnections = {
    db.readOnlyMaster { implicit s =>
      val keepsById = keepRepo.getByIds(keepIds)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepIds)
      val ktusByKeep = ktuRepo.getAllByKeepIds(keepIds)
      KeepsAndConnections(keepsById, ktlsByKeep, ktusByKeep)
    }
  }
  private def viewAssemblyHelper(viewer: Option[Id[User]], keepsAndConnections: KeepsAndConnections, config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], NewKeepView]] = {
    val keepsById = keepsAndConnections.keepsById
    val ktlsByKeep = keepsAndConnections.ktlsByKeep
    val ktusByKeep = keepsAndConnections.ktusByKeep
    val keepSet = keepsById.keySet

    val sourceInfoFut = db.readOnlyReplicaAsync { implicit s =>
      keepSourceCommander.getSourceAttributionForKeeps(keepSet)
    }
    val libInfoFut = db.readOnlyReplicaAsync { implicit s =>
      val basicLibById = {
        val libSet = ktlsByKeep.values.flatMap(_.map(_.libraryId)).toSet
        basicLibGen.getBasicLibraries(libSet)
      }
      basicLibById
    }
    val imageInfoFut = keepImageCommander.getBestImagesForKeepsPatiently(keepSet, ScaleImageRequest(config.idealImageSize)).map { keepImageByKeep =>
      keepImageByKeep.collect {
        case (keepId, Some(keepImage)) => keepId -> NewKeepImageInfo(
          path = keepImage.imagePath,
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
        keepCommander.getActivityForKeep(keepId, eventsBefore = None, maxEvents = config.numEventsPerKeep)
      }
    }

    val (augmentationFut, summaryFut) = {
      val uriIds = keepsById.values.map(_.uriId).toSeq.sorted.distinct

      val augmentation = search.augment(
        viewer,
        config.showPublishedLibraries,
        config.numContextualKeeps,
        config.numContextualKeepers,
        config.numContextualLibraries,
        config.numContextualTags,
        uriIds.map { uriId => AugmentableItem(uriId) }
      ).map { contexts =>
          (uriIds zip sanitizeContexts(contexts)).toMap
        }

      val summary = rover.getUriSummaryByUris(uriIds.toSet)
      (augmentation, summary)
    }

    for {
      sourceByKeep <- sourceInfoFut
      libsById <- libInfoFut
      imagesByKeep <- imageInfoFut
      permissionsByKeep <- permissionsFut
      augmentationByUri <- augmentationFut
      summaryByUri <- summaryFut
      activityByKeep <- activityFut
    } yield {
      val usersById = db.readOnlyMaster { implicit s =>
        val userSet = SetHelpers.unions(Seq(
          ktusByKeep.values.flatMap(_.map(_.userId)).toSet,
          augmentationByUri.values.flatMap(_.keepers.map(_._1)).toSet
        ))
        basicUserRepo.loadAllActive(userSet)
      }
      keepSet.flatMap { keepId =>
        val result = for {
          keep <- keepsById.get(keepId).withLeft(s"keep $keepId does not exist")
          _ <- RightBias.unit.filter(_ => permissionsByKeep.get(keepId).exists(_.contains(KeepPermission.VIEW_KEEP)), s"keep $keepId is not visible to $viewer")
          author <- sourceByKeep.get(keepId).map {
            case (attr, userOpt) => BasicAuthor(attr, userOpt)
          }.orElse(keep.userId.flatMap(keeper => usersById.get(keeper).map(BasicAuthor.fromUser))).withLeft(s"no author for keep $keepId")
        } yield {
          val viewerInfo = NewKeepViewerInfo(
            permissions = permissionsByKeep.getOrElse(keepId, Set.empty)
          )
          val recipients = KeepRecipientsInfo(
            users = ktusByKeep.getOrElse(keepId, Seq.empty).map(_.userId).sorted.flatMap(usersById.get),
            emails = Seq.empty, // TODO(ryan): fill this in
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

          val context = augmentationByUri.get(keep.uriId).map(augmentation => NewPageContext(
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

          val content = summaryByUri.get(keep.uriId).map(summary => NewPageContent(
            summary = NewPageSummary(
              authors = summary.article.authors,
              siteName = summary.article.title,
              publishedAt = summary.article.publishedAt,
              description = summary.article.description,
              wordCount = summary.article.wordCount
            ),
            history = Seq.empty
          ))

          val pageInfo = Some(NewPageInfo(content = content, context = context)).filter(_.nonEmpty)

          NewKeepView(keep = keepInfo, page = pageInfo)
        }

        result match {
          case LeftSide(errMsg) =>
            slackLog.warn(errMsg)
            None
          case RightSide(view) =>
            Some(keepId -> view)
        }
      }.toMap
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
