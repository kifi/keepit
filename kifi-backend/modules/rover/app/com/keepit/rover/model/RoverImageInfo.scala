package com.keepit.rover.model

import com.keepit.common.db._
import com.keepit.common.store.ImagePath
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime

object RoverImageInfoStates extends States[RoverImageInfo]

case class RoverImageInfo(
    id: Option[Id[RoverImageInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverImageInfo] = RoverImageInfoStates.ACTIVE,
    format: ImageFormat,
    width: Int,
    height: Int,
    kind: ProcessImageOperation,
    path: ImagePath,
    source: ImageSource,
    sourceImageHash: ImageHash,
    sourceImageUrl: Option[String]) extends BaseImage with Model[RoverImageInfo] {
  def withId(id: Id[RoverImageInfo]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def isOriginal = (kind == ProcessImageOperation.Original)
  def sourceFileHash = sourceImageHash
  def imagePath = path
  def isActive = (state == RoverImageInfoStates.ACTIVE)
}

object RoverImageInfo {
  def applyFromDbRow(
    id: Option[Id[RoverImageInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverImageInfo] = RoverImageInfoStates.ACTIVE,
    format: ImageFormat,
    width: Int,
    height: Int,
    kind: ProcessImageOperation,
    path: ImagePath,
    source: ImageSource,
    sourceImageHash: ImageHash,
    sourceImageUrl: Option[String]): RoverImageInfo = {
    RoverImageInfo(id, createdAt, updatedAt, state, format, width, height, kind, path, source, sourceImageHash, sourceImageUrl)
  }

  def unapplyToDbRow(info: RoverImageInfo) = {
    Some(
      info.id,
      info.createdAt,
      info.updatedAt,
      info.state,
      info.format,
      info.width,
      info.height,
      info.kind,
      info.path,
      info.source,
      info.sourceImageHash,
      info.sourceImageUrl
    )
  }
}
