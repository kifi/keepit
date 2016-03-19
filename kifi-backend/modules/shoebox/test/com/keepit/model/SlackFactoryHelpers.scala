package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, FakeClock }
import com.keepit.model.LibraryToSlackChannelFactory.PartialLibraryToSlackChannel
import com.keepit.model.SlackChannelFactory.PartialSlackChannel
import com.keepit.model.SlackChannelToLibraryFactory.PartialSlackChannelToLibrary
import com.keepit.model.SlackIncomingWebhookFactory.PartialSlackIncomingWebhook
import com.keepit.model.SlackTeamFactory.PartialSlackTeam
import com.keepit.model.SlackTeamMembershipFactory.PartialSlackTeamMembership
import com.keepit.slack.models._

object SlackTeamMembershipFactoryHelper {
  implicit class SlackTeamMembershipPersister(partial: PartialSlackTeamMembership) {
    def saved(implicit injector: Injector, session: RWSession): SlackTeamMembership = {
      injector.getInstance(classOf[SlackTeamMembershipRepo]).save(partial.stm)
    }
  }
}

object SlackTeamFactoryHelper {
  implicit class SlackTeamPersister(partial: PartialSlackTeam) {
    def saved(implicit injector: Injector, session: RWSession): SlackTeam = {
      injector.getInstance(classOf[SlackTeamRepo]).save(partial.team)
    }
  }
}

object SlackChannelFactoryHelper {
  implicit class SlackChannelPersister(partial: PartialSlackChannel) {
    def saved(implicit injector: Injector, session: RWSession): SlackChannel = {
      injector.getInstance(classOf[SlackChannelRepo]).save(partial.channel)
    }
  }
}

object SlackIncomingWebhookInfoFactoryHelper {
  implicit class SlackIncomingWebhookInfoPersister(partial: PartialSlackIncomingWebhook) {
    def saved(implicit injector: Injector, session: RWSession): SlackIncomingWebhookInfo = {
      injector.getInstance(classOf[SlackIncomingWebhookInfoRepo]).save(partial.siw)
    }
  }
}
object SlackChannelToLibraryFactoryHelper {
  implicit class SlackChannelToLibraryPersister(partial: PartialSlackChannelToLibrary) {
    def saved(implicit injector: Injector, session: RWSession): SlackChannelToLibrary = {
      injector.getInstance(classOf[SlackChannelToLibraryRepo]).save(partial.stl)
    }
  }
}
object LibraryToSlackChannelFactoryHelper {
  implicit class LibraryToSlackChannelPersister(partial: PartialLibraryToSlackChannel) {
    def saved(implicit injector: Injector, session: RWSession): LibraryToSlackChannel = {
      val now = injector.getInstance(classOf[FakeClock]).now
      injector.getInstance(classOf[LibraryToSlackChannelRepo]).save(partial.lts.withNextPushAt(now))
    }
  }
}
