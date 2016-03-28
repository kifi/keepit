package com.keepit.commanders.gen

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ Ord, LinkElement, ImageElement, DescriptionElements, DescriptionElement }
import com.keepit.discussion.{ Message, CrossServiceKeepActivity }
import com.keepit.model.KeepEvent.AddParticipants
import com.keepit.model.{ BasicKeepEvent, KeepEventSource, KeepEventKind, KeepActivity, TwitterAttribution, SlackAttribution, BasicOrganization, BasicLibrary, Library, User, KeepToUser, KeepToLibrary, SourceAttribution, Keep }
import com.keepit.social.{ ImageUrls, BasicUser, BasicAuthor }
import org.joda.time.DateTime

object KeepActivityGen {
  def generateKeepActivity(
    keep: Keep, sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])], elizaActivity: Option[CrossServiceKeepActivity],
    ktls: Seq[KeepToLibrary], ktus: Seq[KeepToUser],
    userById: Map[Id[User], BasicUser], libById: Map[Id[Library], BasicLibrary], orgByLibraryId: Map[Id[Library], BasicOrganization],
    eventsBefore: Option[DateTime], maxEvents: Int)(implicit airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig, pubIdConfig: PublicIdConfiguration): KeepActivity = {
    import com.keepit.common.util.DescriptionElements._

    lazy val initialKeepEvent = {
      val sortedKtls = ktls.sortBy(_.addedAt.getMillis * -1)(Ord.descending)
      val sortedKtus = ktus.sortBy(_.addedAt.getMillis * -1)(Ord.descending)

      val basicAuthor = sourceAttrOpt.map {
        case (sourceAttr, basicUserOpt) => BasicAuthor(sourceAttr, basicUserOpt)
      }.orElse(keep.userId.flatMap(userById.get).map(BasicAuthor.fromUser))
      if (basicAuthor.isEmpty) airbrake.notify(s"[activityLog] can't generate author for keep ${keep.id.get}, keep.user = ${keep.userId}")
      val authorElement: DescriptionElement = basicAuthor.map(fromBasicAuthor).getOrElse(fromText("Someone"))

      val header = sortedKtls.headOption match {
        case Some(ktl) =>
          val library: DescriptionElement = libById.get(ktl.libraryId).map(fromBasicLibrary).getOrElse("a library")
          val orgOpt = orgByLibraryId.get(ktl.libraryId).map(fromBasicOrg)
          val sourceElement = sourceAttrOpt.map(_._1).map {
            case SlackAttribution(message, _) => DescriptionElements(ImageElement(Some(message.permalink), ImageUrls.SLACK_LOGO), message.channel.name.map(_.value --> LinkElement(message.permalink)))
            case TwitterAttribution(tweet) => DescriptionElements(ImageElement(Some(tweet.permalink), ImageUrls.TWITTER_LOGO), tweet.user.screenName.value --> LinkElement(tweet.permalink))
          }
          DescriptionElements(
            authorElement, "kept this into", library,
            orgOpt.map(org => DescriptionElements("in", org)),
            sourceElement
          )
        case None =>
          sortedKtus.headOption match {
            case None =>
              airbrake.notify(s"[activityLog] no ktu or ktls on ${keep.id.get}, can't generate initial keep event")
              DescriptionElements(authorElement, "kept this page")
            case Some(firstKtu) =>
              val firstMinute = firstKtu.addedAt.plusMinutes(1)
              val firstSentTo = sortedKtus.takeWhile(_.addedAt.getMillis <= firstMinute.getMillis)
                .collect { case ktu if !keep.userId.contains(ktu.userId) => userById.get(ktu.userId) }.flatten
              DescriptionElements(authorElement, "started a discussion", if (firstSentTo.nonEmpty) DescriptionElements("with", DescriptionElements.unwordsPretty(firstSentTo.map(fromBasicUser))) else "on this page")
          }
      }
      val body = DescriptionElements(keep.note)
      BasicKeepEvent(
        id = None,
        author = basicAuthor.get,
        KeepEventKind.Initial,
        header = header,
        body = body,
        timestamp = keep.keptAt,
        source = None
      )
    }

    val elizaEvents = elizaActivity.map(_.messages.flatMap { message =>
      message.sentBy match {
        case Some(userOrNonUser) =>
          import DescriptionElements._
          val userOpt = userOrNonUser.left.toOption.flatMap(userById.get)
          val msgAuthor: DescriptionElement = userOrNonUser.fold[Option[DescriptionElement]](_ => userOpt.map(fromBasicUser), nonUser => Some(nonUser.id)).getOrElse("Someone")
          Some(BasicKeepEvent(
            id = Some(Message.publicId(message.id)),
            author = userOrNonUser.fold(userId => userOpt.map(BasicAuthor.fromUser).get, nonUser => BasicAuthor.fromNonUser(nonUser)),
            KeepEventKind.Comment,
            header = DescriptionElements(msgAuthor, "commented on this page"),
            body = DescriptionElements(message.text),
            timestamp = message.sentAt,
            source = KeepEventSource.fromMessageSource(message.source)
          ))
        case None =>
          message.auxData match {
            case Some(AddParticipants(addedBy, addedUsers, addedNonUsers)) =>
              val basicAddedBy = userById.get(addedBy)
              val addedElement = unwordsPretty(addedUsers.flatMap(userById.get).map(fromBasicUser) ++ addedNonUsers.map(fromNonUser))
              Some(BasicKeepEvent(
                id = None, // could use message.id, but system message ids don't need to be exposed to clients yet
                author = BasicAuthor.fromUser(basicAddedBy.get),
                KeepEventKind.AddParticipants,
                header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "added", addedElement),
                body = DescriptionElements(),
                timestamp = message.sentAt,
                source = KeepEventSource.fromMessageSource(message.source)
              ))
            case dataOpt =>
              if (dataOpt.isEmpty) airbrake.notify(s"[activityLog] messsage ${message.id} has no .sentBy and no .auxData, can't generate event")
              None
          }
      }
    }).getOrElse(Seq.empty)

    val events = {
      val trimmedElizaEvents = elizaEvents.filter(ev => eventsBefore.forall(_.getMillis > ev.timestamp.getMillis)).take(maxEvents) // todo(cam): do this on eliza
      if (trimmedElizaEvents.size == maxEvents) trimmedElizaEvents else trimmedElizaEvents :+ initialKeepEvent
    }

    KeepActivity(
      events = events,
      numComments = elizaActivity.map(_.numComments).getOrElse(0) + keep.note.size)
  }
}
