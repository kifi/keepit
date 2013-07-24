package com.keepit.graph.model

import com.keepit.common.db.{Id, State}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.model.UserConnection

case class FollowsEdge(source: VertexId[UserData], destination: VertexId[UserData], data: FollowsData)
  extends Edge[UserData, UserData, FollowsData]

case class FollowsData(id: Id[UserConnection], state: State[UserConnection]) extends EdgeData

object FollowsData {
  def apply(userConnection: UserConnection): FollowsData = FollowsData(userConnection.id.get, userConnection.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[UserConnection]) and
    (__ \ 'state).format(State.format[UserConnection])
    )(FollowsData.apply, unlift(FollowsData.unapply))
}
