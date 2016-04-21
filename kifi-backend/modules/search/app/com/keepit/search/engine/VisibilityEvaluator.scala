package com.keepit.search.engine

import java.nio.LongBuffer

import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.{ SearchFilter, SearchContext }
import com.keepit.search.index.{ LongBufferUtil, DocUtil, WrappedSubReader }
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.{ BinaryDocValues, NumericDocValues }
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

  lazy val (myOwnLibraryIds, memberLibraryIds, trustedLibraryIds, authorizedLibraryIds, myLibraryIds) = {
    val (myLibIds, memberLibIds, trustedLibIds, authorizedLibIds) = monitoredAwait.result(libraryIdsFuture, 5 seconds, s"getting library ids")

    require(myLibIds.forall { libId => memberLibIds.contains(libId) }) // sanity check

    (LongArraySet.fromSet(myLibIds), LongArraySet.fromSet(memberLibIds), LongArraySet.fromSet(trustedLibIds), LongArraySet.fromSet(authorizedLibIds), LongArraySet.fromSet(memberLibIds ++ authorizedLibIds))
  }

  lazy val myOrgIds = LongArraySet.fromSet(monitoredAwait.result(orgIdsFuture, 5 seconds, s"getting org ids"))

  protected def getKeepVisibilityEvaluator(reader: WrappedSubReader): KeepVisibilityEvaluator = {
    KeepVisibilityEvaluator(
      userId,
      myFriendIds,
      restrictedUserIds,
      myLibraryIds,
      myOrgIds,
      context.filter
    )(reader)
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
      myOrgIds,
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
    myLibraryIds: LongArraySet,
    myOrgIds: LongArraySet,
    filter: SearchFilter,
    ownerIdDocValues: NumericDocValues,
    userIdsDocValues: BinaryDocValues,
    libraryIdsDocValues: BinaryDocValues,
    orgIdsDocValues: BinaryDocValues,
    orgIdsDiscoverableDocValues: BinaryDocValues,
    userIdDiscoverableDocValues: BinaryDocValues,
    publishedDocValues: NumericDocValues) {

  import LongBufferUtil._

  private val noFilter = (filter.libraryIds.isEmpty) && (filter.userId < 0) && (filter.orgId < 0) // optimization

  @inline private def keeperId(docId: Int) = ownerIdDocValues.get(docId)
  @inline private def keepUserIds(docId: Int) = DocUtil.toLongBuffer(userIdsDocValues.get(docId))
  @inline private def keepLibraryIds(docId: Int) = DocUtil.toLongBuffer(libraryIdsDocValues.get(docId))
  @inline private def keepOrgIds(docId: Int) = DocUtil.toLongBuffer(orgIdsDocValues.get(docId))
  @inline private def keepDiscoverableOrgIds(docId: Int) = DocUtil.toLongBuffer(orgIdsDiscoverableDocValues.get(docId))
  @inline private def keepDiscoverableUserIds(docId: Int) = DocUtil.toLongBuffer(userIdDiscoverableDocValues.get(docId))
  @inline private def isPublishedKeep(docId: Int) = publishedDocValues.get(docId) > 0
  @inline private def isRestrictedKeeper(docId: Int) = restrictedUserIds.findIndex(keeperId(docId)) >= 0

  @inline private def isRelevant(keeperId: Long, libraryIds: LongBuffer, orgIds: LongBuffer): Boolean = {
    (filter.userId < 0 || filter.userId == keeperId) && (filter.libraryIds.isEmpty || intersect(libraryIds)(filter.libraryIds)) && (filter.orgId < 0 || contains(orgIds)(filter.orgId))
  }

  @inline def isRelevant(docId: Int): Boolean = noFilter || isRelevant(keeperId(docId), keepLibraryIds(docId), keepOrgIds(docId))

  def apply(docId: Int): Int = {
    if (noFilter || isRelevant(docId)) {
      if (keeperId(docId) == userId) Visibility.OWNER // I own that keep
      else if (contains(keepUserIds(docId))(userId)) Visibility.MEMBER // I am a member of that keep
      else if (intersect(keepLibraryIds(docId))(myLibraryIds)) Visibility.MEMBER // the keep is in a library I am a member of / I am authorized in
      else if (intersect(keepDiscoverableOrgIds(docId))(myOrgIds)) Visibility.NETWORK // the keep in one of my organizations' visible libraries
      else if (intersect(keepDiscoverableUserIds(docId))(myFriendIds)) Visibility.NETWORK // the keep in one of my friends' visible libraries
      else if (isPublishedKeep(docId) && !isRestrictedKeeper(docId)) Visibility.OTHERS // the keep is in a published library, from a non explicitly restricted user (e.g. fake user for non-admins) - we're not checking for trusted libraries at the moment
      else Visibility.RESTRICTED
    } else Visibility.RESTRICTED
  }
}

object KeepVisibilityEvaluator {
  def apply(
    userId: Long,
    myFriendIds: LongArraySet,
    restrictedUserIds: LongArraySet,
    myLibraryIds: LongArraySet,
    myOrgIds: LongArraySet,
    filter: SearchFilter)(reader: WrappedSubReader): KeepVisibilityEvaluator = {
    val ownerIdDocValues: NumericDocValues = reader.getNumericDocValues(KeepFields.ownerIdField)
    val userIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.userIdsField)
    val libraryIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.libraryIdsField)
    val orgIdsDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.orgIdsField)
    val orgIdsDiscoverableDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.orgIdsDiscoverableField)
    val userIdDiscoverableDocValues: BinaryDocValues = reader.getBinaryDocValues(KeepFields.userIdsDiscoverableField)
    val publishedDocValues: NumericDocValues = reader.getNumericDocValues(KeepFields.published)

    new KeepVisibilityEvaluator(
      userId,
      myFriendIds,
      restrictedUserIds,
      myLibraryIds,
      myOrgIds,
      filter,
      ownerIdDocValues,
      userIdsDocValues,
      libraryIdsDocValues,
      orgIdsDocValues,
      orgIdsDiscoverableDocValues,
      userIdDiscoverableDocValues,
      publishedDocValues
    )
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
        if (orgIds.findIndex(orgId) >= 0) {
          if (visibility == organization || visibility == published) Visibility.MEMBER // library is owned by an org that I am a member of
          else Visibility.RESTRICTED
        } else if (visibility == published) {
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