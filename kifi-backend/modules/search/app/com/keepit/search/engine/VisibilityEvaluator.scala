package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.NumericDocValues
import scala.concurrent.duration._
import scala.concurrent.Future

trait VisibilityEvaluator { self: DebugOption =>

  protected val userId: Long
  protected val friendIdsFuture: Future[Set[Long]]
  protected val restrictedUserIdsFuture: Future[Set[Long]]
  protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])]
  protected val monitoredAwait: MonitoredAwait

  lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  lazy val restrictedUserIds = LongArraySet.fromSet(monitoredAwait.result(restrictedUserIdsFuture, 5 seconds, s"getting restricted user ids"))

  lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds))
  }

  protected def getKeepVisibilityEvaluator(userIdDocValues: NumericDocValues, visibilityDocValues: NumericDocValues): KeepVisibilityEvaluator = {
    new KeepVisibilityEvaluator(
      userId,
      myFriendIds,
      restrictedUserIds,
      myOwnLibraryIds,
      memberLibraryIds,
      trustedLibraryIds,
      authorizedLibraryIds,
      userIdDocValues,
      visibilityDocValues)
  }

  protected def getLibraryVisibilityEvaluator(ownerIdDocValues: NumericDocValues, visibilityDocValues: NumericDocValues): LibraryVisibilityEvaluator = {
    new LibraryVisibilityEvaluator(
      myOwnLibraryIds,
      memberLibraryIds,
      myFriendIds,
      restrictedUserIds,
      ownerIdDocValues,
      visibilityDocValues)
  }

  protected def getUserVisibilityEvaluator(): UserVisibilityEvaluator = {
    new UserVisibilityEvaluator(
      userId,
      myFriendIds,
      restrictedUserIds
    )
  }

  protected def listLibraries(): Unit = {
    debugLog(s"""myLibs: ${myOwnLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""memberLibs: ${memberLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""trustedLibs: ${trustedLibraryIds.toSeq.sorted.mkString(",")}""")
    debugLog(s"""authorizedLibs: ${authorizedLibraryIds.toSeq.sorted.mkString(",")}""")
  }
}

final class KeepVisibilityEvaluator(
    userId: Long,
    myFriendIds: LongArraySet,
    restrictedUserIds: LongArraySet,
    myOwnLibraryIds: LongArraySet,
    memberLibraryIds: LongArraySet,
    trustedLibraryIds: LongArraySet,
    authorizedLibraryIds: LongArraySet,
    userIdDocValues: NumericDocValues,
    visibilityDocValues: NumericDocValues) {

  private[this] val published = LibraryFields.Visibility.PUBLISHED

  def apply(docId: Int, libId: Long): Int = {
    if (memberLibraryIds.findIndex(libId) >= 0) {
      if (myOwnLibraryIds.findIndex(libId) >= 0) {
        Visibility.OWNER // the keep is in my library (I may or may not have kept it)
      } else {
        val keeperId = userIdDocValues.get(docId)
        if (keeperId == userId) {
          Visibility.OWNER // the keep in a library I am a member of, and I kept it
        } else {
          Visibility.MEMBER // the keep is in a library I am a member of
        }
      }
    } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
      Visibility.MEMBER // the keep is in an authorized library
    } else {
      if (visibilityDocValues.get(docId) == published) {
        val keeperId = userIdDocValues.get(docId)
        if (myFriendIds.findIndex(keeperId) >= 0) {
          Visibility.NETWORK // the keep is in a published library, and my friend kept it
        } else if (trustedLibraryIds.findIndex(libId) >= 0) {
          Visibility.OTHERS // the keep is in a published library, and it is in a trusted library
        } else if (restrictedUserIds.findIndex(keeperId) >= 0) {
          Visibility.RESTRICTED // explicitly restricted user (e.g. fake user for non-admins)
        } else {
          Visibility.RESTRICTED // currently not searching published keeps by others
        }
      } else {
        Visibility.RESTRICTED
      }
    }
  }
}

final class LibraryVisibilityEvaluator(
    myOwnLibraryIds: LongArraySet,
    memberLibraryIds: LongArraySet,
    myFriendIds: LongArraySet,
    restrictedUserIds: LongArraySet,
    ownerIdDocValues: NumericDocValues,
    visibilityDocValues: NumericDocValues) {

  private[this] val published = LibraryFields.Visibility.PUBLISHED

  def apply(docId: Int, libId: Long): Int = {
    if (memberLibraryIds.findIndex(libId) >= 0) {
      if (myOwnLibraryIds.findIndex(libId) >= 0) {
        Visibility.OWNER // my library
      } else {
        Visibility.MEMBER // a library I am a member of
      }
    } else {
      if (visibilityDocValues.get(docId) == published) {
        val ownerId = ownerIdDocValues.get(docId)
        if (myFriendIds.findIndex(ownerId) >= 0) {
          Visibility.NETWORK // a published library owned by my friend
        } else if (restrictedUserIds.findIndex(ownerId) >= 0) {
          Visibility.RESTRICTED // explicitly restricted user (e.g. fake user for non-admins)
        } else {
          Visibility.OTHERS // another published library
        }
      } else {
        Visibility.RESTRICTED
      }
    }
  }
}

final class UserVisibilityEvaluator(
    myUserId: Long,
    myFriendIds: LongArraySet,
    restrictedUserIds: LongArraySet) {

  def apply(profileOwnerId: Long): Int = {
    if (profileOwnerId == myUserId) {
      Visibility.OWNER // myself
    } else if (myFriendIds.findIndex(profileOwnerId) >= 0) {
      Visibility.NETWORK // a friend
    } else if (restrictedUserIds.findIndex(profileOwnerId) >= 0)
      Visibility.RESTRICTED // explicitly restricted user (e.g. fake user for non-admins)
    else {
      Visibility.OTHERS // someone else
    }
  }
}
