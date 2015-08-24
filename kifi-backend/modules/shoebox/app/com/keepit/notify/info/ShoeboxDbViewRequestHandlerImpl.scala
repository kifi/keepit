package com.keepit.notify.info

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ OrganizationAvatarCommander, LibraryImageCommander, ProcessedImageSize, PathCommander }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.model._

import scala.concurrent.Future

/**
 * A DB view request handler on the shoebox side. This should almost never be used, perhaps only a when
 * retrieving information as a notification event is being created.
 */
@Singleton
class ShoeboxDbViewRequestHandlerImpl @Inject() (
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    orgRepo: OrganizationRepo,
    libraryImageCommander: LibraryImageCommander,
    organizationAvatarCommander: OrganizationAvatarCommander,
    s3ImageStore: S3ImageStore,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    pathCommander: PathCommander,
    db: Database,
    implicit val config: PublicIdConfiguration) extends DbViewRequestHandler {

  override val handlers =
    DbViewRequestHandlers()
      .add(DbViewKey.user) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          userRepo.getUsers(requests.map(_.id))
        })
      }.add(DbViewKey.library) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          libraryRepo.getLibraries(requests.map(_.id).toSet)
        })
      }.add(DbViewKey.userImageUrl) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          userRepo.getUsers(requests.map(_.id))
        }.map {
          case (id, user) =>
            (id, s3ImageStore.avatarUrlByExternalId(Some(200), user.externalId, user.pictureName.getOrElse("0"), Some("https")))
        })
      }.add(DbViewKey.keep) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          keepRepo.getByIds(requests.map(_.id).toSet)
        })
      }.add(DbViewKey.libraryUrl) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          libraryRepo.getLibraries(requests.map(_.id).toSet)
        }.map {
          case (id, library) => (id, pathCommander.pathForLibrary(library).encode.absolute)
        })
      }.add(DbViewKey.libraryInfo) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          libraryRepo.getLibraries(requests.map(_.id).toSet).map {
            case (id, library) =>
              val imageOpt = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Medium.idealSize)
              val libOwner = basicUserRepo.load(library.ownerId)
              (id, LibraryNotificationInfoBuilder.fromLibraryAndOwner(library, imageOpt, libOwner))
          }
        })
      }.add(DbViewKey.libraryOwner) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          val libraries = libraryRepo.getLibraries(requests.map(_.id).toSet)
          val userIds = libraries.collect {
            case (id, library) => library.ownerId
          }
          val users = userRepo.getUsers(userIds.toSeq)
          libraries.map {
            case (id, library) =>
              (id, users(library.ownerId))
          }
        })
      }.add(DbViewKey.organization) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          orgRepo.getByIds(requests.map(_.id).toSet)
        })
      }.add(DbViewKey.organizationInfo) { requests =>
        Future.successful(db.readOnlyReplica { implicit session =>
          orgRepo.getByIds(requests.map(_.id).toSet)
        }.map {
          case (id, org) =>
            val orgImageOpt = organizationAvatarCommander.getBestImageByOrgId(org.id.get, ProcessedImageSize.Medium.idealSize)
            (id, OrganizationNotificationInfoBuilder.fromOrganization(org, orgImageOpt))
        })
      }

}
