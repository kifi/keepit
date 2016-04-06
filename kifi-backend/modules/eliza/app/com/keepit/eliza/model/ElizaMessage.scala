package com.keepit.eliza.model

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.json._
import com.keepit.discussion.{ MessageSource, CrossServiceMessage, Message }
import com.keepit.notify.model.Recipient
import com.keepit.social.{ BasicUserLikeEntity, BasicUser }
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ KeepRecipients, KeepEventData, BasicLibrary, Library, KeepEventData$, Keep, User, NormalizedURI }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, Key }
import scala.concurrent.duration.Duration
import play.api.libs.json._
import play.api.libs.functional.syntax._

sealed trait MessageSender {
  def isSystem: Boolean = false
  def asUser: Option[Id[User]] = None
  def asNonUser: Option[NonUserParticipant] = None

  def fold[T](systemF: => T, userF: Id[User] => T, nonUserF: NonUserParticipant => T): T = {
    asUser.map(userF).
      orElse { asNonUser.map(nup => nonUserF(nup)) }.
      getOrElse { systemF }
  }

  def asRecipient: Option[Recipient] =
    if (isSystem) None
    else asUser.map(userId => Recipient.fromUser(userId)) orElse asNonUser.collect {
      case NonUserEmailParticipant(email) => Recipient.fromEmail(email)
    }
}

object MessageSender {
  implicit val format: Format[MessageSender] = new Format[MessageSender] {

    def reads(json: JsValue) = {
      val kind: String = (json \ "kind").as[String]
      kind match {
        case "user" => JsSuccess(User(Id[com.keepit.model.User]((json \ "id").as[Long])))
        case "nonUser" => JsSuccess(NonUser((json \ "nup").as[NonUserParticipant]))
        case "system" => JsSuccess(System)
        case _ => JsError(kind)
      }
    }

    def writes(obj: MessageSender) = obj match {
      case User(id) => Json.obj(
        "kind" -> "user",
        "id" -> id.id
      )
      case NonUser(nup) => Json.obj(
        "kind" -> "nonUser",
        "nup" -> Json.toJson(nup)
      )
      case System => Json.obj(
        "kind" -> "system"
      )
    }

  }

  case class User(id: Id[com.keepit.model.User]) extends MessageSender {
    override def asUser = Some(id)
  }

  case class NonUser(nup: NonUserParticipant) extends MessageSender {
    override def asNonUser = Some(nup)
  }

  case object System extends MessageSender {
    override def isSystem: Boolean = true
  }

  def toMessageSenderView(messageSender: MessageSender): MessageSenderView =
    messageSender.fold(MessageSenderSystemView(), MessageSenderUserView, nup => MessageSenderNonUserView(nup.identifier))
}

sealed abstract class SystemMessageData(val kind: String)
object SystemMessageData {
  val internalFormat = new Format[SystemMessageData] {
    def reads(js: JsValue): JsResult[SystemMessageData] = {
      js match {
        case JsArray(Seq(JsString(AddParticipants.kind), _*)) => AddParticipants.internalFormat.reads(js)
        case JsArray(Seq(JsString(StartWithEmails.kind), _*)) => StartWithEmails.internalFormat.reads(js)
        case js if (js \ "kind").as[String] == AddLibraries.kind => AddLibraries.internalFormat.reads(js)
        case js if (js \ "kind").as[String] == EditTitle.kind => EditTitle.internalFormat.reads(js)
        case _ => JsError("can't parse SystemMessageData")
      }
    }
    def writes(o: SystemMessageData): JsValue = {
      o match {
        case p: AddParticipants => AddParticipants.internalFormat.writes(p)
        case s: StartWithEmails => StartWithEmails.internalFormat.writes(s)
        case l: AddLibraries => AddLibraries.internalFormat.writes(l)
        case t: EditTitle => EditTitle.internalFormat.writes(t)
      }
    }
  }

