package com.keepit.commanders

import com.keepit.common.crypto.{ PublicIdConfiguration }
import com.google.inject.{ Provider, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ S3ImageConfig, ImageSize }
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, AugmentableItem }

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.core._

class KeepDecorator @Inject() (
    db: Database,
    basicUserRepo: BasicUserRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    libraryRepo: LibraryRepo,
    collectionCommander: CollectionCommander,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepRepo: KeepRepo,
    keepImageCommander: KeepImageCommander,
    uriSummaryCommander: URISummaryCommander,
    userCommander: Provider[UserCommander],
    searchClient: SearchServiceClient,
    keepSourceAttributionRepo: KeepSourceAttributionRepo,
    experimentCommander: LocalUserExperimentCommander,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) {

  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keeps: Seq[Keep], idealImageSize: ImageSize, withKeepTime: Boolean): Future[Seq[KeepInfo]] = {
    if (keeps.isEmpty) Future.successful(Seq.empty[KeepInfo])
    else {
      val augmentationFuture = {
        val items = keeps.map { keep => AugmentableItem(keep.uriId) }
        searchClient.augment(perspectiveUserIdOpt, showPublishedLibraries, KeepInfo.maxKeepersShown, KeepInfo.maxLibrariesShown, 0, items).imap(augmentationInfos => filterLibraries(augmentationInfos))
      }
      val basicInfosFuture = augmentationFuture.map { augmentationInfos =>
        val idToLibrary = {
          val librariesShown = augmentationInfos.flatMap(_.libraries.map(_._1)).toSet
          db.readOnlyMaster { implicit s => libraryRepo.getLibraries(librariesShown) } //cached
        }
        val idToBasicUser = {
          val keepersShown = augmentationInfos.flatMap(_.keepers.map(_._1)).toSet
          val libraryContributorsShown = augmentationInfos.flatMap(_.libraries.map(_._2)).toSet
          val libraryOwners = idToLibrary.values.map(_.ownerId).toSet
          val keepers = keeps.map(_.userId).toSet // is this needed? need to double check, it may be redundant
          db.readOnlyMaster { implicit s => basicUserRepo.loadAll(keepersShown ++ libraryContributorsShown ++ libraryOwners ++ keepers) } //cached
        }
        val idToBasicLibrary = idToLibrary.mapValues(library => BasicLibrary(library, idToBasicUser(library.ownerId)))

        (idToBasicUser, idToBasicLibrary)
      }
      val pageInfosFuture = getKeepSummaries(keeps, idealImageSize)

      val colls = db.readOnlyMaster { implicit s =>
        keepToCollectionRepo.getCollectionsForKeeps(keeps) //cached
      }.map(collectionCommander.getBasicCollections)

      val sourceAttrs = db.readOnlyMaster { implicit s =>
        keeps.map { keep => keep.sourceAttributionId.map { id => keepSourceAttributionRepo.get(id) } }
      }

      val allMyKeeps = perspectiveUserIdOpt.map { userId => getBasicKeeps(userId, keeps.map(_.uriId).toSet) } getOrElse Map.empty[Id[NormalizedURI], Set[BasicKeep]]

      val librariesWithWriteAccess = perspectiveUserIdOpt.map { userId =>
        db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) } //cached
      } getOrElse Set.empty

      for {
        augmentationInfos <- augmentationFuture
        pageInfos <- pageInfosFuture
        (idToBasicUser, idToBasicLibrary) <- basicInfosFuture
      } yield {

        val keepsInfo = (keeps zip colls, augmentationInfos, pageInfos zip sourceAttrs).zipped.map {
          case ((keep, collsForKeep), augmentationInfoForKeep, (pageInfoForKeep, sourceAttrOpt)) =>
            val keepers = perspectiveUserIdOpt.map { userId => augmentationInfoForKeep.keepers.filterNot(_._1 == userId) } getOrElse augmentationInfoForKeep.keepers
            val keeps = allMyKeeps.get(keep.uriId) getOrElse Set.empty
            val libraries = {
              def doShowLibrary(libraryId: Id[Library]): Boolean = {
                // ensuring consistency of libraries returned by search with the user's latest database data (race condition)
                lazy val publicId = Library.publicId(libraryId)
                !librariesWithWriteAccess.contains(libraryId) || keeps.exists(_.libraryId == publicId)
              }
              augmentationInfoForKeep.libraries.collect { case (libraryId, contributorId, _) if doShowLibrary(libraryId) => (idToBasicLibrary(libraryId), idToBasicUser(contributorId)) }
            }

            val keptAt = if (withKeepTime) {
              //rather use the kept at, if not exist using the created at
              Some(keep.keptAt)
            } else {
              None
            }

            KeepInfo(
              id = Some(keep.externalId),
              title = keep.title,
              url = keep.url,
              isPrivate = keep.isPrivate,
              user = Some(idToBasicUser(keep.userId)),
              createdAt = keptAt,
              keeps = Some(keeps),
              keepers = Some(keepers.map { case (keeperId, _) => idToBasicUser(keeperId) }),
              keepersOmitted = Some(augmentationInfoForKeep.keepersOmitted),
              keepersTotal = Some(augmentationInfoForKeep.keepersTotal),
              libraries = Some(libraries),
              librariesOmitted = Some(augmentationInfoForKeep.librariesOmitted),
              librariesTotal = Some(augmentationInfoForKeep.librariesTotal),
              collections = Some(collsForKeep.map(_.id.get.id).toSet), // Is this still used?
              tags = Some(collsForKeep.toSet),
              hashtags = Some(collsForKeep.toSet.map { c: BasicCollection => Hashtag(c.name) }),
              summary = Some(pageInfoForKeep),
              siteName = DomainToNameMapper.getNameFromUrl(keep.url),
              libraryId = keep.libraryId.map(l => Library.publicId(l)),
              sourceAttribution = sourceAttrOpt,
              note = keep.note
            )
        }
        keepsInfo
      }
    }
  }

  def filterLibraries(infos: Seq[LimitedAugmentationInfo]): Seq[LimitedAugmentationInfo] = {
    val allUsers = (infos flatMap { info =>
      val keepers = info.keepers
      val libs = info.libraries
      (libs.map(_._2) ++ keepers.map(_._1))
    }).toSet
    if (allUsers.isEmpty) infos
    else {
      val fakeUsers = userCommander.get.getAllFakeUsers().intersect(allUsers)
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

  private def getKeepSummaries(keeps: Seq[Keep], idealImageSize: ImageSize): Future[Seq[URISummary]] = {
    val futureSummariesByUriId = rover.getUriSummaryByUris(keeps.map(_.uriId).toSet)
    val keepImagesByKeepId = keepImageCommander.getBestImagesForKeeps(keeps.map(_.id.get).toSet, ScaleImageRequest(idealImageSize))
    futureSummariesByUriId.map { summariesByUriId =>
      keeps.map { keep =>
        val summary = summariesByUriId.get(keep.uriId).map(_.toUriSummary(idealImageSize)) getOrElse URISummary()
        keepImagesByKeepId.get(keep.id.get) match {
          case None => summary
          case Some(keepImage) =>
            summary.copy(imageUrl = keepImage.map(_.imagePath.getUrl), imageWidth = keepImage.map(_.width), imageHeight = keepImage.map(_.height))
        }
      }
    }
  }

  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Map[Id[NormalizedURI], Set[BasicKeep]] = {
    val allKeeps = db.readOnlyReplica { implicit session =>
      val writeableLibs = libraryMembershipRepo.getLibrariesWithWriteAccess(userId)
      val allKeeps = keepRepo.getByLibraryIdsAndUriIds(writeableLibs, uriIds)
      allKeeps
    }
    val grouped = allKeeps.groupBy(_.uriId)
    uriIds.map { uriId =>
      grouped.get(uriId) match {
        case Some(keeps) =>
          val userKeeps = keeps.map { keep =>
            val mine = userId == keep.userId
            val libraryId = keep.libraryId.get
            val removable = true // all keeps here are writeable
            BasicKeep(
              id = keep.externalId,
              mine = mine,
              removable = removable,
              visibility = keep.visibility,
              libraryId = Library.publicId(libraryId)
            )
          }.toSet
          uriId -> userKeeps
        case _ =>
          uriId -> Set.empty[BasicKeep]
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
