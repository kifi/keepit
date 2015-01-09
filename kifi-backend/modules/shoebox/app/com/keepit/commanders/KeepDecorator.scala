package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.store.ImageSize
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import com.keepit.search.augmentation.AugmentableItem

import scala.concurrent.Future

class KeepDecorator @Inject() (
    searchClient: SearchServiceClient) {

  def decorateKeepsIntoKeepInfos(perspectiveUserIdOpt: Option[Id[User]], showPublishedLibraries: Boolean, keeps: Seq[Keep], idealImageSize: ImageSize): Future[Seq[KeepInfo]] = {
    if (keeps.isEmpty) Future.successful(Seq.empty[KeepInfo])
    else {
      val augmentationFuture = {
        val items = keeps.map { keep => AugmentableItem(keep.uriId) }
        searchClient.augment(perspectiveUserIdOpt, showPublishedLibraries, KeepInfo.maxKeepersShown, KeepInfo.maxLibrariesShown, 0, items).imap(augmentationInfos => filterLibraries(augmentationInfos))
      }
      val basicInfosFuture = augmentationFuture.map { augmentationInfos =>
        val idToLibrary = {
          val librariesShown = augmentationInfos.flatMap(_.libraries.map(_._1)).toSet
          db.readOnlyMaster { implicit s => libraryRepo.getLibraries(librariesShown) }
        }
        val idToBasicUser = {
          val keepersShown = augmentationInfos.flatMap(_.keepers).toSet
          val libraryContributorsShown = augmentationInfos.flatMap(_.libraries.map(_._2)).toSet
          val libraryOwners = idToLibrary.values.map(_.ownerId).toSet
          db.readOnlyMaster { implicit s => basicUserRepo.loadAll(keepersShown ++ libraryContributorsShown ++ libraryOwners) }
        }
        val idToBasicLibrary = idToLibrary.mapValues(library => BasicLibrary(library, idToBasicUser(library.ownerId)))

        (idToBasicUser, idToBasicLibrary)
      }
      val pageInfosFuture = Future.sequence(keeps.map { keep =>
        getKeepSummary(keep, idealImageSize)
      })

      val colls = db.readOnlyMaster { implicit s =>
        keepToCollectionRepo.getCollectionsForKeeps(keeps)
      }.map(collectionCommander.getBasicCollections)

      val allMyKeeps = perspectiveUserIdOpt.map { userId => getBasicKeeps(userId, keeps.map(_.uriId).toSet) } getOrElse Map.empty[Id[NormalizedURI], Set[BasicKeep]]

      for {
        augmentationInfos <- augmentationFuture
        pageInfos <- pageInfosFuture
        (idToBasicUser, idToBasicLibrary) <- basicInfosFuture
      } yield {

        val keepsInfo = (keeps zip colls, augmentationInfos, pageInfos).zipped.map {
          case ((keep, collsForKeep), augmentationInfoForKeep, pageInfoForKeep) =>
            val others = augmentationInfoForKeep.keepersTotal - augmentationInfoForKeep.keepers.size - augmentationInfoForKeep.keepersOmitted
            val keepers = perspectiveUserIdOpt.map { userId => augmentationInfoForKeep.keepers.filterNot(_ == userId) } getOrElse augmentationInfoForKeep.keepers
            KeepInfo(
              id = Some(keep.externalId),
              title = keep.title,
              url = keep.url,
              isPrivate = keep.isPrivate,
              createdAt = Some(keep.createdAt),
              others = Some(others),
              keeps = allMyKeeps.get(keep.uriId),
              keepers = Some(keepers.map(idToBasicUser)),
              keepersOmitted = Some(augmentationInfoForKeep.keepersOmitted),
              keepersTotal = Some(augmentationInfoForKeep.keepersTotal),
              libraries = Some(augmentationInfoForKeep.libraries.map { case (libraryId, contributorId) => (idToBasicLibrary(libraryId), idToBasicUser(contributorId)) }),
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

}
