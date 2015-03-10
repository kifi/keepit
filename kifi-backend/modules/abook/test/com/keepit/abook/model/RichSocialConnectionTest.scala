package com.keepit.abook.model

import org.specs2.mutable.Specification
import com.keepit.abook.ABookTestInjector
import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.common.db.Id
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.common.mail.EmailAddress

class RichSocialConnectionTest extends Specification with ABookTestInjector {

  "RichSocialConnectionRepo" should {

    val kifiLéo = Id[User](134)
    val facebookLéo = Id[SocialUserInfo](53716)
    val fortytwoLéo = SocialUserInfo(
      id = Some(Id(260178)),
      socialId = SocialId("iDoNotMatter"),
      userId = Some(kifiLéo),
      networkType = SocialNetworks.FORTYTWO,
      fullName = "Léo Grimaldi"
    )

    val kifiStephen = Id[User](243)
    val facebookStephen = Id[SocialUserInfo](94667)
    val fortytwoStephen = SocialUserInfo(
      id = Some(Id(260283)),
      socialId = SocialId("iDoNotMatter"),
      userId = Some(kifiStephen),
      networkType = SocialNetworks.FORTYTWO,
      fullName = "Stephen Kemmerling"
    )

    val facebookEishay = SocialUserInfo(
      id = Some(Id(1)),
      socialId = SocialId("iDoNotMatter"),
      networkType = SocialNetworks.FACEBOOK,
      fullName = "Eishay Smith"
    )

    val facebookMarvin = SocialUserInfo(
      id = Some(Id(42)),
      socialId = SocialId("iDoNotMatter"),
      networkType = SocialNetworks.FACEBOOK,
      fullName = "Marvin"
    )

    val contact42 = EContact(userId = kifiLéo, abookId = Id(1), emailAccountId = Id(42), email = EmailAddress("grassfed42@organicintegers.com"), name = Some("FortyTwo"))

    "intern and retrieve rich social network connections" in {
      withDb() { implicit injector =>
        val léoToMarvin = db.readWrite { implicit session =>
          richConnectionRepo.internRichConnection(kifiLéo, Some(facebookLéo), Left(facebookMarvin))
        }
        léoToMarvin.userId === kifiLéo
        léoToMarvin.userSocialId === Some(facebookLéo)
        léoToMarvin.friendSocialId === facebookMarvin.id
        léoToMarvin.friendName === Some(facebookMarvin.fullName)
        léoToMarvin.connectionType === SocialNetworks.FACEBOOK
        léoToMarvin.kifiFriendsCount === 1
        léoToMarvin.commonKifiFriendsCount === 0
        léoToMarvin.invitationsSent === 0
        léoToMarvin.invitedBy === 0

        db.readWrite { implicit session =>
          richConnectionRepo.internRichConnection(kifiLéo, Some(facebookLéo), Left(facebookMarvin)) === léoToMarvin
          richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)) === Some(léoToMarvin)
        }
      }
    }

    //todo(leo): fix test
    //    "increment friends counts on new social connections with no existing kifi connection" in {
    //      withDb() { implicit injector =>
    //        val stephenToMarvin = db.readWrite { implicit session =>
    //          richConnectionRepo.internRichConnection(kifiStephen, Some(facebookStephen), Left(facebookMarvin))
    //        }
    //
    //        stephenToMarvin.kifiFriendsCount === 2
    //        stephenToMarvin.commonKifiFriendsCount === 0
    //
    //        val léoToMarvin = db.readOnlyMaster { implicit session => richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get }
    //        léoToMarvin.kifiFriendsCount === 2
    //        léoToMarvin.commonKifiFriendsCount === 0
    //      }
    //    }

    //    "increment friends counts on new kifi connections" in {
    //      withDb() { implicit injector =>
    //        db.readWrite { implicit session =>
    //          richConnectionRepo.internRichConnection(kifiLéo, fortytwoLéo.id, Left(fortytwoStephen))
    //          richConnectionRepo.internRichConnection(kifiStephen, fortytwoStephen.id, Left(fortytwoLéo))
    //        }
    //
    //        db.readOnlyMaster { implicit session =>
    //          val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
    //          val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
    //          stephenToMarvin.kifiFriendsCount === 2
    //          stephenToMarvin.commonKifiFriendsCount === 1
    //          léoToMarvin.kifiFriendsCount === 2
    //          léoToMarvin.commonKifiFriendsCount === 1
    //        }
    //      }
    //    }

    //    "increment friends counts on new social connections with existing kifi connection" in {
    //      withDb() { implicit injector =>
    //        val stephenToEishay = db.readWrite { implicit session =>
    //          richConnectionRepo.internRichConnection(kifiStephen, Some(facebookStephen), Left(facebookEishay))
    //        }
    //        stephenToEishay.kifiFriendsCount === 1
    //        stephenToEishay.commonKifiFriendsCount === 0
    //
    //        val léoToEishay = db.readWrite { implicit session =>
    //          richConnectionRepo.internRichConnection(kifiLéo, Some(facebookLéo), Left(facebookEishay))
    //        }
    //        léoToEishay.kifiFriendsCount === 2
    //        léoToEishay.commonKifiFriendsCount === 1
    //
    //        val updatedStephenToEishay = db.readOnlyMaster { implicit session => richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookEishay.id.get)).get }
    //        updatedStephenToEishay.kifiFriendsCount === 2
    //        updatedStephenToEishay.commonKifiFriendsCount === 1
    //      }
    //    }

    "intern and retrieve rich social email connections" in {
      withDb() { implicit injector =>

        val léoToGrassfed42 = db.readWrite { implicit session =>
          richConnectionRepo.internRichConnection(kifiLéo, None, Right(contact42))
        }

        léoToGrassfed42.userId === kifiLéo
        léoToGrassfed42.userSocialId === None
        léoToGrassfed42.friendEmailAddress === Some(contact42.email)
        léoToGrassfed42.friendName === contact42.name
        léoToGrassfed42.connectionType === SocialNetworks.EMAIL
        léoToGrassfed42.kifiFriendsCount === 1
        léoToGrassfed42.commonKifiFriendsCount === 0
        léoToGrassfed42.invitationsSent === 0
        léoToGrassfed42.invitedBy === 0

        db.readWrite { implicit session =>
          richConnectionRepo.internRichConnection(kifiLéo, None, Right(contact42)) === léoToGrassfed42
          richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Right(contact42.email)) === Some(léoToGrassfed42)
        }
      }
    }

    //todo(leo): fix test
    //    "keep track of invitations" in {
    //      withDb() { implicit injector =>
    //        db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiLéo, Left(facebookMarvin.id.get)) }
    //
    //        db.readOnlyMaster { implicit session =>
    //          val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
    //          stephenToMarvin.invitationsSent === 0
    //          stephenToMarvin.invitedBy === 1
    //
    //          val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
    //          léoToMarvin.invitationsSent === 1
    //          léoToMarvin.invitedBy === 1
    //        }
    //
    //        db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiStephen, Left(facebookMarvin.id.get)) }
    //
    //        db.readOnlyMaster { implicit session =>
    //          val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
    //          stephenToMarvin.invitationsSent === 1
    //          stephenToMarvin.invitedBy === 2
    //
    //          val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
    //          léoToMarvin.invitationsSent === 1
    //          léoToMarvin.invitedBy === 2
    //        }
    //
    //        db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiLéo, Right(contact42.email)) }
    //
    //        db.readOnlyMaster { implicit session =>
    //          val léoToGrassfed42 = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Right(contact42.email)).get
    //          léoToGrassfed42.invitationsSent === 1
    //          léoToGrassfed42.invitedBy === 1
    //
    //          val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
    //          léoToMarvin.invitationsSent === 1
    //          léoToMarvin.invitedBy === 2
    //        }
    //      }
    //    }

    "have correct queries" in { //This is for running straight up sql queries to make sure they are correctly formatted
      withDb() { implicit injector =>
        db.readOnlyMaster { implicit session => richConnectionRepo.dedupedWTIForUser(Id[User](243), 50) }
        1 === 1
      }
    }
  }
}