  def toKeepEvent(data: SystemMessageData): Option[KeepEventData] = data match {
    case StartWithEmails(addedBy, addedUsers, addedNonUsers) => None
    case AddParticipants(addedBy, addedUsers, addedNonUsers) =>
      val emails = addedNonUsers.collect {
        case NonUserEmailParticipant(email) => email
      }.toSet
      Some(KeepEventData.AddRecipients(addedBy, KeepRecipients(libraries = Set.empty, emails, addedUsers.toSet)))
    case AddLibraries(addedBy, addedLibraries) =>
      Some(KeepEventData.AddRecipients(addedBy, KeepRecipients(addedLibraries, Set.empty, Set.empty)))
    case EditTitle(editedBy, original, updated) =>
      Some(KeepEventData.EditTitle(editedBy, original, updated))
  }

  def fromKeepEvent(event: KeepEventData.EditTitle): Option[SystemMessageData] = {
    val KeepEventData.EditTitle(editedBy, original, updated) = event
    Some(EditTitle(editedBy, original, updated))
  }

  def isFullySupported(data: SystemMessageData): Boolean = data match {
    case _: AddParticipants | _: StartWithEmails => true
    case _ => false
  }

  def generateMessageText(data: SystemMessageData, basicUserById: Map[Id[User], BasicUser]): String = data match {
    case AddParticipants(addedById, addedUserIds, addedNonUsers) => generateParticipantsAddedText(AddParticipants.kind, addedById, addedUserIds, addedNonUsers, basicUserById)
    case StartWithEmails(addedById, addedUserIds, addedNonUsers) => generateParticipantsAddedText(StartWithEmails.kind, addedById, addedUserIds, addedNonUsers, basicUserById)
    case _ => "" // maybe want to static airbrake here
  }

  private def generateParticipantsAddedText(kind: String, addedById: Id[User], addedUserIds: Seq[Id[User]], addedNonUsers: Seq[NonUserParticipant], basicUserById: Map[Id[User], BasicUser]): String = {
    val addedBy = basicUserById.get(addedById)
    val addedUsers = addedUserIds.flatMap(basicUserById.get)
    val addedUsersString = (addedUsers.map(_.fullName) ++ addedNonUsers.map(_.shortName)).toList match {
      case first :: Nil => first
      case first :: second :: Nil => first + " and " + second
      case many => many.take(many.length - 1).mkString(", ") + ", and " + many.last
    }
    val actionText = if (kind == StartWithEmails.kind) "started a discussion with" else "added"
    addedBy.map(_.fullName) match {
      case Some(name) => s"$name $actionText $addedUsersString"
      case None => s"$addedUsersString joined this discussion."
    }
  }

