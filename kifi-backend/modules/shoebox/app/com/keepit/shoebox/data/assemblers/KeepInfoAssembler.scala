package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ BasicLibraryGen, BasicOrganizationGen }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.util.{ ActivityLog, RightBias }
import com.keepit.common.util.RightBias._
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.model._
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepInfoAssemblyOptions
import com.keepit.shoebox.data.keep.{ NewKeepImageInfo, NewKeepViewerInfo, NewKeepInfo }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.BasicAuthor

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[KeepInfoAssemblerImpl])
trait KeepInfoAssembler {
  def assembleInfoForKeeps(viewer: Option[Id[User]], keepIds: Seq[Id[Keep]], config: KeepInfoAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Seq[(NewKeepInfo, NewKeepViewerInfo)]]
}

object KeepInfoAssemblerConfig {
  final case class KeepInfoAssemblyOptions(
    idealImageSize: ImageSize,
    sanitizeUrls: Boolean)

  val default = KeepInfoAssemblyOptions(
    idealImageSize = ProcessedImageSize.Large.idealSize,
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
  private implicit val airbrake: AirbrakeNotifier,
  private implicit val imageConfig: S3ImageConfig,
  private implicit val executionContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepInfoAssembler {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  def assembleInfoForKeeps(viewer: Option[Id[User]], keepIds: Seq[Id[Keep]], config: KeepInfoAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Seq[(NewKeepInfo, NewKeepViewerInfo)]] = {
    val keepSet = keepIds.toSet
    val (keepsById, ktlsByKeep, ktusByKeep) = db.readOnlyReplica { implicit s =>
      val keepsById = keepRepo.getByIds(keepSet)
      val ktlsByKeep = ktlRepo.getAllByKeepIds(keepSet)
      val ktusByKeep = ktuRepo.getAllByKeepIds(keepSet)
      (keepsById, ktlsByKeep, ktusByKeep)
    }

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

    val assembledInfoFut = for {
      sourceByKeep <- sourceInfoFut
      libsById <- libInfoFut
      usersById <- userInfoFut
      imagesByKeep <- imageInfoFut
      permissionsByKeep <- permissionsFut
    } yield keepIds.map { keepId =>
      for {
        keep <- keepsById.get(keepId).withLeft(s"keep $keepId does not exist")
        author <- sourceByKeep.get(keepId).map {
          case (attr, userOpt) => BasicAuthor(attr, userOpt)
        }.orElse(keep.userId.flatMap(keeper => usersById.get(keeper).map(BasicAuthor.fromUser))).withLeft(s"no author for keep $keepId")
        _ <- RightBias.unit.filter(permissionsByKeep.get(keepId).exists(_.contains(KeepPermission.VIEW_KEEP)), s"keep $keepId is not visible to $viewer")
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
          activity = ActivityLog.empty // TODO(ryan): fill in
        )
        val viewerInfo = NewKeepViewerInfo(
          permissions = permissionsByKeep.getOrElse(keepId, Set.empty)
        )
        (keepInfo, viewerInfo)
      }
    }

    assembledInfoFut.map(_.flatMap {
      case LeftSide(errMsg) =>
        slackLog.warn(errMsg)
        None
      case RightSide((keepInfo, viewerInfo)) =>
        Some((keepInfo, viewerInfo))
    })
  }
}
