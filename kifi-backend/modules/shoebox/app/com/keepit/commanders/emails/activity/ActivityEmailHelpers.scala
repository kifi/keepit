package com.keepit.commanders.emails.activity

import com.keepit.commanders.{ LibraryCommander, ProcessedImageSize }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ ActivityEmail, Library, LibraryMembershipRepo, LibraryRepo, User }
import org.joda.time.Duration

trait ActivityEmailHelpers { this: ActivityEmailDependencies =>
  def toUserId: Id[User]

  lazy val emailPrepTime = clock.now()

  // the most we will look back for certain records (libraries, followers)
  def minRecordAge = emailPrepTime.minus(Duration.standardDays(7))

  // previously sent activity emails for the `toUserId` user
  def previouslySent: Seq[ActivityEmail]

  // the last time the activity email was sent to this user
  lazy val lastEmailSentAt = previouslySent.headOption.map(_.createdAt).getOrElse(minRecordAge)
}

trait ActivityEmailDependencies {
  def airbrake: AirbrakeNotifier
  def libraryCommander: LibraryCommander
  def clock: Clock
  def membershipRepo: LibraryMembershipRepo
  def libraryRepo: LibraryRepo
  def db: Database
  def eliza: ElizaServiceClient
}

trait ActivityEmailLibraryHelpers extends ActivityEmailHelpers { this: ActivityEmailDependencies =>
  protected def createFullLibraryInfos(libraries: Seq[Library]) = {
    libraryCommander.createFullLibraryInfos(viewerUserIdOpt = Some(toUserId),
      showPublishedLibraries = true, maxKeepsShown = 10,
      maxMembersShown = 0, idealKeepImageSize = ProcessedImageSize.Large.idealSize,
      idealLibraryImageSize = ProcessedImageSize.Large.idealSize,
      libraries = libraries, withKeepTime = true)
  }
}

