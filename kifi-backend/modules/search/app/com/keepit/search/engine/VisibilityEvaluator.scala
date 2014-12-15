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
  protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])]
  protected val monitoredAwait: MonitoredAwait

  lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds))
  }

  protected def getKeepVisibilityEvaluator(userIdDocValues: NumericDocValues, visibilityDocValues: NumericDocValues): KeepVisibilityEvaluator = {
    new KeepVisibilityEvaluator(
      userId,
      myFriendIds,
      myOwnLibraryIds,
      memberLibraryIds,
      trustedLibraryIds,
      authorizedLibraryIds,
      userIdDocValues,
      visibilityDocValues)
  }

  protected def getLibraryVisibilityEvaluator(visibilityDocValues: NumericDocValues): LibraryVisibilityEvaluator = {
    new LibraryVisibilityEvaluator(
      myOwnLibraryIds,
      memberLibraryIds,
      authorizedLibraryIds,
      visibilityDocValues)
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
        if (userIdDocValues.get(docId) == userId) {
          Visibility.OWNER // the keep in a library I am a member of, and I kept it
        } else {
          Visibility.MEMBER // the keep is in a library I am a member of
        }
      }
    } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
      Visibility.MEMBER // the keep is in an authorized library
    } else {
      if (visibilityDocValues.get(docId) == published) {
        if (myFriendIds.findIndex(userIdDocValues.get(docId)) >= 0) {
          Visibility.NETWORK // the keep is in a published library, and my friend kept it
        } else if (trustedLibraryIds.findIndex(libId) >= 0) {
          Visibility.OTHERS // the keep is in a published library, and it is in a trusted library
        } else {
          Visibility.RESTRICTED
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
    authorizedLibraryIds: LongArraySet,
    visibilityDocValues: NumericDocValues) {

  private[this] val published = LibraryFields.Visibility.PUBLISHED

  def apply(docId: Int, libId: Long): Int = {
    if (memberLibraryIds.findIndex(libId) >= 0) {
      if (myOwnLibraryIds.findIndex(libId) >= 0) {
        Visibility.OWNER // my own library
      } else {
        Visibility.MEMBER // a library I am a member of
      }
    } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
      Visibility.MEMBER // the keep is in an authorized library
    } else {
      if (visibilityDocValues.get(docId) == published) {
        Visibility.OTHERS // a published library
      } else {
        Visibility.RESTRICTED
      }
    }
  }
}
