package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.commanders.gen.{ BasicULOBatchFetcher, KeepActivityGen }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.{ AirbrakeNotifier, FakeAirbrakeNotifier }
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.common.util.{ EnglishProperNouns, RoughlyHumanNames, DescriptionElements }
import com.keepit.eliza.{ ElizaServiceClient, FakeElizaServiceClientImpl }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ BasicLibrary, KeepEventData, KeepEventRepo, KeepFactory, KeepRecipientsDiff, KeepToUserRepo, Library, LibraryFactory, User, UserFactory }
import com.keepit.social.{ BasicAuthor, BasicUser }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.Hours
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }

class KeepActivityGenTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeClockModule())

  implicit def airbrake(implicit injector: Injector) = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
  implicit def imageConfig(implicit injector: Injector) = inject[S3ImageConfig]
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

  def eliza(implicit injector: Injector) = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]

  "KeepActivityGen" should {
    "generate keep activity" in {
      withDb(modules: _*) { implicit injector =>
        val keepEventCommander = inject[KeepEventCommander]

        val userNames = (RoughlyHumanNames.firsts zip RoughlyHumanNames.lasts).toIterator
        val libNames = EnglishProperNouns.planets.toIterator
        val (user, keep, usersToAdd, libsToAdd) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Benjamin", "Button").saved
          val usersToAdd = UserFactory.users(4).map(_.withFullName(userNames.next())).saved
          val libsToAdd = LibraryFactory.libraries(8).map(_.withOwner(user).withName(libNames.next())).saved
          val keep = KeepFactory.keep().withUser(user.id.get).saved
          (user, keep, usersToAdd, libsToAdd)
        }
        val basicUser = BasicUser.fromUser(user)
        val ktus = db.readOnlyMaster(implicit s => inject[KeepToUserRepo].getAllByKeepId(keep.id.get))

        val userIdsToAdd = usersToAdd.map(_.id.get).toSet
        val libIdsToAdd = libsToAdd.map(_.id.get).toSet

        val basicUserById = usersToAdd.map(usr => usr.id.get -> BasicUser.fromUser(usr)).toMap + (user.id.get -> basicUser)
        val basicLibById = libsToAdd.map(lib => lib.id.get -> BasicLibrary(lib, basicUser, None, None)).toMap

        import com.keepit.common.util.DescriptionElements._
        val eventHeaders = db.readWrite { implicit s =>
          Map(userIdsToAdd.take(2) -> true, libIdsToAdd.take(4) -> false, userIdsToAdd.takeRight(2) -> true, libIdsToAdd.takeRight(4) -> false).map {
            case (ids, true) => // users
              val adding = ids.map(id => Id[User](id.id))
              keepEventCommander.persistKeepEventAndUpdateEliza(keep.id.get, KeepEventData.ModifyRecipients(user.id.get, KeepRecipientsDiff.addUsers(adding)), eventTime = Some(fakeClock.now()), source = None)
              fakeClock += Hours.ONE
              val entities = adding.map(id => generateUserElement(basicUserById(id), fullName = false)).toSeq
              DescriptionElements.formatPlain(DescriptionElements(basicUser, "sent this to", unwordsPretty(entities)))
            case (ids, false) => // libraries
              val adding = ids.map(id => Id[Library](id.id))
              keepEventCommander.persistKeepEventAndUpdateEliza(keep.id.get, KeepEventData.ModifyRecipients(user.id.get, KeepRecipientsDiff.addLibraries(adding)), eventTime = Some(fakeClock.now()), source = None)
              fakeClock += Hours.ONE
              val entities = adding.map(id => fromBasicLibrary(basicLibById(id))).toSeq
              DescriptionElements.formatPlain(DescriptionElements(basicUser, "sent this to", unwordsPretty(entities)))
          }.toSeq
        }

        val events = db.readOnlyMaster { implicit s => inject[KeepEventRepo].pageForKeep(keep.id.get, fromTime = None, limit = 10) }
        val activityBF = KeepActivityGen.generateKeepActivity(keep, sourceAttrOpt = None, events = events, discussionOpt = None, ktls = Seq.empty, ktus, maxEvents = 5)
        val activity = inject[BasicULOBatchFetcher].run(activityBF)
        activity.events.size === 5 // NB(ryan): There may be 6 events because of "event-splitting" with the note
        activity.events.map { event => DescriptionElements.formatPlain(event.header) } === (eventHeaders.reverse :+ "Benjamin Button sent this")

        val jsActivity = Json.toJson(activity)

        val jsTimestamp = ((jsActivity \ "events").as[Seq[JsObject]].head \ "timestamp").asOpt[Long]
        jsTimestamp must beSome(activity.events.head.timestamp.getMillis)

        val initialKeepEvent = activity.events.last
        initialKeepEvent.author === BasicAuthor.fromUser(basicUser)
        1 === 1
      }
    }
  }
}
