package com.keepit.model

import com.keepit.common.db.{ States, Model, State, Id }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

// Copied wholesale from LibraryImageRequest
case class OrganizationAvatarRequest(
    id: Option[Id[OrganizationAvatarRequest]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationAvatarRequest] = OrganizationAvatarRequestStates.ACTIVE,
    libraryId: Id[Organization],
    token: String = RandomStringUtils.randomAlphanumeric(12),
    failureCode: Option[String] = None,
    failureReason: Option[String] = None,
    successHash: Option[ImageHash] = None,
    source: ImageSource) extends Model[OrganizationAvatarRequest] {
  def withId(id: Id[OrganizationAvatarRequest]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
}

object OrganizationAvatarRequestStates extends States[OrganizationAvatarRequest] {
  // ACTIVE means we haven't started processing yet. This often means it's incomplete — i.e., we're waiting on a URL or a file
  // INACTIVE means request is completed successfully

  // In progress states
  val FETCHING = State[OrganizationAvatarRequest]("fetching") // Fetching image in progress
  val PROCESSING = State[OrganizationAvatarRequest]("processing") // Decoding the image, including resizing
  val PERSISTING = State[OrganizationAvatarRequest]("persisting") // Uploading image (with all sizes) to S3

  // Failure states
  val UPSTREAM_FAILED = State[OrganizationAvatarRequest]("upstream_failed") // Upstream provider failed to provide image. Typically, simply did not have an image.
  val HEAD_FAILED = State[OrganizationAvatarRequest]("head_failed") // HEAD request checking if remote image exists failed
  val FETCHING_FAILED = State[OrganizationAvatarRequest]("fetching_failed") // Could not fetch image
  val PROCESSING_FAILED = State[OrganizationAvatarRequest]("processing_failed") // Could not decode or resize image
  val PERSISTING_FAILED = State[OrganizationAvatarRequest]("persisting_failed") // Could not upload images to S3
}
