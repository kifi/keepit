package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.SearchContext
import com.keepit.search.index.{ WrappedSubReader }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.{ NumericDocValues }
import scala.concurrent.duration._
import scala.concurrent.Future

trait VisibilityEvaluator { self: DebugOption =>

  protected val userId: Long
  protected val friendIdsFuture: Future[Set[Long]]
  protected val restrictedUserIdsFuture: Future[Set[Long]]
  protected val libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])]
  protected val orgIdsFuture: Future[Set[Long]]
  protected val monitoredAwait: MonitoredAwait
  protected val context: SearchContext

  lazy val myFriendIds = LongArraySet.fromSet(monitoredAwait.result(friendIdsFuture, 5 seconds, s"getting friend ids"))

  lazy val restrictedUserIds = LongArraySet.fromSet(monitoredAwait.result(restrictedUserIdsFuture, 5 seconds, s"getting restricted user ids"))

  lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds))
  }

  lazy val orgIds = LongArraySet.fromSet(monitoredAwait.result(orgIdsFuture, 5 seconds, s"getting org ids"))

  protected def getKeepVisibilityEvaluator(reader: WrappedSubReader): KeepVisibilityEvaluator = {

    val userIdDocValues = reader.getNumericDocValues(KeepFields.userIdField)
    val visibilityDocValues = reader.getNumericDocValues(KeepFields.visibilityField)
    val orgIdDocValues = reader.getNumericDocValues(KeepFields.orgIdField)
    val libraryIdDocValues = reader.getNumericDocValues(KeepFields.libraryIdField)

    new KeepVisibilityEvaluator(
      userId,
      myFriendIds,
      restrictedUserIds,
      myOwnLibraryIds,
      memberLibraryIds,
      trustedLibraryIds,
      authorizedLibraryIds,
      orgIds,
      context,
      userIdDocValues,
      libraryIdDocValues,
      orgIdDocValues,
      visibilityDocValues)
  }

  protected def getLibraryVisibilityEvaluator(reader: WrappedSubReader): LibraryVisibilityEvaluator = {
    val visibilityDocValues = reader.getNumericDocValues(LibraryFields.visibilityField)
    val ownerIdDocValues = reader.getNumericDocValues(LibraryFields.ownerIdField)
    val orgIdDocValues = reader.getNumericDocValues(LibraryFields.orgIdField)
    new LibraryVisibilityEvaluator(
      myOwnLibraryIds,
      memberLibraryIds,
      myFriendIds,
      restrictedUserIds,
      orgIds,
      context,
      ownerIdDocValues,
      orgIdDocValues,
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
    orgIds: LongArraySet,
    context: SearchContext,
    val userIdDocValues: NumericDocValues,
    val libraryIdDocValues: NumericDocValues,
    val orgIdDocValues: NumericDocValues,
    visibilityDocValues: NumericDocValues) {

  private[this] val published = LibraryFields.Visibility.PUBLISHED
  private[this] val organization = LibraryFields.Visibility.ORGANIZATION
  private val noFilter = (context.filter.libraryIds.isEmpty) && (context.filter.userId < 0) && (context.filter.orgId < 0) // optimization

  @inline
  private def isRelevant(libId: Long, keeperId: Long, orgId: Long) = {
    (context.filter.libraryIds.isEmpty || context.filter.libraryIds.findIndex(libId) >= 0) && (context.filter.userId < 0 || context.filter.userId == keeperId) && (context.filter.orgId < 0 || context.filter.orgId == orgId)
  }

  def isRelevant(docId: Int): Boolean = noFilter || {
    val libId = libraryIdDocValues.get(docId)
    val keeperId = userIdDocValues.get(docId)
    val orgId = orgIdDocValues.get(docId)
    isRelevant(libId, keeperId, orgId)
  }

  def apply(docId: Int): Int = {
    val libId = libraryIdDocValues.get(docId)
    val keeperId = userIdDocValues.get(docId)
    val orgId = orgIdDocValues.get(docId)

    if (noFilter || isRelevant(libId, keeperId, orgId)) {
      if (memberLibraryIds.findIndex(libId) >= 0) {
        if (myOwnLibraryIds.findIndex(libId) >= 0) {
          Visibility.OWNER // the keep is in my library (I may or may not have kept it)
        } else if (keeperId == userId) {
          Visibility.OWNER // the keep in a library I am a member of, and I kept it
        } else {
          Visibility.MEMBER // the keep is in a library I am a member of
        }
      } else if (authorizedLibraryIds.findIndex(libId) >= 0) {
        Visibility.MEMBER // the keep is in an authorized library
      } else {
        val visibility = visibilityDocValues.get(docId)
        if (orgIds.findIndex(orgIdDocValues.get(docId)) >= 0) {
          if (visibility == organization || visibility == published) Visibility.MEMBER // library is owned by an org that I am a member of
          else Visibility.RESTRICTED
        } else if (visibility == published) {
          if (myFriendIds.findIndex(keeperId) >= 0) {
            Visibility.NETWORK // the keep is in a published library, and my friend kept it
          } else if (trustedLibraryIds.findIndex(libId) >= 0) {
            Visibility.OTHERS // the keep is in a published library, and it is in a trusted library
          } else if (restrictedUserIds.findIndex(keeperId) >= 0) {
            Visibility.RESTRICTED // explicitly restricted user (e.g. fake user for non-admins)
          } else {
            Visibility.OTHERS // another published keep
          }
        } else {
          Visibility.RESTRICTED
        }
      }
    } else {
      Visibility.RESTRICTED
    }
  }
}

final class LibraryVisibilityEvaluator(
    myOwnLibraryIds: LongArraySet,
    memberLibraryIds: LongArraySet,
    myFriendIds: LongArraySet,
    restrictedUserIds: LongArraySet,
    orgIds: LongArraySet,
    context: SearchContext,
    ownerIdDocValues: NumericDocValues,
    orgIdDocValues: NumericDocValues,
    visibilityDocValues: NumericDocValues) {

  private[this] val published = LibraryFields.Visibility.PUBLISHED
  private[this] val organization = LibraryFields.Visibility.ORGANIZATION
  private val noFilter = (context.filter.libraryIds.isEmpty) && (context.filter.userId < 0) && (context.filter.orgId < 0) // optimization

  @inline
  private def isRelevant(libId: Long, ownerId: Long, orgId: Long) = {
    (context.filter.libraryIds.isEmpty || context.filter.libraryIds.findIndex(libId) >= 0) && (context.filter.userId < 0 || context.filter.userId == ownerId) && (context.filter.orgId < 0 || context.filter.orgId == orgId)
  }

  def isRelevant(docId: Int, libId: Long): Boolean = noFilter || {
    val ownerId = ownerIdDocValues.get(docId)
    val orgId = orgIdDocValues.get(docId)
    isRelevant(libId, ownerId, orgId)
  }

  def apply(docId: Int, libId: Long): Int = {
    val ownerId = ownerIdDocValues.get(docId)
    val orgId = orgIdDocValues.get(docId)
    if (noFilter || isRelevant(libId, ownerId, orgId)) {
      if (memberLibraryIds.findIndex(libId) >= 0) {
        if (myOwnLibraryIds.findIndex(libId) >= 0) {
          Visibility.OWNER // my library
        } else {
          Visibility.MEMBER // a library I am a member of
        }
      } else {
        val visibility = visibilityDocValues.get(docId)
        if (orgIds.findIndex(orgIdDocValues.get(docId)) >= 0) {
          if (visibility == organization || visibility == published) Visibility.MEMBER // library is owned by an org that I am a member of
          else Visibility.RESTRICTED
        } else if (visibility == published) {
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
    } else {
      Visibility.RESTRICTED
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

object ArticleVisibilityEvaluator {
  def apply() = new ArticleVisibilityEvaluator()
}

final class ArticleVisibilityEvaluator() {
  @inline
  def apply(doc: Int): Int = {
    Visibility.OTHERS // todo(Léo): we're checking for isDiscoverable further up in UriSearchImpl, for performance reasons. Keeping this structure in case we change that.
  }
}