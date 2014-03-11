package com.keepit.abook.model

import org.specs2.mutable.Specification
import com.keepit.abook.ABookTestInjector
import com.keepit.model.{Invitation, EContact, SocialUserInfo, User}
import com.keepit.common.db.Id
import com.keepit.social.{SocialId, SocialNetworks}

class RichSocialConnectionTest extends Specification with ABookTestInjector  {

  "RichSocialConnectionRepo" should withDb() { implicit injector =>

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

    val contact42 = EContact(userId = kifiLéo, email = "grassfed42@organicintegers.com", name = Some("FortyTwo"))

    "intern and retrieve rich social network connections" in {
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
      léoToMarvin.invitation === None
      léoToMarvin.invitationCount === 0

      db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiLéo, Some(facebookLéo), Left(facebookMarvin)) === léoToMarvin
        richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)) === Some(léoToMarvin)
      }
    }

    "increment friends counts on new social connections with no existing kifi connection" in {
      val stephenToMarvin = db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiStephen, Some(facebookStephen), Left(facebookMarvin))
      }

      stephenToMarvin.kifiFriendsCount === 2
      stephenToMarvin.commonKifiFriendsCount === 0

      val léoToMarvin = db.readOnly { implicit session => richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get }
      léoToMarvin.kifiFriendsCount === 2
      léoToMarvin.commonKifiFriendsCount === 0
    }

    "increment friends counts on new kifi connections" in {
      db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiLéo, fortytwoLéo.id, Left(fortytwoStephen))
        richConnectionRepo.internRichConnection(kifiStephen, fortytwoStephen.id, Left(fortytwoLéo))
      }

      db.readOnly { implicit session =>
        val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
        val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
        stephenToMarvin.kifiFriendsCount === 2
        stephenToMarvin.commonKifiFriendsCount === 1
        léoToMarvin.kifiFriendsCount === 2
        léoToMarvin.commonKifiFriendsCount === 1
      }
    }

    "increment friends counts on new social connections with existing kifi connection" in {
      val stephenToEishay = db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiStephen, Some(facebookStephen), Left(facebookEishay))
      }
      stephenToEishay.kifiFriendsCount === 1
      stephenToEishay.commonKifiFriendsCount === 0

      val léoToEishay = db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiLéo, Some(facebookLéo), Left(facebookEishay))
      }
      léoToEishay.kifiFriendsCount === 2
      léoToEishay.commonKifiFriendsCount === 1

      val updatedStephenToEishay = db.readOnly { implicit session => richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookEishay.id.get)).get }
      updatedStephenToEishay.kifiFriendsCount === 2
      updatedStephenToEishay.commonKifiFriendsCount === 1
    }

    "intern and retrieve rich social email connections" in {

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
      léoToGrassfed42.invitation === None
      léoToGrassfed42.invitationCount === 0

      db.readWrite { implicit session =>
        richConnectionRepo.internRichConnection(kifiLéo, None, Right(contact42)) === léoToGrassfed42
        richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Right(contact42.email)) === Some(léoToGrassfed42)
      }
    }

    "keep track of invitations" in {
      val léoToMarvinInvitation = Id[Invitation](12)
      db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiLéo, léoToMarvinInvitation, Left(facebookMarvin.id.get)) }

      db.readOnly { implicit session =>
        val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
        stephenToMarvin.invitation === None
        stephenToMarvin.invitationCount === 1

        val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
        léoToMarvin.invitation === Some(léoToMarvinInvitation)
        léoToMarvin.invitationCount === 1
      }

      val stephenToMarvinInvitation = Id[Invitation](13)
      db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiStephen, stephenToMarvinInvitation, Left(facebookMarvin.id.get)) }

      db.readOnly { implicit session =>
        val stephenToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiStephen, Left(facebookMarvin.id.get)).get
        stephenToMarvin.invitation === Some(stephenToMarvinInvitation)
        stephenToMarvin.invitationCount === 2

        val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
        léoToMarvin.invitation === Some(léoToMarvinInvitation)
        léoToMarvin.invitationCount === 2
      }

      val léoToGrassfed42Invitation = Id[Invitation](14)
      db.readWrite { implicit session => richConnectionRepo.recordInvitation(kifiLéo, léoToGrassfed42Invitation, Right(contact42.email)) }

      db.readOnly { implicit session =>
        val léoToGrassfed42 = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Right(contact42.email)).get
        léoToGrassfed42.invitation === Some(léoToGrassfed42Invitation)
        léoToGrassfed42.invitationCount === 1

        val léoToMarvin = richConnectionRepo.getByUserAndSocialFriend(kifiLéo, Left(facebookMarvin.id.get)).get
        léoToMarvin.invitation === Some(léoToMarvinInvitation)
        léoToMarvin.invitationCount === 2
      }
    }

    "have correct queries" in { //This is for running straight up sql queries to make sure they are correctly formatted
      db.readOnly { implicit session => richConnectionRepo.dedupedWTIForUser(Id[User](243), 50)}
      1===1
    }
  }
}
