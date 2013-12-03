package com.keepit.common.usersegment

import play.api.libs.json._

case class UserSegment(
  val value: Int,
  val description: String
)

object UserSegment{
  implicit def format = Json.format[UserSegment]
}

object UserSegmentFactory{
  def apply(numKeeps: Int, numFriends: Int): UserSegment = {
    if (numKeeps > 50){
      if (numFriends > 10) UserSegment(0, "many_friends_and_keeps") else UserSegment(1, "few_friends_and_many_keeps")
    } else {
      if (numFriends > 10) UserSegment(2, "many_friends_and_few_keeps") else UserSegment(3, "few_friends_and_few_keeps")
    }
  }
}