  def publish(data: SystemMessageData, basicUserById: Map[Id[User], BasicUser]): JsArray = data match {
    case AddParticipants(addedById, addedUserIds, addedNonUsers) => Json.arr(AddParticipants.kind, basicUserById.get(addedById), addedUserIds.flatMap(basicUserById.get(_).map(BasicUserLikeEntity(_))) ++ addedNonUsers.map(nup => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup))))
    case StartWithEmails(addedById, addedUserIds, addedNonUsers) => Json.arr(StartWithEmails.kind, basicUserById.get(addedById), addedUserIds.flatMap(basicUserById.get(_).map(BasicUserLikeEntity(_))) ++ addedNonUsers.map(nup => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup))))
    case _ => Json.arr()
  }

  case class AddParticipants(addedBy: Id[User], addedUsers: Seq[Id[User]], addedNonUsers: Seq[NonUserParticipant]) extends SystemMessageData(AddParticipants.kind)
  object AddParticipants {
    val kind = "add_participants"
    val internalFormat = new Format[AddParticipants] {
      def writes(o: AddParticipants): JsArray = Json.arr(kind, o.addedBy.id.toString, o.addedUsers.map(Json.toJson(_)) ++ o.addedNonUsers.map(Json.toJson(_)))

      def reads(js: JsValue): JsResult[AddParticipants] = {
        js.asOpt[JsArray].map(_.value) match {
          case Some(Seq(JsString(AddParticipants.kind), JsString(addedBy), jsAdded: JsArray)) =>
            implicit val eitherReads = EitherFormat(Id.format[User], NonUserParticipant.format)
            jsAdded.asOpt[Seq[Either[Id[User], NonUserParticipant]]] match {
              case None => JsError("can't parse AddParticipants.added")
              case Some(added) =>
                val (users, nonUsers) = (added.collect { case user if user.isLeft => user.left.get }, added.collect { case nonUser if nonUser.isRight => nonUser.right.get })
                JsSuccess(AddParticipants(Id[User](addedBy.toLong), users, nonUsers))
            }
          case _ => JsError("can't parse AddParticipants")
        }
      }
    }
  }

  case class StartWithEmails(addedBy: Id[User], addedUsers: Seq[Id[User]], addedNonUsers: Seq[NonUserParticipant]) extends SystemMessageData(StartWithEmails.kind)
  object StartWithEmails {
    val kind = "start_with_emails"
    val internalFormat = new Format[StartWithEmails] {
      def writes(o: StartWithEmails): JsArray = Json.arr(kind, o.addedBy.id.toString, o.addedUsers.map(Json.toJson(_)) ++ o.addedNonUsers.map(Json.toJson(_)))

      def reads(js: JsValue): JsResult[StartWithEmails] = {
        js.asOpt[JsArray].map(_.value) match {
          case Some(Seq(JsString(StartWithEmails.kind), JsString(addedBy), jsAdded: JsArray)) =>
            implicit val eitherReads = EitherFormat(Id.format[User], NonUserParticipant.format)
            jsAdded.asOpt[Seq[Either[Id[User], NonUserParticipant]]] match {
              case None => JsError("can't parse StartWithEmails.added")
              case Some(added) =>
                val (users, nonUsers) = (added.collect { case user if user.isLeft => user.left.get }, added.collect { case nonUser if nonUser.isRight => nonUser.right.get })
                JsSuccess(StartWithEmails(Id[User](addedBy.toLong), users, nonUsers))
            }
          case _ => JsError("can't parse StartWithEmails")
        }
      }
    }
  }

  case class AddLibraries(addedBy: Id[User], libraries: Set[Id[Library]]) extends SystemMessageData(AddLibraries.kind)
  object AddLibraries {
    val kind = "add_libraries"
    val internalFormat: Format[AddLibraries] = Format(
      Reads { js => Json.reads[AddLibraries].reads(js) },
      Writes { o => Json.writes[AddLibraries].writes(o).as[JsObject] ++ Json.obj("kind" -> kind) }
    )
  }

  case class EditTitle(editedBy: Id[User], original: Option[String], updated: Option[String]) extends SystemMessageData(EditTitle.kind)
  object EditTitle {
    val kind = "edit_title"
    val internalFormat: Format[EditTitle] = Format(
      Reads { js => Json.reads[EditTitle].reads(js) },
      Writes { o => Json.writes[EditTitle].writes(o).as[JsObject] ++ Json.obj("kind" -> kind) }
    )
  }
}

case class ElizaMessage(
  id: Option[Id[ElizaMessage]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ElizaMessage] = ElizaMessageStates.ACTIVE,
  seq: SequenceNumber[ElizaMessage] = SequenceNumber.ZERO,
  keepId: Id[Keep],
  from: MessageSender,
  messageText: String,
  source: Option[MessageSource],
  auxData: Option[SystemMessageData] = None,
  sentOnUrl: Option[String],
  sentOnUriId: Option[Id[NormalizedURI]])
    extends Model[ElizaMessage] with ModelWithState[ElizaMessage] with ModelWithSeqNumber[ElizaMessage] {
  def withId(id: Id[ElizaMessage]): ElizaMessage = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def withText(newText: String) = this.copy(messageText = newText)
  def sanitizeForDelete = this.copy(state = ElizaMessageStates.INACTIVE)

  def pubId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Message] = Message.publicId(ElizaMessage.toCommonId(id.get))
  def pubKeepId(implicit publicIdConfig: PublicIdConfiguration): PublicId[Keep] = Keep.publicId(keepId)

  def isActive: Boolean = state == ElizaMessageStates.ACTIVE
}
object ElizaMessageStates extends States[ElizaMessage]

