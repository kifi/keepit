package com.keepit.maven.model

import com.keepit.common.db.{Model, Id, ModelWithState, State}
import com.keepit.model.{NormalizedURI, User, Keep}
import com.keepit.common.time._

import org.joda.time.DateTime


case class MavenKeepInfo(
    id: Option[Id[MavenKeepInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    uriId: Id[NormalizedURI],
    userId: Id[User],
    keepId: Id[Keep],
    isPrivate: Boolean,
    state: State[MavenKeepInfo]
  )
  extends Model[MavenKeepInfo] with ModelWithState[MavenKeepInfo]{

  def withId(id: Id[MavenKeepInfo]): MavenKeepInfo = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): MavenKeepInfo = this.copy(updateAt=updateTime)
}
