package com.keepit.model

import com.keepit.common.db.{ States, State, Model, Id }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.time.Clock

case class KeepImageRequest(
    id: Option[Id[KeepImageRequest]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[KeepImageRequest] = KeepImageRequestStates.ACTIVE,
    keepId: Id[Keep],
    token: String = RandomStringUtils.randomAlphanumeric(12),
    failureCode: Option[String] = None,
    failureReason: Option[String] = None,
    successHash: Option[ImageHash] = None,
    imageUri: Option[String] = None,
    source: ImageSource) extends Model[KeepImageRequest] {
  def withId(id: Id[KeepImageRequest]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

@ImplementedBy(classOf[KeepImageRequestRepoImpl])
trait KeepImageRequestRepo extends Repo[KeepImageRequest] {
  def getByToken(token: String)(implicit session: RSession): Option[KeepImageRequest]
  def updateState(id: Id[KeepImageRequest], state: State[KeepImageRequest])(implicit session: RWSession): Unit
}

@Singleton
class KeepImageRequestRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[KeepImageRequest] with KeepImageRequestRepo {

  import db.Driver.simple._

  type RepoImpl = KeepImageRequestTable
  class KeepImageRequestTable(tag: Tag) extends RepoTable[KeepImageRequest](db, tag, "keep_image_request") {

    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def failureCode = column[String]("failure_code", O.Nullable)
    def failureReason = column[String]("failure_reason", O.Nullable)
    def successHash = column[ImageHash]("success_hash", O.Nullable)
    def imageUri = column[String]("image_uri", O.Nullable)
    def source = column[ImageSource]("source", O.NotNull)

    def idxSourceFileHashSize = index("keep_image_request_u_token", token, unique = true)

    def * = (id.?, createdAt, updatedAt, state, keepId, token, failureCode.?, failureReason.?, successHash.?, imageUri.?, source) <> ((KeepImageRequest.apply _).tupled, KeepImageRequest.unapply _)
  }

  def table(tag: Tag) = new KeepImageRequestTable(tag)
  initTable()

  override def invalidateCache(model: KeepImageRequest)(implicit session: RSession): Unit = {}

  override def deleteCache(model: KeepImageRequest)(implicit session: RSession): Unit = {}

  private val getByTokenCompiled = Compiled { token: Column[String] =>
    for (r <- rows if r.token === token) yield r
  }
  def getByToken(token: String)(implicit session: RSession): Option[KeepImageRequest] = {
    getByTokenCompiled(token).firstOption
  }

  private val getIdAndStateCompiled = Compiled { id: Column[Id[KeepImageRequest]] =>
    for (r <- rows if r.id === id) yield (r.id, r.state)
  }
  def updateState(id: Id[KeepImageRequest], state: State[KeepImageRequest])(implicit session: RWSession): Unit = {
    getIdAndStateCompiled(id).update((id, state))
  }

}

object KeepImageRequestStates extends States[KeepImageRequest] {
  // ACTIVE means we haven't started processing yet. This often means it's incomplete — i.e., we're waiting on a URL or a file
  // INACTIVE means request is completed successfully

  // In progress states
  val FETCHING = State[KeepImageRequest]("fetching") // Fetching image in progress
  val PROCESSING = State[KeepImageRequest]("processing") // Decoding the image, including resizing
  val PERSISTING = State[KeepImageRequest]("persisting") // Uploading image (with all sizes) to S3

  // Failure states
  val UPSTREAM_FAILED = State[KeepImageRequest]("upstream_failed") // Upstream provider failed to provide image. Typically, simply did not have an image.
  val HEAD_FAILED = State[KeepImageRequest]("head_failed") // HEAD request checking if remote image exists failed
  val FETCHING_FAILED = State[KeepImageRequest]("fetching_failed") // Could not fetch image
  val PROCESSING_FAILED = State[KeepImageRequest]("processing_failed") // Could not decode or resize image
  val PERSISTING_FAILED = State[KeepImageRequest]("persisting_failed") // Could not upload images to S3
}
