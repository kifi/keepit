package com.keepit.commanders.emails.activity

import com.keepit.commanders.emails.{ BaseLibraryInfoView, LibraryInfoFollowersView }
import com.keepit.commanders.{ LibraryCommander, ProcessedImageSize }
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ ActivityEmail, Library, LibraryMembership, LibraryMembershipRepo, User }
import org.joda.time.Duration
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

trait ActivityEmailHelpers {
  def clock: Clock

  lazy val emailPrepTime = clock.now()

  // the most we will look back for certain records (libraries, followers)
  def minRecordAge = emailPrepTime.minus(Duration.standardDays(7))

  // the last time the activity email was sent to this user
  def lastEmailSentAt(previouslySent: Seq[ActivityEmail]) = previouslySent.headOption.map(_.createdAt).getOrElse(minRecordAge)
}

trait ActivityEmailLibraryHelpers extends ActivityEmailHelpers {
  def libraryCommander: LibraryCommander

  protected def createFullLibraryInfos(toUserId: Id[User], libraries: Seq[Library]) = {
    libraryCommander.createFullLibraryInfos(viewerUserIdOpt = Some(toUserId),
      showPublishedLibraries = true, maxKeepsShown = 10,
      maxMembersShown = 0, idealKeepImageSize = ProcessedImageSize.Large.idealSize,
      idealLibraryImageSize = ProcessedImageSize.Large.idealSize,
      libraries = libraries, withKeepTime = true)
  }
}

trait ActivityEmailLibraryMembershipHelpers extends ActivityEmailLibraryHelpers {
  def membershipRepo: LibraryMembershipRepo

  def createLibraryInfoFollowersViews(toUserId: Id[User],
    libraries: Map[Id[Library], Library],
    librariesToMembersMap: Map[Id[Library], Seq[LibraryMembership]],
    friends: Set[Id[User]]): Future[Seq[LibraryInfoFollowersView]] = {

    val libInfosF = createFullLibraryInfos(toUserId, libraries.map(_._2).toSeq)
    libInfosF map { libInfos =>
      libInfos map {
        case (libId, libInfo) =>
          val members = librariesToMembersMap(libId) map (_.userId) sortBy friendsFirstSorter(friends)
          val libInfoView = BaseLibraryInfoView(libId, libInfo)
          LibraryInfoFollowersView(libInfoView, members)
      }
    }
  }

  // sorts users by friend status, randomly shuffling the friends and others while ensuring all friends are first
  def friendsFirstSorter(friends: Set[Id[User]]) = (userId: Id[User]) => {
    val rand = Math.abs(util.Random.nextInt())
    if (friends.contains(userId)) -rand else rand
  }
}
