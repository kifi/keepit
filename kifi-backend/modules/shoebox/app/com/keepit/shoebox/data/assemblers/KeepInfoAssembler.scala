package com.keepit.shoebox.data.assemblers

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders._
import com.keepit.commanders.gen.{ BasicLibraryGen, BasicOrganizationGen }
import com.keepit.common.core.{ anyExtensionOps, iterableExtensionOps }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.SchemaReads
import com.keepit.common.logging.SlackLog
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.net.QsFormat
import com.keepit.common.performance.Stopwatch
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.common.util.RightBias
import com.keepit.common.util.RightBias._
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.{ SearchFilter, SearchServiceClient }
import com.keepit.search.augmentation.{ AugmentableItem, LimitedAugmentationInfo }
import com.keepit.shoebox.data.assemblers.KeepInfoAssemblerConfig.KeepViewAssemblyOptions
import com.keepit.shoebox.data.keep._
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.BasicAuthor
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@ImplementedBy(classOf[KeepInfoAssemblerImpl])
trait KeepInfoAssembler {
  def assembleKeepInfos(viewer: Option[Id[User]], keepSet: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepInfo]]]
  def assemblePageInfos(viewer: Option[Id[User]], uriSet: Set[Id[NormalizedURI]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[NormalizedURI], NewPageInfo]]
  def assembleKeepViews(viewer: Option[Id[User]], keepSet: Set[Id[Keep]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepView]]]
}

object KeepInfoAssemblerConfig {
  final case class KeepViewAssemblyOptions(
    idealImageSize: ImageSize,
    numEventsPerKeep: Int,
    hideOtherPublishedLibraries: Boolean,
    numContextualKeeps: Int,
    numContextualKeepers: Int,
    numContextualLibraries: Int,
    numContextualTags: Int,
    sanitizeUrls: Boolean)

  val default = KeepViewAssemblyOptions(
    idealImageSize = ProcessedImageSize.Large.idealSize,
    numEventsPerKeep = 5,
    hideOtherPublishedLibraries = false,
    numContextualKeeps = 1,
    numContextualKeepers = 1,
    numContextualLibraries = 1,
    numContextualTags = 1,
    sanitizeUrls = true
  )

  import com.keepit.common.net.QsPath._
  private implicit val qsfImageSize: QsFormat[ImageSize] = (
    (q__ \ "width").qsf[Int] and
    (q__ \ "height").qsf[Int]
  )(ImageSize.apply, unlift(ImageSize.unapply))

  val qsf: QsFormat[KeepViewAssemblyOptions] = (
    (q__ \ "idealImageSize").qsfOpt[ImageSize].withDefault(default.idealImageSize) and
    (q__ \ "numEventsPerKeep").qsfOpt[Int].withDefault(default.numEventsPerKeep) and
    (q__ \ "hideOtherPublishedLibraries").qsfOpt[Boolean].withDefault(default.hideOtherPublishedLibraries) and
    (q__ \ "numContextualKeeps").qsfOpt[Int].withDefault(default.numContextualKeeps) and
    (q__ \ "numContextualKeepers").qsfOpt[Int].withDefault(default.numContextualKeepers) and
    (q__ \ "numContextualLibraries").qsfOpt[Int].withDefault(default.numContextualLibraries) and
    (q__ \ "numContextualTags").qsfOpt[Int].withDefault(default.numContextualTags) and
    (q__ \ "sanitizeUrls").qsfOpt[Boolean].withDefault(default.sanitizeUrls)
  )(KeepViewAssemblyOptions.apply, unlift(KeepViewAssemblyOptions.unapply))

  implicit val qsBinder = QsFormat.binder(qsf)

