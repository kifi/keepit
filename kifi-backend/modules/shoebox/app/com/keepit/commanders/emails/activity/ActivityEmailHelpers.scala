package com.keepit.commanders.emails.activity

import com.keepit.commanders.{ LibraryCommander, ProcessedImageSize }
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.{ ActivityEmail, Library, User }
import org.joda.time.Duration

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

