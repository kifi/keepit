package com.keepit.model

import com.keepit.common.db.{ States, Model, State, Id }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

case class LibraryImageRequest(
    id: Option[Id[LibraryImageRequest]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryImageRequest] = LibraryImageRequestStates.ACTIVE,
    libraryId: Id[Library],
    token: String = RandomStringUtils.randomAlphanumeric(12),
    failureCode: Option[String] = None,
    failureReason: Option[String] = None,
    successHash: Option[ImageHash] = None,
    source: BaseImageSource) extends Model[LibraryImageRequest] {
  def withId(id: Id[LibraryImageRequest]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object LibraryImageRequestStates extends States[LibraryImageRequest] {
  // ACTIVE means we haven't started processing yet. This often means it's incomplete — i.e., we're waiting on a URL or a file
  // INACTIVE means request is completed successfully

  // In progress states
  val FETCHING = State[LibraryImageRequest]("fetching") // Fetching image in progress
  val PROCESSING = State[LibraryImageRequest]("processing") // Decoding the image, including resizing
  val PERSISTING = State[LibraryImageRequest]("persisting") // Uploading image (with all sizes) to S3

  // Failure states
  val UPSTREAM_FAILED = State[LibraryImageRequest]("upstream_failed") // Upstream provider failed to provide image. Typically, simply did not have an image.
  val HEAD_FAILED = State[LibraryImageRequest]("head_failed") // HEAD request checking if remote image exists failed
  val FETCHING_FAILED = State[LibraryImageRequest]("fetching_failed") // Could not fetch image
  val PROCESSING_FAILED = State[LibraryImageRequest]("processing_failed") // Could not decode or resize image
  val PERSISTING_FAILED = State[LibraryImageRequest]("persisting_failed") // Could not upload images to S3
}
