package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db.Id
import com.keepit.common.db.Model
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import java.io.{DataOutputStream, DataInputStream, ByteArrayInputStream, ByteArrayOutputStream}
import com.keepit.common.db.slick.FortyTwoTypeMappers.ByteArrayTypeMapper
import com.keepit.common.db.slick.FortyTwoTypeMappers.UserIdTypeMapper
import scala.annotation.elidable
import scala.annotation.elidable.ASSERTION
import com.keepit.learning.topicmodel.TopicModelGlobal

case class UserTopic(
  id: Option[Id[UserTopic]] = None,
  userId: Id[User],
  topic: Array[Byte],     // hold int array, histogram over topics
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime
) extends Model[UserTopic] {
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withId(id: Id[UserTopic]) = this.copy(id = Some(id))
}

class UserTopicByteArrayHelper {
  def toByteArray(arr: Array[Int]) = {
    assume(arr.length == TopicModelGlobal.numTopics, "topic array size not matching TopicModelGlobal.numTopics")
    val bs = new ByteArrayOutputStream(TopicModelGlobal.numTopics * 4)
    val os = new DataOutputStream(bs)
    arr.foreach{os.writeInt}
    os.close()
    val rv = bs.toByteArray()
    bs.close()
    rv
  }

  def toIntArray(arr: Array[Byte]) = {
    assume(arr.size == TopicModelGlobal.numTopics * 4, "topic array size not matching TopicModelGlobal.numTopics")
    val is = new DataInputStream(new ByteArrayInputStream(arr))
    val topic = (0 until TopicModelGlobal.numTopics).map{i => is.readInt()}
    is.close()
    topic.toArray
  }
}

class TopicByteArrayFormat extends Format[Array[Byte]] {
  val helper = new UserTopicByteArrayHelper
  def writes (x: Array[Byte]): JsValue = {
    JsArray(helper.toIntArray(x).map{JsNumber(_)})
  }
  def reads(js: JsValue): JsResult[Array[Byte]] = {
    val x = js.as[JsArray].value.map{_.as[JsNumber].value.toInt}
    JsSuccess(helper.toByteArray(x.toArray))
  }
}

object UserTopic {
  implicit val userTopicFormat = (
    (__ \'id).formatNullable(Id.format[UserTopic]) and
    (__ \'userId).format(Id.format[User]) and
    (__ \'topic).format(new TopicByteArrayFormat) and
    (__ \'createdAt).format[DateTime] and
    (__ \'updatedAt).format[DateTime]
  )(UserTopic.apply, unlift(UserTopic.unapply))
}


@ImplementedBy(classOf[UserTopicRepoImpl])
trait UserTopicRepo extends Repo[UserTopic]{
  def getByUserId(userId: Id[User])(implicit session: RSession):Option[UserTopic]
  def deleteAll()(implicit session: RWSession): Int
}

@Singleton
class UserTopicRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[UserTopic] with UserTopicRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UserTopic](db, "user_topic"){
    def userId = column[Id[User]]("user_id", O.NotNull)
    def topic = column[Array[Byte]]("topic", O.NotNull)
    def * = id.? ~ userId ~ topic ~ createdAt ~ updatedAt <> (UserTopic.apply _, UserTopic.unapply _)
  }

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[UserTopic] = {
    (for(r <- table if r.userId === userId) yield r).firstOption
  }

  def deleteAll()(implicit session: RWSession): Int = {
    (for(r <- table) yield r).delete
  }
}