object ElizaMessage extends CommonClassLinker[ElizaMessage, Message] {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ElizaMessage]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[ElizaMessage]] and
    (__ \ 'seq).format[SequenceNumber[ElizaMessage]] and
    (__ \ 'keepId).format[Id[Keep]] and
    (__ \ 'from).format[MessageSender] and
    (__ \ 'messageText).format[String] and
    (__ \ 'source).formatNullable[MessageSource] and
    (__ \ 'auxData).formatNullable(SystemMessageData.internalFormat) and
    (__ \ 'sentOnUrl).formatNullable[String] and
    (__ \ 'sentOnUriId).formatNullable(Id.format[NormalizedURI])
  )(ElizaMessage.apply, unlift(ElizaMessage.unapply))

  def fromDbRow(
    id: Option[Id[ElizaMessage]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[ElizaMessage],
    seq: SequenceNumber[ElizaMessage],
    keepId: Id[Keep],
    userSender: Option[Id[User]],
    messageText: String,
    source: Option[MessageSource],
    auxData: Option[SystemMessageData],
    sentOnUrl: Option[String],
    sentOnUriId: Option[Id[NormalizedURI]],
    nonUserSender: Option[JsValue]): ElizaMessage = {
    ElizaMessage(
      id,
      createdAt,
      updatedAt,
      state,
      seq,
      keepId,
      userSender.map(MessageSender.User(_)).getOrElse(nonUserSender.map(json => MessageSender.NonUser(json.as[NonUserParticipant])).getOrElse(MessageSender.System)),
      messageText,
      source,
      auxData,
      sentOnUrl,
      sentOnUriId
    )
  }

  def toDbRow(message: ElizaMessage): Option[(Option[Id[ElizaMessage]], DateTime, DateTime, State[ElizaMessage], SequenceNumber[ElizaMessage], Id[Keep], Option[Id[User]], String, Option[MessageSource], Option[SystemMessageData], Option[String], Option[Id[NormalizedURI]], Option[JsValue])] = {
    Some((
      message.id,
      message.createdAt,
      message.updatedAt,
      message.state,
      message.seq,
      message.keepId,
      message.from.asUser,
      message.messageText,
      message.source,
      message.auxData,
      message.sentOnUrl,
      message.sentOnUriId,
      message.from.asNonUser.map(Json.toJson(_))
    ))
  }

  def toMessageView(message: ElizaMessage): MessageView = {
    MessageView(
      from = MessageSender.toMessageSenderView(message.from),
      messageText = message.messageText,
      createdAt = message.createdAt)
  }

  def toCrossServiceMessage(message: ElizaMessage): CrossServiceMessage = {
    CrossServiceMessage(
      id = ElizaMessage.toCommonId(message.id.get),
      isDeleted = !message.isActive,
      seq = ElizaMessage.toCommonSeq(message.seq),
      keep = message.keepId,
      sentAt = message.createdAt,
      sentBy = message.from.fold(None, userId => Some(Left(userId)), nup => Some(Right(NonUserParticipant.toBasicNonUser(nup)))),
      text = message.messageText,
      auxData = message.auxData.flatMap(SystemMessageData.toKeepEvent),
      source = message.source
    )
  }
}

case class MessagesKeepIdKey(keepId: Id[Keep]) extends Key[Seq[ElizaMessage]] {
  override val version = 2
  val namespace = "messages_by_keep_id"
  def toKey(): String = keepId.id.toString
}

class MessagesByKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[MessagesKeepIdKey, Seq[ElizaMessage]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

