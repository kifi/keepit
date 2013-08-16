package com.keepit.eliza

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.{StringMapperDelegate}
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import org.joda.time.DateTime
import com.keepit.common.time.{currentDateTime, zones, Clock}
import com.keepit.common.db.{ModelWithExternalId, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.time.{DateTimeJsonFormat}
import scala.slick.lifted.{BaseTypeMapper, TypeMapperDelegate}
import scala.slick.driver.{BasicProfile}
import scala.util.hashing.MurmurHash3
import MessagingTypeMappers._
import scala.concurrent.duration.Duration
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin}

class MessageThreadParticipants(val participants : Map[Id[User], DateTime]) {

  def contains(user: Id[User]) : Boolean = participants.contains(user)
  def allExcept(user: Id[User]) : Set[Id[User]] = participants.keySet - user
  
  lazy val all = participants.keySet
  lazy val hash : Int = MurmurHash3.setHash(participants.keySet)

  override def equals(other: Any) : Boolean = other match {
    case mtps: MessageThreadParticipants => super.equals(other) || mtps.participants.keySet == participants.keySet 
    case _ => false
  }

  override def hashCode = participants.keySet.hashCode

}

object MessageThreadParticipants{
  implicit val format = new Format[MessageThreadParticipants] {
    def reads(json: JsValue) = {
      json match {
        case obj: JsObject => {
          val mtps = MessageThreadParticipants(obj.fields.toMap.map {
            case (uid, timestamp) => (Id[User](uid.toLong), timestamp.as[DateTime])
          })
          JsSuccess(mtps)
        }  
        case _ => JsError()
      }
    }

    def writes(mtps: MessageThreadParticipants) : JsValue = {
      JsObject(
        mtps.participants.toSeq.map{
          case (uid, timestamp) => (uid.id.toString, Json.toJson(timestamp))
        }
      )
    }
  }

  def apply(initialParticipants: Set[Id[User]]) : MessageThreadParticipants = {
    new MessageThreadParticipants(initialParticipants.map{userId => (userId, currentDateTime(zones.PT))}.toMap)
  }

  def apply(participants: Map[Id[User], DateTime]) : MessageThreadParticipants = {
    new MessageThreadParticipants(participants)
  }

}


case class MessageThread(
    id: Option[Id[MessageThread]],
    createdAt: DateTime = currentDateTime(zones.PT), 
    updateAt: DateTime = currentDateTime(zones.PT), 
    externalId: ExternalId[MessageThread] = ExternalId(),
    uriId: Option[Id[NormalizedURI]],
    url: Option[String],
    nUrl: Option[String],
    pageTitle: Option[String],
    participants: Option[MessageThreadParticipants],
    participantsHash: Option[Int],
    replyable: Boolean
  ) 
  extends ModelWithExternalId[MessageThread] {

  def withId(id: Id[MessageThread]): MessageThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime) 

  def containsUser(user: Id[User]) : Boolean = participants.map(_.contains(user)).getOrElse(false)
  def allUsersExcept(user: Id[User]) : Set[Id[User]] = participants.map(_.allExcept(user)).getOrElse(Set[Id[User]]())
}



object MessageThread {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[MessageThread]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'externalId).format(ExternalId.format[MessageThread]) and
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'url).formatNullable[String] and
    (__ \ 'nUrl).formatNullable[String] and    
    (__ \ 'pageTitle).formatNullable[String] and  
    (__ \ 'participants).formatNullable[MessageThreadParticipants] and
    (__ \ 'participantsHash).formatNullable[Int] and
    (__ \ 'replyable).format[Boolean] 
  )(MessageThread.apply, unlift(MessageThread.unapply))
}


@ImplementedBy(classOf[MessageThreadRepoImpl])
trait MessageThreadRepo extends Repo[MessageThread] with ExternalIdColumnFunction[MessageThread] {

  def getOrCreate(participants: Set[Id[User]], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession) : (MessageThread, Boolean)

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread

  def updateNormalizedUris(updates: Map[Id[NormalizedURI], NormalizedURI])(implicit session: RWSession) : Unit
}


@Singleton
class MessageThreadRepoImpl @Inject() (
    val clock: Clock, 
    val db: DataBaseComponent,
    val threadExternalIdCache: MessageThreadExternalIdCache 
  ) 
  extends DbRepo[MessageThread] with MessageThreadRepo with ExternalIdColumnDbFunction[MessageThread] {

  override val table = new RepoTable[MessageThread](db, "message_thread") with ExternalIdColumn[MessageThread] {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def url = column[String]("url", O.Nullable)
    def nUrl = column[String]("nUrl", O.Nullable)
    def pageTitle = column[String]("page_title", O.Nullable)
    def participants = column[MessageThreadParticipants]("participants", O.Nullable)
    def participantsHash = column[Int]("participants_hash", O.Nullable)
    def replyable = column[Boolean]("replyable", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ uriId.? ~ url.? ~ nUrl.? ~ pageTitle.? ~ participants.? ~ participantsHash.? ~ replyable <> (MessageThread.apply _, MessageThread.unapply _)
  }

  import db.Driver.Implicit._

  override def invalidateCache(thread: MessageThread)(implicit session: RSession): MessageThread = {
    threadExternalIdCache.set(MessageThreadExternalIdKey(thread.externalId), thread)
    thread
  }

  def getOrCreate(participants: Set[Id[User]], urlOpt: Option[String], uriIdOpt: Option[Id[NormalizedURI]], nUriOpt: Option[String], pageTitleOpt: Option[String])(implicit session: RWSession) : (MessageThread, Boolean) = {
    //Note (stephen): This has a race condition: When two threads that would normally be merged are created at the exact same time two different conversations will be the result
    val mtps = MessageThreadParticipants(participants)
    val candidates : Seq[MessageThread]= (for (row <- table if row.participantsHash===mtps.hash && row.uriId===uriIdOpt) yield row).list.filter { thread =>
      thread.uriId.isDefined &&
      thread.participants.isDefined &&
      thread.participants.get == mtps
    }
    if (candidates.length>0) (candidates.head, false)
    else {
      val thread = MessageThread(
        id = None,
        uriId = uriIdOpt,
        url = urlOpt,
        nUrl = nUriOpt,
        pageTitle=pageTitleOpt,
        participants = Some(mtps),
        participantsHash = Some(mtps.hash),
        replyable = true
      )
      (super.save(thread), true)
    }

  }

  override def get(id: ExternalId[MessageThread])(implicit session: RSession) : MessageThread = {
    threadExternalIdCache.getOrElse(MessageThreadExternalIdKey(id))(super.get(id))
  }

  override def get(id: Id[MessageThread])(implicit session: RSession) : MessageThread = {
    super.get(id)
  }

  def updateNormalizedUris(updates: Map[Id[NormalizedURI], NormalizedURI])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newNUri) =>
      val updateIds = (for (row <- table if row.uriId===oldId) yield row.externalId).list //Note: race condition if there is an insert after this?
      (for (row <- table if row.uriId===oldId) yield (row.uriId ~ row.nUrl)).update((newNUri.id.get, newNUri.url))
      updateIds.foreach{ extId =>
        threadExternalIdCache.remove(MessageThreadExternalIdKey(extId))
      }
    }
  }

}



case class MessageThreadExternalIdKey(externalId: ExternalId[MessageThread]) extends Key[MessageThread] {
  override val version = 2
  val namespace = "message_thread_by_external_id"
  def toKey(): String = externalId.id
}

class MessageThreadExternalIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessageThreadExternalIdKey, MessageThread](innermostPluginSettings, innerToOuterPluginSettings:_*)