  import SchemaReads._
  private implicit val imageSizeSReads = SchemaReads.trivial[ImageSize]("image size")
  implicit val useDefaultForMissing: SchemaReads[KeepViewAssemblyOptions] = (
    (__ \ "idealImageSize").readNullableWithSchema[ImageSize].withDefault(default.idealImageSize) and
    (__ \ "numEventsPerKeep").readNullableWithSchema[Int].withDefault(default.numEventsPerKeep) and
    (__ \ "hideOtherPublishedLibraries").readNullableWithSchema[Boolean].withDefault(default.hideOtherPublishedLibraries) and
    (__ \ "numContextualKeeps").readNullableWithSchema[Int].withDefault(default.numContextualKeeps) and
    (__ \ "numContextualKeepers").readNullableWithSchema[Int].withDefault(default.numContextualKeepers) and
    (__ \ "numContextualLibraries").readNullableWithSchema[Int].withDefault(default.numContextualLibraries) and
    (__ \ "numContextualTags").readNullableWithSchema[Int].withDefault(default.numContextualTags) and
    (__ \ "sanitizeUrls").readNullableWithSchema[Boolean].withDefault(default.sanitizeUrls)
  )(KeepViewAssemblyOptions.apply _)
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
  activityAssembler: KeepActivityAssembler,
  rover: RoverServiceClient,
  search: SearchServiceClient,
  userExperimentRepo: UserExperimentRepo,
  userCommander: UserCommander,
  private implicit val airbrake: AirbrakeNotifier,
  private implicit val imageConfig: S3ImageConfig,
  private implicit val executionContext: ExecutionContext,
  private implicit val publicIdConfig: PublicIdConfiguration,
  private implicit val inhouseSlackClient: InhouseSlackClient)
    extends KeepInfoAssembler {

  private val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

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
      val keepsById = keepRepo.getActiveByIds(keepIds)
      val activeKeepIds = keepsById.keySet
      val ktlsByKeep = ktlRepo.getAllByKeepIds(activeKeepIds)
      val ktusByKeep = ktuRepo.getAllByKeepIds(activeKeepIds)
      val ktesByKeep = kteRepo.getAllByKeepIds(activeKeepIds)
      KeepsAndConnections(keepsById, ktlsByKeep, ktusByKeep, ktesByKeep)
    }
  }

  private def keepViewAssemblyHelper(viewer: Option[Id[User]], keepsAndConnections: KeepsAndConnections, config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[Keep], RightBias[KeepFail, NewKeepView]]] = {
    val keepsById = keepsAndConnections.keepsById
    val keepInfoFut = keepInfoAssemblyHelper(viewer, keepsAndConnections, config)
    val pageInfoFut = pageInfoAssemblyHelper(viewer, keepsById.values.map(_.uriId).toSet, config)
    for {
      keepInfosByKeep <- keepInfoFut
      pageInfosByUri <- pageInfoFut
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
    val stopwatch = new Stopwatch(s"[KIA-KEEPINFO-${RandomStringUtils.randomAlphanumeric(5)}]")
    val keepsById = keepsAndConnections.keepsById
    val ktlsByKeep = keepsAndConnections.ktlsByKeep
    val ktusByKeep = keepsAndConnections.ktusByKeep
    val ktesByKeep = keepsAndConnections.ktesByKeep
    val keepSet = keepsById.keySet
    stopwatch.logTimeWith(s"start_${keepSet.size}")

    val sourceInfoFut = db.readOnlyReplicaAsync { implicit s =>
      keepSourceCommander.getSourceAttributionForKeeps(keepSet)
    } andThen { case _ => stopwatch.logTimeWith("complete_source") }
    stopwatch.logTimeWith("launched_source")
    val imageInfoFut = keepImageCommander.getBestImagesForKeepsPatiently(keepSet, ScaleImageRequest(config.idealImageSize)).map { keepImageByKeep =>
      keepImageByKeep.collect {
        case (keepId, Some(keepImage)) => keepId -> NewKeepImageInfo(
          url = keepImage.imagePath.getImageUrl,
          dimensions = keepImage.dimensions
        )
      }
    } andThen { case _ => stopwatch.logTimeWith("complete_image") }
    stopwatch.logTimeWith("launched_image")

    val permissionsFut = db.readOnlyReplicaAsync { implicit s =>
      permissionCommander.getKeepsPermissions(keepSet, viewer)
    } andThen { case _ => stopwatch.logTimeWith("complete_permission") }
    stopwatch.logTimeWith("launched_permission")

    val activityFut = {
      if (config.numEventsPerKeep <= 0) Future.successful(Map.empty[Id[Keep], KeepActivity])
      else activityAssembler.getActivityForKeeps(keepSet, fromTime = None, numEventsPerKeep = config.numEventsPerKeep)
    } andThen { case _ => stopwatch.logTimeWith("complete_activity") }
    stopwatch.logTimeWith("launched_activity")

    for {
      sourceByKeep <- sourceInfoFut
      imagesByKeep <- imageInfoFut
      permissionsByKeep <- permissionsFut
      activityByKeep <- activityFut
    } yield {
      stopwatch.logTimeWith("gathering_basics")
      val (usersById, libsById) = db.readOnlyMaster { implicit s =>
        val userSet = ktusByKeep.values.flatMap(_.map(_.userId)).toSet ++ keepsById.values.flatMap(_.userId).toSet
        val libSet = ktlsByKeep.values.flatMap(_.map(_.libraryId)).toSet
        (basicUserRepo.loadAllActive(userSet), basicLibGen.getBasicLibraries(libSet))
      }
      stopwatch.logTimeWith("assembling_infos")
      keepSet.toSeq.augmentWith { keepId =>
        for {
          keep <- keepsById.get(keepId).withLeft(KeepFail.KEEP_NOT_FOUND)
          permissions <- RightBias.right(permissionsByKeep.getOrElse(keepId, Set.empty)).filter(_.contains(KeepPermission.VIEW_KEEP), KeepFail.INSUFFICIENT_PERMISSIONS)
          author <- sourceByKeep.get(keepId).map {
            case (attr, userOpt) => BasicAuthor(attr, userOpt)
          }.orElse(keep.userId.flatMap(keeper => usersById.get(keeper).map(BasicAuthor.fromUser))).withLeft(KeepFail.INVALID_KEEP_ID)
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
            activity = activityByKeep(keepId),
            viewer = viewerInfo
          )

          keepInfo
        }
      }
    }.toMap tap { result =>
      stopwatch.logTimeWith("done")
      val failures = result.collect { case (kId, LeftSide(fail)) => kId -> fail }
      if (failures.nonEmpty) slackLog.error(s"When generating keep infos for $viewer, ran into errors: " + failures)
    }
  }

  private def pageInfoAssemblyHelper(viewer: Option[Id[User]], uriIds: Set[Id[NormalizedURI]], config: KeepViewAssemblyOptions = KeepInfoAssemblerConfig.default): Future[Map[Id[NormalizedURI], NewPageInfo]] = {
    val stopwatch = new Stopwatch(s"[KIA-PAGEINFO-${RandomStringUtils.randomAlphanumeric(5)}]")
    val sortedUris = uriIds.toSeq.sorted
    val (augmentationFut, summaryFut) = {
      val augmentation = search.augment(
        viewer,
        SearchFilter.default, // todo(Léo/Ryan): Not sure whether I should make this part of KeepInfoAssemblerConfig since it's not made of straigthforward primitives
        config.hideOtherPublishedLibraries,
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
    stopwatch.logTimeWith("launched_both")

    for {
      augmentationByUri <- augmentationFut.tap(_.onComplete(_ => stopwatch.logTimeWith("complete_augment")))
      summaryByUri <- summaryFut.tap(_.onComplete(_ => stopwatch.logTimeWith("complete_summary")))
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
      }.toMap tap { result =>
        stopwatch.logTimeWith("done")
      }
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
