package com.keepit.graph.model

import com.keepit.model.User
import play.api.libs.json._
import com.keepit.common.db.{Id, State}
import play.api.libs.functional.syntax._

case class UserVertex(id: VertexId[UserData], data: UserData) extends Vertex[UserData]

case class UserData(id: Id[User], state: State[User]) extends VertexData

object UserData {
  def apply(user: User): UserData = UserData(user.id.get, user.state)

  implicit val format = (
    (__ \ 'id).format(Id.format[User]) and
    (__ \ 'state).format(State.format[User])
    )(UserData.apply, unlift(UserData.unapply))

}
