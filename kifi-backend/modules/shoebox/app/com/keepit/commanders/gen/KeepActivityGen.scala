package com.keepit.commanders.gen

import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.util.{ Ord, LinkElement, ImageElement, DescriptionElements, DescriptionElement }
import com.keepit.discussion.CrossServiceKeepActivity
import com.keepit.model.KeepEvent.AddParticipants
import com.keepit.model.{ KeepEventSourceKind, BasicKeepEvent, KeepEventSource, KeepEventKind, KeepActivity, TwitterAttribution, SlackAttribution, BasicOrganization, BasicLibrary, Library, User, KeepToUser, KeepToLibrary, SourceAttribution, Keep }
import com.keepit.social.{ ImageUrls, BasicUser, BasicAuthor }
import org.joda.time.DateTime

object KeepActivityGen {
  def generateKeepActivity(
    keep: Keep, sourceAttrOpt: Option[(SourceAttribution, Option[BasicUser])], elizaActivity: Option[CrossServiceKeepActivity],
    ktls: Seq[KeepToLibrary], ktus: Seq[KeepToUser],
    userById: Map[Id[User], BasicUser], libById: Map[Id[Library], BasicLibrary], orgByLibraryId: Map[Id[Library], BasicOrganization],
    eventsBefore: Option[DateTime], maxEvents: Int)(implicit airbrake: AirbrakeNotifier, imageConfig: S3ImageConfig): KeepActivity = {
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
          DescriptionElements(
            authorElement, "kept this into", library,
            orgOpt.map(org => DescriptionElements("in", org))
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

      val source = sourceAttrOpt.map(_._1).map {
        case SlackAttribution(message, _) => KeepEventSource(KeepEventSourceKind.Slack, Some(message.permalink))
        case TwitterAttribution(tweet) => KeepEventSource(KeepEventSourceKind.Twitter, Some(tweet.permalink))
      }

      val body = DescriptionElements(keep.note)
      BasicKeepEvent(
        KeepEventKind.Initial,
        image = basicAuthor.map(_.image).getOrElse("0.jpg"),
        header = header,
        body = body,
        timestamp = keep.keptAt,
        source = source
      )
    }

    val elizaEvents = elizaActivity.map(_.messages.flatMap { message =>
      message.sentBy match {
        case Some(userOrNonUser) =>
          import DescriptionElements._
          val userOpt = userOrNonUser.left.toOption.flatMap(userById.get)
          val msgAuthor: DescriptionElement = userOrNonUser.fold[Option[DescriptionElement]](userId => userOpt.map(fromBasicUser), nonUser => Some(nonUser.id)).getOrElse {
            airbrake.notify(s"[activityLog] could not generate message author name on keep ${keep.id.get}")
            "Someone"
          }
          Some(BasicKeepEvent(
            KeepEventKind.Comment,
            image = userOpt.map(_.picturePath.getUrl).getOrElse("0.jpg"), // todo(cam): figure out a protocol for non-user images
            header = DescriptionElements(msgAuthor, "commented on this page"),
            body = DescriptionElements(message.text),
            timestamp = message.sentAt,
            source = KeepEventSourceKind.fromMessageSource(message.source).map(kind => KeepEventSource(kind, url = None))
          ))
        case None =>
          message.auxData match {
            case Some(AddParticipants(addedBy, addedUsers, addedNonUsers)) =>
              val basicAddedBy = userById.get(addedBy)
              val addedElement = unwordsPretty(addedUsers.flatMap(userById.get).map(fromBasicUser) ++ addedNonUsers.map(fromNonUser))
              Some(BasicKeepEvent(
                KeepEventKind.AddParticipants,
                image = basicAddedBy.map(_.picturePath.getUrl).getOrElse {
                  airbrake.notify(s"[activityLog] can't find user $addedBy for keep ${keep.id.get}")
                  "0.jpg"
                },
                header = DescriptionElements(basicAddedBy.map(fromBasicUser).getOrElse(fromText("Someone")), "added", addedElement),
                body = DescriptionElements(),
                timestamp = message.sentAt,
                source = KeepEventSourceKind.fromMessageSource(message.source).map(kind => KeepEventSource(kind, url = None))
              ))
            case dataOpt =>
              if (dataOpt.isEmpty) airbrake.notify(s"[activityLog] messsage ${message.id} has no .sentBy and no .auxData, can't generate event")
              None
          }
      }
    }).getOrElse(Seq.empty)

    import com.keepit.common.util.Ord._
    val events = {
      val trimmedElizaEvents = elizaEvents.filter(ev => eventsBefore.forall(_.getMillis > ev.timestamp.getMillis)).take(maxEvents) // todo(cam): do this on eliza
      if (trimmedElizaEvents.size == maxEvents) trimmedElizaEvents else trimmedElizaEvents :+ initialKeepEvent
    }

    KeepActivity(
      events = events,
      numEvents = events.size, // todo(cam): fetch the eliza total event count
      numComments = elizaActivity.map(_.numComments).getOrElse(0) + keep.note.size)
  }
}
