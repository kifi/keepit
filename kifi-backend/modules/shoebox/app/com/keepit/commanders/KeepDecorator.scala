package com.keepit.commanders

import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.google.inject.{ Provider, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.{ LimitedAugmentationInfo, AugmentableItem }

import scala.concurrent.Future
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
          val keepersShown = augmentationInfos.flatMap(_.keepers).toSet
          val libraryContributorsShown = augmentationInfos.flatMap(_.libraries.map(_._2)).toSet
          val libraryOwners = idToLibrary.values.map(_.ownerId).toSet
          db.readOnlyMaster { implicit s => basicUserRepo.loadAll(keepersShown ++ libraryContributorsShown ++ libraryOwners) } //cached
        }
        val idToBasicLibrary = idToLibrary.mapValues(library => BasicLibrary(library, idToBasicUser(library.ownerId)))

        (idToBasicUser, idToBasicLibrary)
      }
      val pageInfosFuture = Future.sequence(keeps.map { keep =>
        getKeepSummary(keep, idealImageSize)
      })

      val colls = db.readOnlyMaster { implicit s =>
        keepToCollectionRepo.getCollectionsForKeeps(keeps) //cached
      }.map(collectionCommander.getBasicCollections)

      val allMyKeeps = perspectiveUserIdOpt.map { userId => getBasicKeeps(userId, keeps.map(_.uriId).toSet) } getOrElse Map.empty[Id[NormalizedURI], Set[BasicKeep]]

      val librariesWithWriteAccess = perspectiveUserIdOpt.map { userId =>
        db.readOnlyMaster { implicit session => libraryMembershipRepo.getLibrariesWithWriteAccess(userId) } //cached
      } getOrElse Set.empty

      for {
        augmentationInfos <- augmentationFuture
        pageInfos <- pageInfosFuture
        (idToBasicUser, idToBasicLibrary) <- basicInfosFuture
      } yield {

        val keepsInfo = (keeps zip colls, augmentationInfos, pageInfos).zipped.map {
          case ((keep, collsForKeep), augmentationInfoForKeep, pageInfoForKeep) =>
            val others = augmentationInfoForKeep.keepersTotal - augmentationInfoForKeep.keepers.size - augmentationInfoForKeep.keepersOmitted
            val keepers = perspectiveUserIdOpt.map { userId => augmentationInfoForKeep.keepers.filterNot(_ == userId) } getOrElse augmentationInfoForKeep.keepers
            val keeps = allMyKeeps.get(keep.uriId) getOrElse Set.empty
            val libraries = {
              def doShowLibrary(libraryId: Id[Library]): Boolean = { // ensuring consistency of libraries returned by search with the user's latest database data (race condition)
                lazy val publicId = Library.publicId(libraryId)
                !librariesWithWriteAccess.contains(libraryId) || keeps.exists(_.libraryId == publicId)
              }
              augmentationInfoForKeep.libraries.collect { case (libraryId, contributorId) if doShowLibrary(libraryId) => (idToBasicLibrary(libraryId), idToBasicUser(contributorId)) }
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
              createdAt = keptAt,
              others = Some(others),
              keeps = Some(keeps),
              keepers = Some(keepers.map(idToBasicUser)),
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
              clickCount = None,
              rekeepCount = None,
              libraryId = keep.libraryId.map(l => Library.publicId(l))
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
      (libs.map(_._2) ++ keepers)
    }).toSet
    if (allUsers.isEmpty) infos
    else {
      val fakeUsers = userCommander.get.getAllFakeUsers().intersect(allUsers)
      if (fakeUsers.isEmpty) infos
      else {
        infos map { info =>
          val keepers = info.keepers.filterNot(u => fakeUsers.contains(u))
          val libs = info.libraries.filterNot(t => fakeUsers.contains(t._2))
          info.copy(keepers = keepers, libraries = libs)
        }
      }
    }
  }

  private def getKeepSummary(keep: Keep, idealImageSize: ImageSize, waiting: Boolean = false): Future[URISummary] = {
    val futureSummary = uriSummaryCommander.getDefaultURISummary(keep.uriId, waiting)
    val keepImageOpt = keepImageCommander.getBestImageForKeep(keep.id.get, ScaleImageRequest(idealImageSize))
    futureSummary.map { summary =>
      keepImageOpt match {
        case None => summary
        case Some(keepImage) =>
          summary.copy(imageUrl = keepImage.map(keepImageCommander.getUrl), imageWidth = keepImage.map(_.width), imageHeight = keepImage.map(_.height))
      }
    }
  }

  def getBasicKeeps(userId: Id[User], uriIds: Set[Id[NormalizedURI]]): Map[Id[NormalizedURI], Set[BasicKeep]] = {
    val (allKeeps, libraryMemberships) = db.readOnlyReplica { implicit session =>
      val allKeeps = keepRepo.getByUserAndUriIds(userId, uriIds)
      val libraryMemberships = libraryMembershipRepo.getWithLibraryIdsAndUserId(allKeeps.map(_.libraryId.get).toSet, userId)
      (allKeeps, libraryMemberships)
    }
    val grouped = allKeeps.groupBy(_.uriId)
    uriIds.map { uriId =>
      grouped.get(uriId) match {
        case Some(keeps) =>
          val userKeeps = keeps.map { keep =>
            val mine = userId == keep.userId
            val libraryId = keep.libraryId.get
            val removable = libraryMemberships.get(libraryId).exists(_.canWrite)
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
