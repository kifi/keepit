package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.util.Paginator
import com.keepit.model._
import com.keepit.common.core._

import scala.collection.mutable
import scala.util.Random

class ChecklistCommander @Inject() (
    db: Database,
    kifiInstallationRepo: KifiInstallationRepo,
    libraryCommander: LibraryCommander,
    userProfileCommander: UserProfileCommander,
    libraryRepo: LibraryRepo,
    libraryInviteRepo: LibraryInviteRepo,
    invitationRepo: InvitationRepo,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    keepRepo: KeepRepo) {
  import ChecklistPlatform._
  private val mobilePlatforms = Set(KifiInstallationPlatform.IPhone, KifiInstallationPlatform.Android)
  private val checklistSize = 5

  def checklist(userId: Id[User], platform: ChecklistPlatform): Map[String, Boolean] = {
    platform match {
      case Website =>
        db.readOnlyReplica { implicit session =>
          val sources = keepRepo.getKeepSourcesByUser(userId).toSet
          lazy val installations = kifiInstallationRepo.all(userId)

          val hasExt = sources.contains(KeepSource.keeper) || installations.exists(_.platform == KifiInstallationPlatform.Extension)
          val hasMobile = sources.contains(KeepSource.mobile) || installations.exists(p => mobilePlatforms.contains(p.platform))
          val followsSeveralLibs = libraryRepo.getFollowingLibrariesForSelf(userId, Paginator(0, 5), None).length >= 5
          val keptSeveralPages = sources.size > 2 || keepRepo.getCountByUser(userId) >= 5
          val importedBrowserBookmarks = sources.contains(KeepSource.bookmarkImport) || sources.contains(KeepSource.bookmarkFileImport)
          val importedThirdParty = KeepSource.imports.exists(sources.contains)
          val hasTwitterSync = sources.contains(KeepSource.twitterSync) || sources.contains(KeepSource.twitterFileImport) || twitterWaitlistRepo.getByUser(userId).nonEmpty
          val hasInvitedFriends = invitationRepo.countByUser(userId) + libraryInviteRepo.getByUser(userId, Set(LibraryInviteStates.INACTIVE)).length >= 3

          val all = Seq(
            "install_ext" -> hasExt,
            "install_mobile" -> hasMobile,
            "invite_fiends" -> hasInvitedFriends,
            "follow_libs" -> followsSeveralLibs,
            "keep_pages" -> keptSeveralPages,
            "import_bookmarks" -> importedBrowserBookmarks,
            "import_third_party" -> importedThirdParty,
            "twitter_sync" -> hasTwitterSync
          )

          val first = if (hasMobile) {
            "install_mobile" -> hasMobile
          } else {
            "install_ext" -> hasExt
          }

          val rest = {
            val restSize = checklistSize - 1
            val (complete, incomplete) = all.filterNot(_ == first).partition(_._2)
            val incompleteShuffled = Random.shuffle(incomplete).take(restSize)
            val completeShuffled = if (incompleteShuffled.length <= restSize) {
              Random.shuffle(complete).take(restSize - incompleteShuffled.length)
            } else Seq.empty
            Random.shuffle(incompleteShuffled ++ completeShuffled).take(restSize)
          }

          first +: rest
        }.toMap
    }
  }
}

sealed trait ChecklistPlatform
object ChecklistPlatform {
  case object Website extends ChecklistPlatform
}