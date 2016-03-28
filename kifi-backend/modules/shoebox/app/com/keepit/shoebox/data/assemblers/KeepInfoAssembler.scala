package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ BasicLibraryGen, BasicOrganizationGen }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.util.RightBias
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
    showPublishedLibraries: Boolean,
    numContextualKeeps: Int,
    numContextualKeepers: Int,
    numContextualLibraries: Int,
    numContextualTags: Int,
    sanitizeUrls: Boolean)

  val default = KeepViewAssemblyOptions(
    idealImageSize = ProcessedImageSize.Large.idealSize,
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
  keepImageCommander: KeepImageCommander,
  rover: RoverServiceClient,
  search: SearchServiceClient,
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
    val userInfoFut = db.readOnlyReplicaAsync { implicit s =>
      val basicUserById = {
        val userSet = ktusByKeep.values.flatMap(_.map(_.userId)).toSet
        basicUserRepo.loadAllActive(userSet)
      }
      basicUserById
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
      usersById <- userInfoFut
      imagesByKeep <- imageInfoFut
      permissionsByKeep <- permissionsFut
      augmentationByUri <- augmentationFut
      summaryByUri <- summaryFut
    } yield keepSet.flatMap { keepId =>
      val result = for {
        keep <- keepsById.get(keepId).withLeft(s"keep $keepId does not exist")
        _ <- RightBias.unit.filter(permissionsByKeep.get(keepId).exists(_.contains(KeepPermission.VIEW_KEEP)), s"keep $keepId is not visible to $viewer")
        author <- sourceByKeep.get(keepId).map {
          case (attr, userOpt) => BasicAuthor(attr, userOpt)
        }.orElse(keep.userId.flatMap(keeper => usersById.get(keeper).map(BasicAuthor.fromUser))).withLeft(s"no author for keep $keepId")
        augmentation <- augmentationByUri.get(keep.uriId).withLeft(s"keep $keepId with uri ${keep.uriId} does not have augmentations")
        summary <- summaryByUri.get(keep.uriId).withLeft(s"keep $keepId with uri ${keep.uriId} does not have content summary")
      } yield {
        val keepInfo = NewKeepInfo(
          id = Keep.publicId(keepId),
          path = keep.path,
          url = keep.url,
          title = keep.title,
          image = imagesByKeep.get(keepId),
          author = author,
          keptAt = keep.keptAt,
          source = sourceByKeep.get(keepId).map(_._1),
          users = ktusByKeep.getOrElse(keepId, Seq.empty).map(_.userId).sorted.flatMap(usersById.get),
          libraries = ktlsByKeep.getOrElse(keepId, Seq.empty).map(_.libraryId).sorted.flatMap(libsById.get),
          activity = KeepActivity.empty // TODO(ryan): fill in
        )
        val viewerInfo = NewKeepViewerInfo(
          permissions = permissionsByKeep.getOrElse(keepId, Set.empty)
        )
        val context = NewPageContext(
          keeps = augmentation.keeps,
          numVisibleKeeps = augmentation.keeps.length + augmentation.keepsOmitted,
          keepers = augmentation.keepers.flatMap { case (userId, time) => usersById.get(userId) }, // TODO(ryan): actually ensure that the user ids are in that map
          numVisibleKeepers = augmentation.keepers.length + augmentation.keepersOmitted,
          numTotalKeepers = augmentation.keepersTotal,
          libraries = augmentation.libraries.flatMap { case (libId, time, x) => libsById.get(libId) },
          numVisibleLibraries = augmentation.libraries.length + augmentation.librariesOmitted,
          numTotalLibraries = augmentation.librariesTotal,
          tags = augmentation.tags,
          numVisibleTags = augmentation.tags.length + augmentation.tagsOmitted)

        val content = NewPageContent(
          summary = NewPageSummary(
            authors = summary.article.authors,
            siteName = summary.article.title,
            publishedAt = summary.article.publishedAt,
            description = summary.article.description,
            wordCount = summary.article.wordCount),
          history = ???
        )

        NewKeepView(keep = keepInfo, viewer = viewerInfo, context = context, content = content)
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
