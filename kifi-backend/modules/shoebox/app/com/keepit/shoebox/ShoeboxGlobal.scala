package com.keepit.shoebox

import com.keepit.FortyTwoGlobal
import com.keepit.commanders.SuggestedSearchTermUpdatePlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.concurrent.ForkJoinExecContextPlugin
import com.keepit.common.healthcheck._
import com.keepit.common.integration.AutogenReaperPlugin
import com.keepit.common.mail.{ MailSenderPlugin, MailToKeepPlugin }
import com.keepit.integrity.{ DataIntegrityPlugin, UriIntegrityPlugin }
import com.keepit.model._
import com.keepit.reports._
import com.keepit.shoebox.cron.ActivityEmailCronPlugin
import com.keepit.social.SocialGraphPlugin
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Mode._
import play.api._

object ShoeboxGlobal extends FortyTwoGlobal(Prod) with ShoeboxServices {

  val module = ShoeboxProdModule()

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
    startShoeboxServices()
    super.onStart(app)
    log.info("shoebox started")
  }
}

trait ShoeboxServices { self: FortyTwoGlobal =>
  def startShoeboxServices() {
    require(injector.instance[ForkJoinExecContextPlugin] != null)
    //    require(injector.instance[SocialGraphPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[MailSenderPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[AutogenReaperPlugin] != null) //make sure its not lazy loaded
    injector.instance[MailSenderPlugin].processOutbox()
    //    require(injector.instance[MailToKeepPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[DataIntegrityPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[UriIntegrityPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[GeckoboardReporterPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[UriIntegrityPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[LoadBalancerCheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[ShoeboxTasksPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[ActivityEmailCronPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[SuggestedSearchTermUpdatePlugin] != null) // make sure its not lazy loaded

    // DB sequencing plugins
    //    require(injector.instance[NormalizedURISequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[UserConnectionSequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[LibrarySequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[LibraryMembershipSequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[SocialConnectionSequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[ChangedURISeqPlugin] != null) // make sure its not lazy loaded
    //    require(injector.instance[InvitationSequencingPlugin] != null) // make sure its not lazy loaded
    //    require(injector.instance[CollectionSeqPlugin] != null) // make sure its not lazy loaded
    //    require(injector.instance[SocialUserInfoSequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[UserSeqPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[PhraseSequencingPlugin] != null) // make sure its not lazy loaded
    //    require(injector.instance[UserEmailAddressSeqPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[KeepSequencingPlugin] != null) //make sure its not lazy loaded
    //    require(injector.instance[UserIpAddressSequencingPlugin] != null) // make sure it's not lazy loaded
    //    require(injector.instance[OrganizationSequencingPlugin] != null)
    //    require(injector.instance[OrganizationMembershipSequencingPlugin] != null)
    //    require(injector.instance[OrganizationMembershipCandidateSequencingPlugin] != null)
    //    require(injector.instance[OrganizationDomainOwnershipSequencingPlugin] != null)
  }
}
